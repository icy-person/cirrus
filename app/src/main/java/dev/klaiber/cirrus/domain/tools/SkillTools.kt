package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.repository.SkillRepository
import dev.klaiber.cirrus.domain.model.Skill
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.tools.github.stringParam
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The skill chooser, as the model sees it.
 *
 * Two tools rather than one, and the split is the whole design. Skills are useful in proportion to
 * how many are installed, and their bodies are pages long — putting the instructions of every
 * installed skill into the system prompt would cost thousands of tokens on every turn of every
 * conversation, nearly all of it about jobs the user is not doing. So the *names and descriptions*
 * go into the standing brief, which is cheap and is exactly what choosing needs, and the body
 * arrives only when the model has chosen.
 *
 * [ListSkillsTool] exists alongside that brief for the case the brief cannot serve: an install with
 * more skills than the brief lists, and a model that wants to search rather than scan. [UseSkillTool]
 * is the one that matters, and it is deliberately shaped as a tool call rather than as something
 * Cirrus decides — the model knows what the user asked for and a keyword match does not, and a
 * skill loaded for the wrong job is worse than no skill at all because it is followed anyway.
 */
@Singleton
class ListSkillsTool @Inject constructor(
    private val skills: SkillRepository,
) : CirrusTool {

    override val name: String = "list_skills"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "List the skills installed here — prepared instructions for particular " +
            "kinds of job, written by the people who do them. The names and one-line descriptions " +
            "of the enabled ones are already in your instructions, so call this only when you " +
            "want to search a longer list or check what is available before promising something. " +
            "This returns descriptions, not instructions: call use_skill to actually read one.",
    ) {
        stringParam(
            "query",
            "Words to match against names and descriptions. Omit to list everything enabled.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = skillTool {
        val query = arguments.string("query")?.lowercase()?.split(' ')?.filter { it.isNotBlank() }
        val available = skills.active()
        val matches = if (query.isNullOrEmpty()) {
            available
        } else {
            available.filter { skill ->
                val haystack = "${skill.name} ${skill.description}".lowercase()
                query.any { it in haystack }
            }
        }

        buildJsonObject {
            put("installed_count", available.size)
            putJsonArray("skills") {
                matches.take(MAX_LISTED).forEach { skill ->
                    add(
                        buildJsonObject {
                            put("name", skill.name)
                            put("description", skill.description)
                            put("from", skill.source)
                        },
                    )
                }
            }
            if (matches.isEmpty()) {
                put(
                    "note",
                    if (available.isEmpty()) {
                        "No skills are installed. The user can add some from Settings → Skills, " +
                            "where there is a library to browse. Answer from your own knowledge " +
                            "in the meantime — do not treat this as a reason you cannot help."
                    } else {
                        "Nothing matched. There are ${available.size} installed; call this with " +
                            "no query to see them all."
                    },
                )
            }
        }.toString()
    }

    private companion object {
        const val MAX_LISTED = 40
    }
}

/**
 * Hands over one skill's instructions, with the one caveat they were not written with.
 *
 * The caveat is not decoration. These are instructions written for coding agents on development
 * machines, which is what the registry is mostly full of, and a skill that opens "run
 * `npm install`" will otherwise have the model attempting exactly the thing every other guardrail
 * in this app exists to head off. Saying so *here* — at the moment the instructions arrive, in the
 * same tool result — is the only placement that works, because a rule in the system prompt is read
 * before the skill and forgotten by the time it contradicts one.
 */
