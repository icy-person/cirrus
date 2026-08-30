package dev.klaiber.cirrus.domain.files

import dev.klaiber.cirrus.domain.tools.shell.Scratchpad
import dev.klaiber.cirrus.domain.tools.shell.ShellWorkspace
import java.io.File

/**
 * What kind of thing a scratch file is, to the precision the viewer needs and no further.
 *
 * Six cases, because six is how many genuinely different ways there are to put a file on a screen.
 * Anything finer — distinguishing Kotlin from Swift, say — is a *language* for the highlighter and
 * rides along on [ScratchpadFile.language] rather than adding a case here.
 */
enum class FileKind {
    /** Rendered as prose, with headings and lists, the way the transcript renders an answer. */
    MARKDOWN,

    /** Rows and columns, shown as a table rather than as the commas it is stored in. */
    TABLE,

    /** Highlighted, with a language: source, JSON, HTML, YAML, a config file. */
    CODE,

    /** Monospace and wrapped: a log, a note, anything textual with no structure worth drawing. */
    TEXT,

    /** Decoded and shown. */
    IMAGE,

    /** Nothing useful to show. Its size and its name are the whole of what can be said. */
    BINARY,
    ;

    val isText: Boolean get() = this == MARKDOWN || this == TABLE || this == CODE || this == TEXT

    companion object {

        /**
         * Decides from the name, then checks the bytes.
         *
         * The extension comes first because it is what the author meant, and the bytes are
         * consulted only to catch the two cases a name gets wrong: a file with no extension at all
         * (common — the shell writes `out`, `totals`, `tmp`), and one whose extension promises text
         * that is not. A NUL byte in the first few kilobytes is the test, for the same reason `file`
         * uses it: no text encoding this app will meet puts one there, and every binary format does
         * within a few hundred bytes.
         *
         * [sample] is allowed to be empty, which is what an unreadable or zero-length file gives —
         * and an empty file is text, not binary. Showing "no preview available" for a file the model
         * created and has not written to yet would be a lie about the interesting thing, which is
         * that it is empty.
         */
        fun of(name: String, sample: ByteArray = ByteArray(0)): FileKind {
            val extension = name.substringAfterLast('.', "").lowercase()

            // `it.toInt() == 0` rather than a char literal: a raw NUL in a Kotlin source makes the
            // file binary to grep and breaks the parser.
            val looksBinary = sample.any { it.toInt() == 0 }

            return when {
                extension in IMAGE_EXTENSIONS -> IMAGE
                looksBinary -> BINARY
                extension in MARKDOWN_EXTENSIONS -> MARKDOWN
                extension in TABLE_EXTENSIONS -> TABLE
                extension in LANGUAGES -> CODE
                extension in TEXT_EXTENSIONS -> TEXT
                // No extension, and nothing in it says binary. The shell writes files like this
                // constantly, and calling them opaque would hide most of what it produces.
                extension.isEmpty() -> TEXT
                else -> TEXT
            }
        }

        /** The highlighter's name for this extension, or null when there is nothing to highlight. */
        fun languageOf(name: String): String? =
            LANGUAGES[name.substringAfterLast('.', "").lowercase()]

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown")
        private val TABLE_EXTENSIONS = setOf("csv", "tsv")
        private val TEXT_EXTENSIONS = setOf("txt", "log", "text", "out", "err", "diff", "patch")

        /**
         * Extension to highlighter language.
         *
         * Only what `SyntaxHighlighter` actually has a lexer for, plus the aliases people write.
         * Naming a language the highlighter does not know costs a pass over the file to produce
         * exactly the plain text it started with.
         */
        private val LANGUAGES = mapOf(
            "json" to "json",
            "html" to "html",
            "htm" to "html",
            "xml" to "xml",
            "svg" to "xml",
            "yaml" to "yaml",
            "yml" to "yaml",
            "toml" to "toml",
            "ini" to "ini",
            "conf" to "ini",
            "sh" to "bash",
            "bash" to "bash",
            "zsh" to "bash",
            "kt" to "kotlin",
            "kts" to "kotlin",
            "java" to "java",
            "py" to "python",
            "js" to "javascript",
            "mjs" to "javascript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "jsx" to "javascript",
            "css" to "css",
            "sql" to "sql",
            "rs" to "rust",
            "go" to "go",
            "rb" to "ruby",
            "php" to "php",
            "c" to "c",
            "h" to "c",
            "cpp" to "cpp",
            "swift" to "swift",
        )
    }
}

