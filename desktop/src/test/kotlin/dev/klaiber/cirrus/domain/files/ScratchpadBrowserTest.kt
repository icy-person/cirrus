package dev.klaiber.cirrus.domain.files

import dev.klaiber.cirrus.domain.tools.shell.ShellWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Reading the scratchpad from the user's side.
 *
 * Two things here are worth asserting and the rest is plumbing. What kind a file is decides how it
 * is drawn, and getting that wrong is visible immediately — a CSV shown as text is a wall of commas.
 * And a path arriving from the UI has never been through `CommandPolicy`, so the containment check
 * is the same class of rule the shell has, and deserves the same treatment.
 */
class ScratchpadBrowserTest {

    private lateinit var root: File
    private lateinit var workspace: ShellWorkspace
    private lateinit var browser: ScratchpadBrowser

    @Before
    fun setUp() {
        root = Files.createTempDirectory("cirrus-browser").toFile()
        workspace = ShellWorkspace(root)
        browser = ScratchpadBrowser(workspace)
    }

    private fun write(topic: String, name: String, text: String = "x"): File =
        File(workspace.scratchpad(CONVERSATION).topicDirectory(topic), name)
            .apply { parentFile?.mkdirs(); writeText(text) }

    // ---- What a file is ------------------------------------------------------------------------

    @Test
    fun `the extension decides, and the bytes get a veto`() {
        assertEquals(FileKind.MARKDOWN, FileKind.of("notes.md"))
        assertEquals(FileKind.TABLE, FileKind.of("totals.csv"))
        assertEquals(FileKind.TABLE, FileKind.of("totals.tsv"))
        assertEquals(FileKind.CODE, FileKind.of("data.json"))
        assertEquals(FileKind.CODE, FileKind.of("page.html"))
        assertEquals(FileKind.TEXT, FileKind.of("run.log"))
        assertEquals(FileKind.IMAGE, FileKind.of("chart.png"))

        // The shell writes files with no extension constantly; calling those opaque would hide
        // most of what it produces.
        assertEquals(FileKind.TEXT, FileKind.of("out"))

        // A NUL in the first few kilobytes is the one thing that overrides a textual name.
        val binary = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
        assertEquals(FileKind.BINARY, FileKind.of("archive.txt", binary))
        // …but never for an image, which is binary by definition and still has a rendering.
        assertEquals(FileKind.IMAGE, FileKind.of("chart.png", binary))
    }

    /** An empty file is empty, not unreadable, and saying otherwise hides the interesting part. */
    @Test
    fun `an empty file is still text`() {
        assertEquals(FileKind.TEXT, FileKind.of("out", ByteArray(0)))
    }

    @Test
    fun `a language is offered only when the highlighter has one`() {
        assertEquals("json", FileKind.languageOf("data.json"))
        assertEquals("kotlin", FileKind.languageOf("Main.kt"))
        assertEquals("html", FileKind.languageOf("page.htm"))
        assertNull(FileKind.languageOf("notes.txt"))
        assertNull(FileKind.languageOf("out"))
    }

    // ---- Listing -------------------------------------------------------------------------------

    @Test
    fun `topics come back with their files, sizes and kinds`() {
        write("expenses", "totals.csv", "a,b\n1,2")
        write("expenses", "notes.md", "# Heading")
        write("logs", "run.log", "line")

        val topics = browser.topics(CONVERSATION)

        assertEquals(setOf("expenses", "logs"), topics.map { it.name }.toSet())
        val expenses = topics.first { it.name == "expenses" }
        assertEquals(listOf("notes.md", "totals.csv"), expenses.files.map { it.path })
        assertEquals(FileKind.TABLE, expenses.files.first { it.path == "totals.csv" }.kind)
        assertEquals(FileKind.MARKDOWN, expenses.files.first { it.path == "notes.md" }.kind)
        assertEquals(7L, expenses.files.first { it.path == "totals.csv" }.sizeBytes)
    }

    /** Another conversation's files are not this conversation's business. */
    @Test
    fun `only this conversation's files are listed`() {
        write("expenses", "mine.csv")
        File(workspace.scratchpad("someone-else").topicDirectory("expenses"), "theirs.csv")
            .writeText("x")

        assertEquals(listOf("mine.csv"), browser.topics(CONVERSATION).single().files.map { it.path })
    }

