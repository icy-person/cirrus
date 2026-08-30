package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.domain.tools.github.clip
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.files.DownloadSink
import dev.klaiber.cirrus.domain.tools.github.booleanParam
import dev.klaiber.cirrus.domain.tools.github.stringParam
import dev.klaiber.cirrus.domain.tools.shell.Scratchpad
import dev.klaiber.cirrus.domain.tools.shell.ShellWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * Fetches a file as it actually is, and puts it where a command can read it.
 *
 * `web_fetch` already exists and answers a different question. It flattens a page to prose, which
 * is exactly right for "what does this article say" and exactly wrong for everything else: the
 * markup is gone, so is the CSV's comma structure, so is the JSON. A model asked to look at a
 * page's own HTML, count the rows in a data file or check what an API returns has, until now, had
 * one tool that would hand it a summary of the thing instead of the thing.
 *
 * So this saves the bytes. It lands in the shell workspace, in the topic the model is already
 * working in, which is what makes it worth having rather than a second way to print a page into the
 * context window: `grep`, `wc`, `head` and `sed` are all right there, and the file can be looked at
 * three times without being fetched three times.
 *
 * It saves in two places, and that is the correction to the version that shipped first. That one
 * saved only into the shell workspace, which is right for the model — the workspace is the only
 * place `run_command` can read — and useless for the person who asked, because the workspace is
 * inside the app's private storage. "Downloaded to expenses/report.csv" was a true sentence about a
 * file they could not open, which is worse than a plain failure, since a failure would at least
 * have been actionable. The working copy now goes to the scratchpad, a second copy goes to the
 * user's own Downloads, and the reply names both.
 *
 * Not a write, by the definition the gate uses. Nothing it does is outside Cirrus in the sense that
 * matters: a file in Downloads is one the user asked for, sitting where they asked for it, and
 * removing it is something they can do without us. It reaches the network, so the conversation's
 * tools switch governs it.
 *
 * The size cap is the interesting constraint, and it is deliberately the *topic's* cap rather than
 * a number of its own. A download is the easiest way there is to fill a disk, and a model that has
 * been told a topic holds a few megabytes should not discover a different limit here.
 */
