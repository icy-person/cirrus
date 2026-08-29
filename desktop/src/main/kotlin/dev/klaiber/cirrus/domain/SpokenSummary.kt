package dev.klaiber.cirrus.domain

import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.domain.model.ReadAloudMode
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What actually gets spoken when somebody presses play on an answer.
 *
 * Reading an answer out in full is the obvious implementation and the wrong feature. A written
 * answer is built to be *skimmed*: it has headings to jump between, a table to glance at, a code
 * block to ignore, and a summary at the end that the eye reaches in two seconds. Speech has none of
 * that. It is strictly linear and it runs at about two and a half words a second, so a nine-hundred
 * word answer — an ordinary length here — is six minutes of audio with no way to skip the part you
 * did not need. People press play to hear what the answer *said*, and then stop it a minute in.
 *
 * So a long answer is summarised before it is spoken, and the summary is deliberately not a
 * headline. Two or three spoken paragraphs — the finding, the reasoning that matters, the caveat —
 * because a single sentence loses the very thing somebody wanted read to them while they were
 * cooking or walking. The written answer stays on screen and is still the record; this is the
 * version you can listen to.
 *
 * Three rules keep it from being a worse feature than the thing it replaced:
 *
 *  - **Short answers are read verbatim.** Summarising four sentences produces three, more slowly,
 *    and having lost something. Below [VERBATIM_CHARS] there is nothing to gain.
 *  - **A failure is never silence.** No model configured, a request that times out, an empty reply
 *    — every one of them falls through to [condense], which is pure, local and instant. The button
 *    always makes sound.
 *  - **The user can turn it off.** [ReadAloudMode.FULL] exists because somebody proofreading their
 *    own text wants every word of it, and no summariser should get a vote on that.
 */
class SpokenSummary(
    private val engine: ChatEngine,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
) {

    /**
     * Turns the speakable form of an answer into the version worth listening to.
     *
     * [spoken] has already been through `markdownToSpeech`, which is the right input rather than
     * the raw markdown: the code blocks are already announced instead of quoted, the tables are
     * already sentences, and what is summarised is therefore exactly what would otherwise have been
     * read. Summarising the markdown instead would spend the model's attention on syntax and risk a
     * summary that mentions a code listing nobody was ever going to hear.
     */
    suspend fun forSpeech(spoken: String): String {
        val text = spoken.trim()
        val current = settings.current.value

        if (current.readAloudMode == ReadAloudMode.FULL) return text
        if (text.length <= VERBATIM_CHARS) return text

        val model = current.defaultModel.takeIf { it.isNotBlank() } ?: return condense(text)
        val thinks = models.find(model)?.supportsThinking ?: ModelInfo.mayThink(model)

        // Bounded, because this sits between a button press and the first sound. A summariser that
        // takes fifteen seconds to decide has already lost to reading the thing out.
        val written = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            engine.complete(
                model = model,
                system = SYSTEM_PROMPT,
                // Enough of the answer to summarise honestly, and the *end* is kept as well as the
                // beginning: conclusions live there, and a summary that stops two thirds of the way
                // through a long answer is confidently wrong about how it ended.
                user = clipForSummary(text, MAX_INPUT_CHARS),
                supportsThinking = thinks,
                tokenBudget = if (thinks) THINKING_BUDGET else BUDGET,
                temperature = 0.3,
            )
        }

        return written?.let(::tidy)?.takeIf { it.length > MIN_USEFUL } ?: condense(text)
    }

    private companion object {

        /**
         * Written for the ear, and every clause in it is load-bearing.
         *
         * "No markdown" because the output goes to a speech engine that would say the asterisks.
         * "Do not say you are summarising" because a preamble is four seconds of nothing at the one
         * moment the listener is deciding whether this was worth pressing. And the length is given
         * in spoken time rather than in words, since that is the unit the listener experiences and
         * the one a model reasons about badly if you give it any other.
         */
        const val SYSTEM_PROMPT =
            "You turn a written answer into the version of it that gets read aloud. Write two or " +
                "three short paragraphs — roughly a minute of speech — covering what the answer " +
                "actually concluded, the reasoning that matters, and anything the listener would " +
                "be misled by not hearing. Keep specifics: names, numbers, dates and the actual " +
                "recommendation. Drop the scaffolding: headings, lists, code, tables, links and " +
                "anything that only made sense on a page. Write plain sentences a person can say " +
                "out loud, in the same voice and tense as the original, addressed to the same " +
                "reader. No markdown, no bullet points, no headings. Do not introduce it, do not " +
                "say you are summarising, and do not mention the original — begin with the " +
                "substance. Close by saying the full answer is on screen."

        /**
         * Below this, the written answer already *is* the spoken one.
         *
         * Roughly ninety seconds of speech. It is set by how long somebody will listen without
         * wanting to skip rather than by how long an answer is: under a minute and a half, there is
         * nothing to skip past, and a summary can only lose something.
         */
        const val VERBATIM_CHARS = 1_200

        /** Past this, the middle of the answer is dropped rather than the end. */
        const val MAX_INPUT_CHARS = 12_000

        const val BUDGET = 400
        const val THINKING_BUDGET = 900
        const val REQUEST_TIMEOUT_MS = 20_000L

        /** A reply this short is a refusal or a stray token, not a summary. */
        const val MIN_USEFUL = 40
    }
}