/** One file in a conversation's scratchpad, described well enough to list and to open. */
data class ScratchpadFile(
    val topic: String,
    /** Relative to the topic, so it reads as the name the model gave it. */
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val kind: FileKind,
    val language: String?,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** One job's files, with the totals the header needs. */
data class TopicListing(
    val name: String,
    val files: List<ScratchpadFile>,
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
    val modifiedAt: Long get() = files.maxOfOrNull { it.modifiedAt } ?: 0L
}

/** What the viewer got when it opened a file. */
sealed interface FilePreview {

    /**
     * Text, capped. [truncated] is separate from the text rather than appended to it, because the
     * viewer says so in its own voice — a sentence spliced onto the end of a CSV would become a row.
     */
    data class Readable(
        val text: String,
        val truncated: Boolean,
        val kind: FileKind,
        val language: String?,
    ) : FilePreview

    /** An image, left as a file so each platform can decode it its own way. */
    data class Picture(val file: File) : FilePreview

    /** Nothing worth drawing, and the reason, in a sentence the screen can show. */
    data class Opaque(val reason: String) : FilePreview
}

/**
 * Reading the scratchpad from the outside — the user's side rather than the model's.
 *
 * The shell tools have been able to list and read these files since the beginning; the person whose
 * phone they are on could not. That asymmetry is the whole reason this class exists. A model would
 * say "I saved the totals to expenses/totals.csv", which was true, and there was no screen in the
 * app where that file could be seen — so the only way to get at your own working files was to ask
 * the model to print them back at you, one `cat` at a time.
 *
 * It reads and it deletes, and it deliberately cannot write. Everything in here was made by a
 * command, and a file the user could edit from a viewer would be a file the next command's
 * assumptions no longer hold for.
 */
class ScratchpadBrowser(private val workspace: ShellWorkspace) {

    /** Everything this conversation has, newest job first, empty topics dropped. */
    fun topics(conversationId: String?): List<TopicListing> {
        val pad = workspace.scratchpad(conversationId)
        return pad.topics()
            .map { topic ->
                TopicListing(
                    name = topic.name,
                    files = pad.topicEntries(topic.name)
                        .filterNot { it.isDirectory }
                        .map { entry ->
                            ScratchpadFile(
                                topic = topic.name,
                                path = entry.path,
                                sizeBytes = entry.sizeBytes,
                                modifiedAt = entry.modifiedAt,
                                // Sniffed here rather than at open time so the list can show the
                                // right icon, and cheap because it reads a few hundred bytes.
                                kind = kindOf(pad, topic.name, entry.path, entry.sizeBytes),
                                language = FileKind.languageOf(entry.path),
                            )
                        }
                        .sortedBy { it.path },
                )
            }
            .filter { it.files.isNotEmpty() }
            .sortedByDescending { it.modifiedAt }
    }

    /** The file itself, for the download sink. Null when it has gone since the list was drawn. */
    fun file(conversationId: String?, topic: String, path: String): File? =
        resolve(workspace.scratchpad(conversationId), topic, path)

    /** Opens a file for the viewer. */
    fun preview(conversationId: String?, topic: String, path: String): FilePreview {
        val pad = workspace.scratchpad(conversationId)
        val file = resolve(pad, topic, path)
            ?: return FilePreview.Opaque("That file is not there any more.")

        val kind = kindOf(pad, topic, path, file.length())
        if (kind == FileKind.IMAGE) return FilePreview.Picture(file)
        if (kind == FileKind.BINARY) {
            return FilePreview.Opaque(
                "This is not a text file, so there is nothing to show. Download it to open it in " +
                    "something that understands it.",
            )
        }

        val text = runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(MAX_PREVIEW_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = stream.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read, Charsets.UTF_8)
            }
        }.getOrNull() ?: return FilePreview.Opaque("That file could not be read.")

        return FilePreview.Readable(
            text = text,
            truncated = file.length() > MAX_PREVIEW_BYTES,
            kind = kind,
            language = FileKind.languageOf(path),
        )
    }

    fun deleteFile(conversationId: String?, topic: String, path: String): Boolean =
        resolve(workspace.scratchpad(conversationId), topic, path)?.delete() ?: false

    fun deleteTopic(conversationId: String?, topic: String): Int =
        workspace.scratchpad(conversationId).clear(topic)

    fun deleteAll(conversationId: String?): Int =
        workspace.scratchpad(conversationId).clear()

    private fun kindOf(pad: Scratchpad, topic: String, path: String, size: Long): FileKind {
        if (size == 0L) return FileKind.of(path)
        val file = resolve(pad, topic, path) ?: return FileKind.of(path)
        val sample = runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(SNIFF_BYTES)
                val read = stream.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
        return FileKind.of(path, sample)
    }

    /**
     * A file inside a topic, or null if the path tries to be anywhere else.
     *
     * The topic and the path arrive from the UI rather than from a command, so they never pass
     * `CommandPolicy` — the same hole `Scratchpad.topicName` closes for the model's side, closed
     * again here for ours. Canonical paths rather than string checks, because `a/../../b` is the
     * one form a `contains("..")` test misses.
     */
    private fun resolve(pad: Scratchpad, topic: String, path: String): File? {
        val root = File(pad.path, Scratchpad.topicName(topic))
        val target = File(root, path)
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return null
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) return null
        return target.takeIf { it.isFile }
    }

    private companion object {
        /**
         * How much of a file the viewer will show.
         *
         * A cap rather than the whole file because this is rendered into a scrolling column of
         * annotated text, and a megabyte of it is a frame drop rather than a feature. The download
         * button is what "I want all of it" means here.
         */
        const val MAX_PREVIEW_BYTES = 256 * 1024

        /** Enough to find a NUL in anything that has one. */
        const val SNIFF_BYTES = 4_096
    }
}