    @Test
    fun `an empty topic is not listed at all`() {
        workspace.scratchpad(CONVERSATION).topicDirectory("started-nothing")

        assertTrue(browser.topics(CONVERSATION).isEmpty())
    }

    // ---- Opening -------------------------------------------------------------------------------

    @Test
    fun `reading a text file gives its text and its kind`() {
        write("expenses", "totals.csv", "a,b\n1,2")

        val preview = browser.preview(CONVERSATION, "expenses", "totals.csv")

        assertTrue(preview is FilePreview.Readable)
        preview as FilePreview.Readable
        assertEquals("a,b\n1,2", preview.text)
        assertEquals(FileKind.TABLE, preview.kind)
        assertFalse(preview.truncated)
    }

    @Test
    fun `a file that has gone is a sentence, not a crash`() {
        val preview = browser.preview(CONVERSATION, "expenses", "never-existed.txt")

        assertTrue(preview is FilePreview.Opaque)
    }

    /**
     * The path comes from the UI, so it has never been near `CommandPolicy`. Canonical paths rather
     * than a `contains("..")` test, because `a/../../b` is exactly the form that test misses.
     */
    @Test
    fun `a path cannot climb out of its topic`() {
        write("expenses", "mine.csv")
        val secret = File(root, "secret.txt").apply { writeText("nope") }

        assertNull(browser.file(CONVERSATION, "expenses", "../../secret.txt"))
        assertNull(browser.file(CONVERSATION, "expenses", "a/../../../secret.txt"))
        assertTrue(
            "the real file is still reachable",
            browser.file(CONVERSATION, "expenses", "mine.csv") != null,
        )
        assertTrue("and nothing was deleted on the way", secret.exists())
    }

    @Test
    fun `deleting works on a file, a topic and everything`() {
        write("expenses", "a.csv")
        write("expenses", "b.csv")
        write("logs", "run.log")

        assertTrue(browser.deleteFile(CONVERSATION, "expenses", "a.csv"))
        assertEquals(1, browser.topics(CONVERSATION).first { it.name == "expenses" }.files.size)

        assertEquals(1, browser.deleteTopic(CONVERSATION, "expenses"))
        assertEquals(listOf("logs"), browser.topics(CONVERSATION).map { it.name })

        assertEquals(1, browser.deleteAll(CONVERSATION))
        assertTrue(browser.topics(CONVERSATION).isEmpty())
    }

    // ---- Tables --------------------------------------------------------------------------------

    /**
     * A naive `split(",")` shifts every column on the row with an address in it, which is worse
     * than not drawing a table at all — the numbers line up under the wrong headings.
     */
    @Test
    fun `quoted fields survive being parsed`() {
        val rows = parseDelimited(
            "name,address,total\n\"Smith, J\",\"12 High St\nLondon\",42\n",
            delimiter = ',',
        )

        assertEquals(2, rows.size)
        assertEquals(listOf("name", "address", "total"), rows[0])
        assertEquals(listOf("Smith, J", "12 High St\nLondon", "42"), rows[1])
    }

    @Test
    fun `a doubled quote is one literal quote`() {
        // Escaped rather than a raw string: a raw string ends at its first `"""`, which is
        // exactly what a doubled quote at the end of a CSV field looks like.
        val rows = parseDelimited("a,\"say \"\"hi\"\"\",c", delimiter = ',')

        assertEquals(listOf("a", "say \"hi\"", "c"), rows.single())
    }

    @Test
    fun `a trailing newline does not become an empty row`() {
        assertEquals(2, parseDelimited("a,b\n1,2\n", delimiter = ',').size)
    }

    @Test
    fun `rows are capped so a long file cannot be drawn whole`() {
        val text = (1..900).joinToString("\n") { "$it,x" }

        assertEquals(500, parseDelimited(text, delimiter = ',', maxRows = 500).size)
    }

    @Test
    fun `tsv gets tabs and everything else gets commas`() {
        assertEquals('\t', delimiterFor("totals.tsv"))
        assertEquals(',', delimiterFor("totals.csv"))
        assertEquals(',', delimiterFor("out"))
    }

    private companion object {
        const val CONVERSATION = "conversation-one"
    }
}