class DownloadFileTool(
    private val client: OkHttpClient,
    private val workspace: ShellWorkspace,
    private val downloads: DownloadSink,
) : CirrusTool {

    override val name: String = "download_file"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Download a file by URL and save it into the shell workspace, then read it " +
            "with run_command. Use this when you need a file as it is rather than as prose: the " +
            "HTML of a page, a CSV or TSV, a JSON API response, a log, a plain-text document. " +
            "web_fetch is the right tool for \"what does this page say\" — it flattens a page to " +
            "readable text — and this one is right for everything where the markup, the columns " +
            "or the exact bytes are the point.\n\n" +
            "WHAT COMES BACK. The saved path, the content type, the size, and the first part of " +
            "the file if it is text. Then work on it with run_command in the SAME topic: " +
            "`grep -c \"</tr>\" page.html`, `head -20 data.csv`, `wc -l log.txt`, " +
            "`cut -d, -f2 data.csv | sort | uniq -c`. The file is a normal file in that topic " +
            "until the topic is cleaned.\n\n" +
            "THE USER GETS A COPY TOO. Unless you pass save=false, the file is also placed in " +
            "their Downloads folder, where they can open it like any other download. Tell them " +
            "the name it was saved under — the reply says what it is, and it may differ from the " +
            "one you asked for if a file of that name was already there. The workspace copy is " +
            "yours to work on and gets swept; theirs does not.\n\n" +
            "LIMITS. Only http and https. Files over " +
            "${Scratchpad.MAX_TOPIC_BYTES / 1024}KB are truncated, because a topic holds no " +
            "more than that in total — so this is for documents and data, not for archives, " +
            "videos or installers, none of which anything here could open in any case.",
        required = listOf("url"),
    ) {
        stringParam("url", "Absolute http or https URL of the file to download.")
        stringParam(
            "filename",
            "What to call it in the workspace. Defaults to the name in the URL, or " +
                "\"$FALLBACK_NAME\" when it has none. Directories are not allowed.",
        )
        stringParam(
            "topic",
            "The job these files belong to, exactly as passed to run_command, so the command that " +
                "reads this file can find it. Defaults to \"${Scratchpad.DEFAULT_TOPIC}\".",
        )
        booleanParam(
            "save",
            "Whether to also put a copy in the user's Downloads. True by default, which is what " +
                "you want whenever they asked for the file at all. Pass false only when the " +
                "download is a step in your own work that they never asked for — a page you are " +
                "about to count the rows of, say — so their Downloads folder does not fill with " +
                "your working files.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String =
        execute(arguments, TurnContext.None)

    override suspend fun execute(arguments: JsonObject, turn: TurnContext): String = try {
        download(arguments, turn)
    } catch (cancellation: CancellationException) {
        // The user pressed stop. Swallowing this would report a half-written file as a result and
        // let the turn carry on as though nothing had happened.
        throw cancellation
    } catch (error: Throwable) {
        errorJson(error.message ?: "The file could not be downloaded.")
    }

    private suspend fun download(arguments: JsonObject, turn: TurnContext): String {
        val raw = arguments.string("url")
            ?: return errorJson("missing required argument: url")

        val url = normalizeUrl(raw)
            ?: return errorJson(
                "\"$raw\" is not an http or https URL. Only those two schemes are fetched — a " +
                    "file:// path would name somewhere on the device, which is not reachable " +
                    "from here.",
            )

        // This conversation's scratchpad: the working copy has to land where this thread's
        // run_command will look for it.
        val pad = workspace.scratchpad(turn.conversationId)
        val topic = Scratchpad.topicName(arguments.string("topic"))
        pad.budgetProblem(topic)?.let { problem ->
            return buildJsonObject {
                put("refused", true)
                put("url", url)
                put("topic", topic)
                put("reason", problem)
            }.toString()
        }

        val directory = pad.topicDirectory(topic)
        val filename = safeFileName(arguments.string("filename") ?: nameFrom(url))
        val destination = File(directory, filename)

        val outcome = withContext(Dispatchers.IO) { fetch(url, destination) }
        return when (outcome) {
            is Outcome.Failed -> errorJson(outcome.message)
            is Outcome.Saved -> {
                // Made after the download rather than instead of it: the scratchpad copy is what
                // the shell can read, and this one is what the person who asked can open. A
                // failure here is reported rather than raised — the file exists either way, and
                // what matters is which of the two places it actually reached.
                val wanted = arguments.boolean("save") ?: true
                val kept = if (wanted) {
                    downloads.save(destination, filename, outcome.contentType)
                } else {
                    null
                }

                buildJsonObject {
                put("url", url)
                if (outcome.finalUrl != url) put("redirected_to", outcome.finalUrl)
                put("topic", topic)
                put("path", filename)
                put("content_type", outcome.contentType)
                put("bytes", outcome.bytes)
                when {
                    kept != null -> {
                        put("saved_to", kept.location)
                        put(
                            "saved_as", kept.name)
                        put(
                            "tell_the_user",
                            "The file is in their Downloads as \"${kept.name}\". Say so — the " +
                                "workspace copy is yours and gets swept, theirs does not.",
                        )
                    }

                    wanted -> put(
                        "not_saved",
                        "The copy for the user could not be written, so only your working copy " +
                            "exists. Say that rather than telling them it was downloaded.",
                    )
                }
                if (outcome.truncated) {
                    put(
                        "truncated",
                        "The file was larger than the ${Scratchpad.MAX_TOPIC_BYTES / 1024}KB " +
                            "a topic holds, so only the first part was saved. What is on disk is " +
                            "a prefix of the file, not the whole of it — say so if it matters.",
                    )
                }
                if (outcome.preview != null) {
                    put("preview", outcome.preview.clip(PREVIEW_CHARS))
                } else {
                    // A model handed a path and nothing else assumes the download failed and
                    // fetches it again. Saying "it is binary" is what stops the second attempt.
                    put(
                        "note",
                        "This does not look like text, so there is no preview. Nothing here can " +
                            "open a binary file — if the user needs it, give them the URL.",
                    )
                }
                put(
                    "next",
                    "Read it with run_command in topic \"$topic\", for example: head -40 $filename",
                )
                }.toString()
            }
        }
    }

    /**
     * Copies at most one topic's worth of the body, and reports having stopped.
     *
     * Read in bounded chunks rather than through `body.bytes()`, which would hold the whole file in
     * memory before anything could decide it was too big — from a URL a model chose, that is the
     * one failure mode worth writing extra code to avoid. `Content-Length` is not
     * trusted for the same reason: it is a claim by the server, and a chunked response has none.
     */
    private fun fetch(url: String, destination: File): Outcome {
        val request = Request.Builder().url(url).get().build()

        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            return Outcome.Failed("Could not reach $url: ${error.message}")
        }

        response.use { result ->
            if (!result.isSuccessful) {
                return Outcome.Failed(
                    "$url returned HTTP ${result.code}. " +
                        when (result.code) {
                            401, 403 -> "It needs credentials this app does not have."
                            404 -> "Check the URL, or search for the page first."
                            429 -> "The host is rate limiting; do not retry immediately."
                            else -> "Nothing was saved."
                        },
                )
            }

            val body = result.body ?: return Outcome.Failed("$url returned an empty response.")
            val limit = Scratchpad.MAX_TOPIC_BYTES
            var written = 0L
            var truncated = false

            body.byteStream().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val room = limit - written
                        if (read > room) {
                            output.write(buffer, 0, room.toInt())
                            written = limit
                            truncated = true
                            break
                        }
                        output.write(buffer, 0, read)
                        written += read
                    }
                }
            }

            val contentType = result.header("Content-Type").orEmpty().substringBefore(';').trim()
            return Outcome.Saved(
                finalUrl = result.request.url.toString(),
                contentType = contentType.ifBlank { "unknown" },
                bytes = written,
                truncated = truncated,
                preview = destination.textPreview(contentType),
            )
        }
    }

    private sealed interface Outcome {
        data class Saved(
            val finalUrl: String,
            val contentType: String,
            val bytes: Long,
            val truncated: Boolean,
            val preview: String?,
        ) : Outcome

        data class Failed(val message: String) : Outcome
    }

    private companion object {
        const val PREVIEW_CHARS = 2_000
        const val BUFFER_BYTES = 16 * 1024
        const val FALLBACK_NAME = "download"
    }
}