@Singleton
class UseSkillTool @Inject constructor(
    private val skills: SkillRepository,
) : CirrusTool {

    override val name: String = "use_skill"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Read the full instructions for one installed skill, then follow them for " +
            "the rest of this task. Call it as the FIRST step of anything a skill covers — the " +
            "descriptions you have are advertisements, and the instructions are where the method " +
            "actually is, so starting without them means doing the job twice. One skill at a " +
            "time: they were each written as a complete approach and interleaving two produces " +
            "neither.",
        required = listOf("name"),
    ) {
        stringParam(
            "name",
            "The skill's name, exactly as listed. A close match is accepted.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String = skillTool {
        val requested = arguments.string("name")
            ?: return@skillTool errorJson("missing required argument: name")

        val available = skills.active()
        val skill = available.match(requested)
            ?: return@skillTool buildJsonObject {
                put("error", "No installed skill is called \"$requested\".")
                putJsonArray("available") {
                    available.take(MAX_SUGGESTED).forEach { add(JsonPrimitive(it.name)) }
                }
                put(
                    "note",
                    "Carry on without it rather than telling the user a skill is missing — they " +
                        "did not ask for one.",
                )
            }.toString()

        buildJsonObject {
            put("name", skill.name)
            put("from", skill.source)
            put("instructions", skill.instructions)
            if (skill.references.isNotEmpty()) {
                // Said out loud because the instructions will refer to these by name, and a model
                // that goes looking for `references/testing.md` finds a shell that cannot reach it
                // and spends a turn concluding something is broken.
                putJsonArray("files_not_available") {
                    skill.references.forEach { add(JsonPrimitive(it)) }
                }
                put(
                    "files_note",
                    "This skill ships those extra files and Cirrus does not download them. Where " +
                        "the instructions send you to one, use your own knowledge of the subject " +
                        "instead and say which part you are filling in.",
                )
            }
            put("environment", CIRRUS_CAVEAT)
        }.toString()
    }

    /**
     * Exact first, then case-insensitive, then a contains match.
     *
     * Models paraphrase a name they read three messages ago — "changelog" for "writing-changelogs"
     * — and answering that with "no such skill" spends a round trip on spelling. The exact tiers
     * come first so a fuzzy match can never win over a real one.
     */
    private fun List<Skill>.match(requested: String): Skill? {
        val wanted = requested.trim()
        return firstOrNull { it.name == wanted }
            ?: firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: firstOrNull { it.id.equals(wanted, ignoreCase = true) }
            ?: firstOrNull { it.name.contains(wanted, ignoreCase = true) }
            ?: firstOrNull { wanted.contains(it.name, ignoreCase = true) }
    }

    companion object {
        /**
         * What the skill's author could not have known about where their instructions ended up.
         *
         * Most of the registry is written for coding agents with a checkout, a terminal and a
         * package manager. Cirrus is a chat client: the shell is a scratch pad on a phone, there is
         * no project, and the tools are the ones in this app. Without this, a skill's first
         * instruction routinely sends the model at `npm` — and it is worth the tokens precisely
         * because it arrives attached to the instructions it is correcting.
         */
        const val CIRRUS_CAVEAT =
            "These instructions were written for a coding agent with a terminal and a checkout. " +
                "You are in Cirrus, a chat client. Follow the skill's method, judgement and " +
                "structure, and ignore anything that assumes otherwise: there is no repository, " +
                "no project directory and no package manager here, run_command is a small scratch " +
                "pad with no compiler or runtime, and the only tools you have are the ones already " +
                "offered to you. Where the skill says to create files, put the contents in your " +
                "answer instead. Never tell the user a skill failed — adapt it, and say which " +
                "parts did not apply."

        const val MAX_SUGGESTED = 12
    }
}

/**
 * The pair, plus the line about them that goes into the system prompt.
 *
 * The brief lives here rather than in `ToolRegistry` because it is the half of the chooser the
 * tools cannot supply: a tool description is read when the model is deciding whether to call *that
 * tool*, and "there is a skill for this" is something it has to know before it has thought of
 * calling anything. Keeping the two together also keeps them honest — the brief lists exactly the
 * skills `list_skills` would return, because both read [SkillRepository.active].
 */
class SkillToolSet(
    private val repository: SkillRepository,
    val list: CirrusTool,
    val use: CirrusTool,
) {
    val all: List<CirrusTool> = listOf(list, use)

    /**
     * The names and descriptions of the installed skills, or null when there are none.
     *
     * Null rather than "no skills are installed", because a sentence about an empty list is a
     * sentence paid for on every turn to say nothing. Capped, because somebody with forty installed
     * would otherwise spend a paragraph of every request on a catalogue — past the cap the brief
     * says how many are missing and points at `list_skills`, which is the whole reason that tool
     * exists alongside this.
     */
    fun brief(): String? {
        val available = repository.active()
        if (available.isEmpty()) return null

        val listed = available.take(MAX_BRIEFED)
        return buildString {
            append("Skills are prepared instructions for particular kinds of job, written by ")
            append("people who do them. Installed here:\n")
            listed.forEach { append("- ").append(it.brief()).append('\n') }
            if (available.size > listed.size) {
                append("- …and ${available.size - listed.size} more; call list_skills to search ")
                append("them.\n")
            }
            append("When a request is one of these jobs, call use_skill with the name FIRST and ")
            append("follow what it says — the lines above are advertisements, and the method is ")
            append("in the skill. When it is not, ignore them entirely; a skill applied to the ")
            append("wrong job is worse than none, because it gets followed anyway.")
        }
    }

    private companion object {
        const val MAX_BRIEFED = 12
    }
}

/**
 * Never throws. A tool call happens mid-turn, and an exception here ends the answer with a stack
 * trace where a JSON error is something the model can read and recover from. Cancellation is
 * rethrown: swallowing it would turn "the user pressed stop" into a tool result.
 */
private inline fun skillTool(body: () -> String): String = try {
    body()
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    errorJson(error.message ?: "That skill could not be read.")
}