/**
 * Keeps the beginning and the end of a very long answer, and says where the join is.
 *
 * A head-only clip is the version that reads correctly and summarises wrongly: the recommendation,
 * the caveat and the "so, in short" are all at the bottom of a long answer, and a summariser that
 * never saw them will confidently describe the middle as the conclusion.
 */
internal fun clipForSummary(text: String, max: Int = 12_000): String {
    if (text.length <= max) return text
    val head = (max * 2) / 3
    val tail = max - head
    return text.take(head).trimEnd() +
        "\n\n[…the middle of the answer is omitted here…]\n\n" +
        text.takeLast(tail).trimStart()
}

/**
 * The local summary: the opening, the closing, and nothing in between.
 *
 * Extractive rather than clever, because this runs when the model could not be asked — no default
 * model, a timed-out request, an empty reply — and the only thing worse than a mediocre summary at
 * that moment is silence. It leans on the same fact that [clipForSummary] does: a written answer
 * puts its subject first and its conclusion last, so the two ends of it are a real if blunt summary
 * of the whole, and the seam is announced so nobody hears two unrelated halves joined without
 * warning.
 */
internal fun condense(text: String, target: Int = 900): String {
    val trimmed = text.trim()
    if (trimmed.length <= target) return trimmed

    val paragraphs = trimmed.split(PARAGRAPH_BREAK)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (paragraphs.size <= 1) return trimmed.take(target).substringBeforeLast(' ') + "… " + ON_SCREEN

    val opening = mutableListOf<String>()
    var used = 0
    for (paragraph in paragraphs) {
        // The first paragraph goes in whatever its length, or a long lead-in would produce a
        // "summary" consisting entirely of the closing line.
        if (opening.isNotEmpty() && used + paragraph.length > target) break
        opening += paragraph
        used += paragraph.length
    }

    val closing = paragraphs.last().takeIf { it !in opening }
    return buildString {
        append(opening.joinToString(" "))
        if (closing != null) {
            append(" Skipping to the end of the answer. ")
            append(closing)
        }
        append(" ")
        append(ON_SCREEN)
    }
}

/**
 * The one sentence the listener needs that the summary itself cannot supply.
 *
 * Somebody who has just heard a shortened version has to be told that a fuller one exists, or the
 * summary is not a summary — it is the answer, quietly abridged, and they will act on it as though
 * they had the whole thing.
 */
private const val ON_SCREEN = "The full answer is on screen."

/**
 * Strips the wrapping a model reaches for even when told not to: fences, bullets, a heading.
 *
 * The markers are removed wherever they fall rather than only at the ends, because a speech engine
 * says them wherever they fall — `**In short**` is "asterisk asterisk in short asterisk asterisk",
 * which is four wasted seconds and the listener's first impression of the feature. Underscores are
 * left alone on purpose: they are far more often part of a name worth hearing (`run_command`) than
 * they are emphasis.
 */
internal fun tidy(raw: String): String = raw
    .replace(THINK_BLOCK, " ")
    .let { THINK_OPEN.split(it, limit = 2).first() }
    .lineSequence()
    .map { line ->
        line.trim()
            .removePrefix("- ")
            .removePrefix("* ")
            .removePrefix("+ ")
            .filterNot { it in MARKUP }
            .trim()
    }
    .filter { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
    .joinToString(" ")
    .replace(WHITESPACE, " ")
    .trim()

private val MARKUP = charArrayOf('*', '`', '#', '~')

private val PARAGRAPH_BREAK = Regex("\n\\s*\n|\n")
private val WHITESPACE = Regex("\\s+")
private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
private val THINK_OPEN = Regex("<think>")