/**
 * Splits a CSV or TSV into rows, well enough to draw a table.
 *
 * Not a full CSV implementation and not trying to be: no character encodings to guess, no dialect
 * detection, no streaming. What it does handle is the one thing a naive `split(",")` gets wrong
 * often enough to matter — a quoted field containing the delimiter, a newline, or a doubled quote —
 * because a table whose columns shift by one on the row with an address in it is worse than showing
 * the raw text.
 *
 * Rows are capped: a table is a thing you glance at, and beyond a few hundred rows the answer to
 * "what is in this file?" is a command, not a screen.
 */
fun parseDelimited(text: String, delimiter: Char, maxRows: Int = 500): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0

    fun endField() {
        row += field.toString()
        field.setLength(0)
    }

    fun endRow() {
        endField()
        // A trailing newline produces one empty field, which is not a row anybody wants drawn.
        if (row.size > 1 || row.firstOrNull()?.isNotEmpty() == true) rows += row.toList()
        row.clear()
    }

    while (index < text.length && rows.size < maxRows) {
        val char = text[index]
        when {
            quoted && char == '"' ->
                if (text.getOrNull(index + 1) == '"') {
                    // "" inside a quoted field is one literal quote, which is how CSV escapes it.
                    field.append('"')
                    index++
                } else {
                    quoted = false
                }

            quoted -> field.append(char)
            char == '"' && field.isEmpty() -> quoted = true
            char == delimiter -> endField()
            char == '\n' -> endRow()
            char == '\r' -> Unit
            else -> field.append(char)
        }
        index++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
}

/** `,` for CSV and anything unknown, a tab for TSV. */
fun delimiterFor(name: String): Char =
    if (name.substringAfterLast('.', "").equals("tsv", ignoreCase = true)) '\t' else ','