/**
 * The head of the file, when it is text, and null when it is not.
 *
 * The content type is a hint rather than the answer: servers label CSV as `application/octet-stream`
 * and JSON as `text/plain` often enough that trusting it would drop the preview on exactly the
 * files this tool exists for. So the bytes decide — a NUL byte in the first few kilobytes means
 * binary, and nothing else does.
 */
private fun File.textPreview(contentType: String, sample: Int = 4_096): String? {
    if (!exists() || length() == 0L) return null
    if (contentType.startsWith("image/") ||
        contentType.startsWith("video/") ||
        contentType.startsWith("audio/")
    ) {
        return null
    }

    val bytes = inputStream().use { stream ->
        val buffer = ByteArray(sample)
        val read = stream.read(buffer)
        if (read <= 0) return null
        buffer.copyOf(read)
    }
    // `it.toInt() == 0` rather than a char literal: a raw NUL in a source file makes it binary to
    // grep and breaks the parser.
    if (bytes.any { it.toInt() == 0 }) return null
    return String(bytes, Charsets.UTF_8)
}

/** Accepts what a model actually sends: a bare host, extra spaces, an occasional `www.` and no scheme. */
internal fun normalizeUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val candidate = when {
        trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
        // A scheme that is neither is a refusal, not something to fix: `file://` and `content://`
        // name places on the device, and quietly rewriting one to https would fetch a stranger.
        trimmed.contains("://") -> return null
        else -> "https://$trimmed"
    }

    return runCatching {
        val uri = URI(candidate)
        if (uri.host.isNullOrBlank()) null else uri.toString()
    }.getOrNull()
}

/**
 * A file name that is a file name, and nothing else.
 *
 * The name arrives as a tool argument, so it never passes `CommandPolicy` — which is the same hole
 * `ShellWorkspace.topicName` exists to close, and it is closed the same way: normalise rather than
 * reject, since a model that asked for `data/report.csv` has said perfectly clearly what it wants
 * the file called.
 */
internal fun safeFileName(raw: String): String {
    val slug = raw.trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filter { it.isLetterOrDigit() || it in "-_. " }
        .replace(' ', '-')
        .trim('.', '-')
        .take(64)

    return slug.ifEmpty { "download" }
}

/** The last path segment of the URL, which is what the file is called nearly everywhere. */
private fun nameFrom(url: String): String {
    val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
    val last = path.substringAfterLast('/')
    if (last.isBlank()) return "download.html"
    // A segment with no extension is a route, not a file; `index.html` is the honest name for it.
    return if ('.' in last) last else "$last.html"
}

/**
 * A boolean argument, tolerant of how models actually send one.
 *
 * `true`, `"true"`, `"yes"`, `1` all mean true; the mirror set means false. Anything else is null,
 * which the caller reads as "not specified" rather than as false — the difference matters here,
 * since the default is on and a misparsed value would silently stop saving the user's file.
 */
internal fun JsonObject.boolean(key: String): Boolean? {
    val raw = (this[key] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return null
    return when (raw) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
}
