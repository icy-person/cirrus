package dev.klaiber.cirrus.domain.model

/**
 * A skill is a page of instructions for one kind of job.
 *
 * Not a tool and not a prompt. A tool is a function the model may call; a system prompt is what it
 * is for the whole conversation. A skill is neither — it is the paragraph somebody who has done
 * this particular job before would tell you at the start of it: which order to do things in, what
 * to check, what usually goes wrong. The reason to have them at all is that this knowledge is
 * *conditional*. It is worth a great deal while writing a changelog and worth nothing at all the
 * rest of the time, so it cannot live in the system prompt, where it would be paid for on every
 * turn of every conversation.
 *
 * That shape is the same one the wider ecosystem settled on — a `SKILL.md` with a name, a
 * description and a body — which is why these are fetched from the public registry at skills.sh
 * rather than written here. What Cirrus adds is a chooser: enabled skills are listed to the model
 * by name and description only, and the body arrives only when it calls `use_skill`. The
 * description is the advertisement, the body is the cost, and nothing pays for the body until the
 * model has decided the job is one of these.
 *
 * They are text, and text written by strangers. A skill can tell the model to do something, but it
 * cannot make it possible: every gate in `ToolRegistry` still applies, and a skill that assumes a
 * development machine is a skill that will not work here — which is what
 * `SkillToolSet.CIRRUS_CAVEAT` exists to say at the moment the instructions arrive.
 */
data class Skill(
    /** The registry's own identifier, `owner/repo/slug`. Stable, and unique across the registry. */
    val id: String,
    val name: String,
    /**
     * The one line the model reads when deciding whether this is the job.
     *
     * Written by the skill's author for exactly this purpose, so it is used verbatim rather than
     * summarised: a description rewritten by us is a description the author cannot fix.
     */
    val description: String,
    /** `owner/repo` on GitHub, shown so it is obvious whose instructions these are. */
    val source: String,
    /** The registry's install count, the only signal of trust it offers. */
    val installs: Int,
    /** The body of SKILL.md — everything below the frontmatter. */
    val instructions: String,
    /**
     * The names of the other files in the package, which Cirrus does not fetch.
     *
     * Recorded rather than downloaded, and listed to the model when it loads the skill, because a
     * skill whose SKILL.md says "see references/testing.md" would otherwise send it looking for a
     * file that does not exist here. Naming them is what turns that into a known limit.
     */
    val references: List<String> = emptyList(),
    /** Off keeps it installed but out of the brief, which is how you park one without losing it. */
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
) {
    val owner: String get() = source.substringBefore('/')

    /** One line for the standing brief: enough to choose by, small enough to send every turn. */
    fun brief(): String = "$name — ${description.take(BRIEF_DESCRIPTION_CHARS)}"

    companion object {
        /** Instructions past this are not a skill, they are a manual. Trimmed on install. */
        const val MAX_INSTRUCTION_CHARS = 20_000

        const val BRIEF_DESCRIPTION_CHARS = 160
    }
}

/** A search result: everything the registry's index knows, which is not much. */
data class SkillListing(
    val id: String,
    val name: String,
    val source: String,
    val installs: Int,
) {
    /**
     * True once this one is installed.
     *
     * Compared on [id] rather than on name: two owners publishing a `changelog` skill is the normal
     * case in a registry that is one namespace deep, and matching on name would show one of them as
     * installed because the other was.
     */
    fun isInstalled(installed: List<Skill>): Boolean = installed.any { it.id == id }
}

/**
 * The shelves the Explore page opens with.
 *
 * The registry has no "browse everything" endpoint and its search rejects a query under two
 * characters, so a library screen has a choice: open empty and wait to be typed into, or open with
 * something on it. These are that something — each one is an ordinary search, run through the same
 * call as anything typed, so there is no second code path and no curated list to go stale. What is
 * curated is only the *questions*, and they are chosen to be the jobs somebody would bring to a
 * chat client rather than the ones that happen to be popular in a registry built for coding agents.
 */
