package dev.klaiber.cirrus.domain.tools.shell

import dev.klaiber.cirrus.domain.files.DownloadSink
import dev.klaiber.cirrus.domain.files.ScratchpadBrowser
import dev.klaiber.cirrus.domain.tools.CirrusTool
import dev.klaiber.cirrus.domain.tools.TurnContext
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.github.string
import dev.klaiber.cirrus.domain.tools.github.stringParam
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hands a file the model made to the person it made it for.
 *
 * The gap this closes was invisible from the inside and obvious from the outside. `run_command`
 * could write a file, `download_file` could fetch one, and both of them landed it in a scratch
 * directory inside the app's private storage — which the model could read and the user could not
 * reach. So a model that had just sorted a spreadsheet or assembled a report would say "I've saved
 * it to expenses/totals.csv" and there was, genuinely, nothing the user could do with that
 * sentence. The file existed, the offer was sincere, and it could not be accepted.
 *
 * `download_file` grew a copy-to-Downloads step for the same reason, but only for files it had
 * fetched. This is the other half: anything in the workspace, however it got there, including
 * everything the shell wrote itself.
 *
 * **It is a write**, and it took a second look to see that. The first version was not, on the
 * reasoning that a file in Downloads is the thing the user asked for and deleting it is something
 * they can do without us. That argues from intent, and the gate does not: the test is that the
 * effect outlives the turn, happens outside Cirrus, and cannot be reversed by calling the same tool
 * again. Saving here meets all three. The file is in shared storage, so on Android it survives
 * Cirrus being uninstalled; and calling this twice does not undo anything, it produces a second
 * copy called `totals (1).csv`. Reasoning from what the user probably wanted is exactly how a gate
 * ends up with a hole in it.
 *
 * It is *not* external, and that is a separate axis. Nothing leaves the device — this copies a file
 * from one directory to another — so the conversation's tools switch has no business governing it.
 * The two switches ask different questions: "may this reach the network?" and "may this do
 * something I cannot undo from inside the app?".
 *
 * The cost is real and worth stating: with write actions off, which is the default, a model asked
 * for a file will be refused. That refusal names the switch and where to find it, so what the user
 * sees is "turn on Settings → Tools → Allow write actions and I can put that in your Downloads"
 * rather than a failure — which is the whole reason `explainRefusal` exists.
 */
class SaveFileTool(
    private val browser: ScratchpadBrowser,
    private val downloads: DownloadSink,
) : CirrusTool {

    override val name: String = "save_file"

    /** See the note above: it puts a file outside Cirrus that calling this again would not remove. */
    override val writes: Boolean = true

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Give the user a file from your workspace, by copying it to their Downloads " +
            "folder where they can open it. Use this whenever you have made something they " +
            "actually want — a spreadsheet you sorted, a report you assembled, a page you " +
            "downloaded, anything you would otherwise describe as \"saved\".\n\n" +
            "IT IS THE ONLY WAY THEY CAN GET A FILE. Your workspace is inside Cirrus's private " +
            "storage; the user cannot browse to it, and telling them a path in it is telling them " +
            "nothing they can act on. If you wrote a file and the answer involves them having it, " +
            "call this and then tell them the name it was saved under — the reply says what that " +
            "is, and it may differ from the name you asked for if a file of that name was already " +
            "there.\n\n" +
            "Do NOT call it for your own working files — an intermediate sort, a page you are " +
            "about to count the lines of. Those belong in the workspace and get cleaned up. This " +
            "is for the thing the user asked for.\n\n" +
            "IT NEEDS WRITE ACTIONS, which are off by default: it puts a file on the user's " +
            "device that nothing here can take back. If it is refused, say so plainly — tell them " +
            "which switch turns it on, and offer the contents in your answer instead so they have " +
            "the work either way.",
        required = listOf("path"),
    ) {
        stringParam(
            "path",
            "The file, named as it is inside the topic — \"totals.csv\", not a full path.",
        )
        stringParam(
            "topic",
            "The job the file belongs to, exactly as passed to run_command. Defaults to " +
                "\"${Scratchpad.DEFAULT_TOPIC}\".",
        )
        stringParam(
            "save_as",
            "What to call it in the user's Downloads. Defaults to its own name. Worth setting " +
                "when the working name was for you rather than for them — \"out2.txt\" tells them " +
                "nothing, \"march-expenses.csv\" does.",
        )
    }

    override suspend fun execute(arguments: JsonObject): String =
        execute(arguments, TurnContext.None)

    override suspend fun execute(arguments: JsonObject, turn: TurnContext): String = shellTool {
        val path = arguments.string("path")
            ?: return@shellTool errorJson("missing required argument: path")
        val topic = Scratchpad.topicName(arguments.string("topic"))

        val source = browser.file(turn.conversationId, topic, path)
            ?: return@shellTool buildJsonObject {
                put("error", "There is no file called \"$path\" in the \"$topic\" topic.")
                // The listing rather than a bare refusal: the usual cause is the wrong topic, and
                // a model that can see what is actually there fixes it without a round trip.
                val available = browser.topics(turn.conversationId)
                if (available.isEmpty()) {
                    put("note", "This conversation has no scratch files at all yet.")
                } else {
                    putJsonArray("what_is_there") {
                        available.forEach { listing ->
                            listing.files.forEach { add(JsonPrimitive("${listing.name}/${it.path}")) }
                        }
                    }
                }
            }.toString()

        val name = arguments.string("save_as")?.let(::safeSaveName) ?: source.name
        val saved = downloads.save(source, name, mimeTypeOf(name))
            ?: return@shellTool errorJson(
                "\"$path\" could not be copied to the user's Downloads. Tell them it did not " +
                    "save rather than saying it did.",
            )

        buildJsonObject {
            put("saved_to", saved.location)
            put("saved_as", saved.name)
            put("bytes", source.length())
            put(
                "tell_the_user",
                "It is in their Downloads as \"${saved.name}\". Say that, and say nothing about " +
                    "the workspace path — it means nothing to them.",
            )
        }.toString()
    }
}

/**
 * A name safe to hand to the platform's Downloads.
 *
 * Same rule as the one `download_file` applies to a filename, and for the same reason: this one
 * arrives as a tool argument, so it has never been through `CommandPolicy`, and a name with a
 * separator in it would be a path rather than a name.
 */
internal fun safeSaveName(raw: String): String {
    val slug = raw.trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filter { it.isLetterOrDigit() || it in "-_. " }
        .replace(' ', '-')
        .trim('.', '-')
        .take(64)

    return slug.ifEmpty { "file" }
}

/**
 * A MIME type from the extension.
 *
 * Only the ones worth being right about: a CSV typed `text/csv` opens in a spreadsheet, and one
 * typed `application/octet-stream` opens a chooser with nothing sensible in it. Everything unknown
 * gets the generic type rather than a guess, because a wrong type is worse than an absent one — it
 * hands the file to an app that cannot read it.
 */
internal fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "log", "out", "err", "text" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "csv" -> "text/csv"
    "tsv" -> "text/tab-separated-values"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "xml", "svg" -> "text/xml"
    "yaml", "yml" -> "application/yaml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