data class SkillTopic(val label: String, val query: String) {
    companion object {
        val Default: List<SkillTopic> = listOf(
            SkillTopic("Writing", "writing"),
            SkillTopic("Research", "research"),
            SkillTopic("Documents", "documents"),
            SkillTopic("Data", "data analysis"),
            SkillTopic("Planning", "planning"),
            SkillTopic("Code review", "code review"),
            SkillTopic("Testing", "testing"),
            SkillTopic("Design", "design"),
            SkillTopic("Email", "email"),
            SkillTopic("Meetings", "meeting notes"),
        )
    }
}

/**
 * A parsed `SKILL.md`: the frontmatter that matters, and the body.
 *
 * The format is a YAML block between `---` fences followed by markdown. Only `name` and
 * `description` are required by the registry and only those two are read here — `allowed-tools`,
 * `license` and the rest describe an agent that is not this one.
 */
data class SkillDocument(
    val name: String,
    val description: String,
    val body: String,
)

/**
 * Reads a `SKILL.md`, or null when it is not one.
 *
 * Deliberately not a YAML parser. The frontmatter this has to read is a handful of `key: value`
 * lines written by a tool, and the two keys that matter are both plain strings; a real parser would
 * be a dependency and several hundred lines to gain nothing. What it does have to survive is the
 * variety in the wild — CRLF line endings, quoted and unquoted values, a `description:` that runs
 * to a folded block, and files with no frontmatter at all, which are refused rather than guessed
 * at because a skill with no description cannot be chosen between.
 */
fun parseSkillDocument(markdown: String): SkillDocument? {
    val text = markdown.replace("\r\n", "\n").trimStart(BYTE_ORDER_MARK, ' ', '\n')
    if (!text.startsWith("---")) return null

    val end = text.indexOf("\n---", startIndex = 3)
    if (end < 0) return null

    val frontmatter = text.substring(text.indexOf('\n') + 1, end)
    val body = text.substring(end + 4).trimStart('-', '\n').trim()

    val fields = mutableMapOf<String, String>()
    var currentKey: String? = null
    val folded = StringBuilder()

    fun commit() {
        val key = currentKey ?: return
        if (folded.isNotBlank()) fields[key] = folded.toString().trim()
        currentKey = null
        folded.setLength(0)
    }

    frontmatter.lines().forEach { raw ->
        val line = raw.trimEnd()
        val separator = line.indexOf(':')
        // A continuation line: indented, and not itself a `key: value`. This is how a long
        // description survives — several skills in the registry fold theirs over three lines, and
        // reading only the first gives a sentence that stops mid-clause.
        val isContinuation = currentKey != null &&
            (raw.startsWith(" ") || raw.startsWith("\t")) &&
            (separator < 0 || line.substringBefore(':').isBlank())

        when {
            isContinuation -> folded.append(' ').append(line.trim())
            separator <= 0 -> commit()
            else -> {
                commit()
                val key = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                if (value.isEmpty() || value == ">" || value == "|") {
                    currentKey = key
                } else {
                    fields[key] = value.unquoted()
                }
            }
        }
    }
    commit()

    val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
    val description = fields["description"]?.takeIf { it.isNotBlank() } ?: return null
    return SkillDocument(
        name = name.take(80),
        description = description.unquoted().take(400),
        body = body,
    )
}

/**
 * A byte-order mark, as an escape rather than as itself.
 *
 * Files exported from Windows editors start with one, and it would otherwise sit in front of the
 * opening `---` and stop the frontmatter being recognised at all. Written `\uFEFF` because a
 * literal one in a Kotlin source is invisible, makes the file binary to `grep`, and is a lint
 * error — the same rule that keeps a raw NUL out of a char literal here.
 */
private const val BYTE_ORDER_MARK = '\uFEFF'

private fun String.unquoted(): String {
    val trimmed = trim()
    return when {
        trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
            trimmed.substring(1, trimmed.length - 1)

        trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'') ->
            trimmed.substring(1, trimmed.length - 1)

        else -> trimmed
    }
}
