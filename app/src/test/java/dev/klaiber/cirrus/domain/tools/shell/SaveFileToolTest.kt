package dev.klaiber.cirrus.domain.tools.shell

import dev.klaiber.cirrus.domain.files.DownloadSink
import dev.klaiber.cirrus.domain.files.SavedDownload
import dev.klaiber.cirrus.domain.files.ScratchpadBrowser
import dev.klaiber.cirrus.domain.tools.TurnContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Handing a file the model made to the person it made it for.
 *
 * The bug this closes was invisible from the inside: `run_command` wrote the file, the model could
 * read it back, and the user could not reach it at all — so "I've saved it to expenses/totals.csv"
 * was a sincere offer that could not be accepted. What is asserted here is that the file actually
 * leaves the workspace, and that when it cannot, the model is told to say so rather than to repeat
 * the offer.
 */
class SaveFileToolTest {

    private class RecordingSink(var succeed: Boolean = true) : DownloadSink {
        val saved = mutableListOf<Pair<String, String?>>()

        override suspend fun save(source: File, displayName: String, mimeType: String?): SavedDownload? {
            if (!succeed) return null
            saved += displayName to mimeType
            return SavedDownload(name = displayName, location = "Downloads/$displayName")
        }
    }

    private lateinit var root: File
    private lateinit var workspace: ShellWorkspace
    private lateinit var sink: RecordingSink
    private lateinit var tool: SaveFileTool

    @Before
    fun setUp() {
        root = Files.createTempDirectory("cirrus-save").toFile()
        workspace = ShellWorkspace(root)
        sink = RecordingSink()
        tool = SaveFileTool(ScratchpadBrowser(workspace), sink)
    }

    private fun write(topic: String, name: String, text: String = "a,b\n1,2") =
        File(workspace.scratchpad(CONVERSATION).topicDirectory(topic), name).apply { writeText(text) }

    private suspend fun save(vararg pairs: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(
            tool.execute(
                buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } },
                TurnContext(CONVERSATION),
            ),
        ).jsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    /**
     * The gate, asserted rather than assumed.
     *
     * This shipped ungated on the reasoning that a file in Downloads is the thing the user asked
     * for. That argues from intent, and the gate's test is mechanical: the effect outlives the
     * turn, it happens outside Cirrus — on Android in shared storage, which survives the app being
     * uninstalled — and calling this again does not undo it, it makes a second copy. All three
     * hold, so it is a write, and `ToolRegistry` refuses it until write actions are on.
     */
    @Test
    fun `saving a file counts as a write`() {
        assertTrue(tool.writes)
    }

    @Test
    fun `a file the shell wrote reaches the user's downloads`() = runTest {
        write("expenses", "totals.csv")

        val result = save("path" to "totals.csv", "topic" to "expenses")

        assertEquals(listOf("totals.csv" to "text/csv"), sink.saved)
        assertEquals("Downloads/totals.csv", result.text("saved_to"))
        assertTrue(result.text("tell_the_user")!!.contains("Downloads"))
    }

    /** A working name is for the model; the user gets to be given something they can recognise. */
    @Test
    fun `save_as renames it on the way out`() = runTest {
        write("expenses", "out2.txt", "totals")

        val result = save(
            "path" to "out2.txt",
            "topic" to "expenses",
            "save_as" to "march expenses.csv",
        )

        assertEquals("march-expenses.csv", result.text("saved_as"))
        assertEquals("text/csv", sink.saved.single().second)
    }

    /** The name arrives as a tool argument, so it has never been near `CommandPolicy`. */
    @Test
    fun `a save_as cannot name a directory`() {
        assertEquals("report.csv", safeSaveName("../../report.csv"))
        assertEquals("report.csv", safeSaveName("data/report.csv"))
        assertEquals("file", safeSaveName("../.."))
    }

    /**
     * The usual cause of a miss is the wrong topic, so the reply says what is actually there — a
     * model that can see the listing fixes it without a round trip.
     */
    @Test
    fun `a missing file comes back with what is actually there`() = runTest {
        write("expenses", "totals.csv")

        val result = save("path" to "totals.csv", "topic" to "the-wrong-topic")

        assertTrue(result.text("error")!!.contains("the-wrong-topic"))
        assertEquals(
            listOf("expenses/totals.csv"),
            result["what_is_there"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a sink that refused is reported rather than claimed as a success`() = runTest {
        write("expenses", "totals.csv")
        sink.succeed = false

        val result = save("path" to "totals.csv", "topic" to "expenses")

        assertTrue(result.text("error")!!.contains("did not save"))
        assertNull(result["saved_to"])
    }

    /** One conversation must not be able to hand over another's files. */
    @Test
    fun `it cannot reach another conversation's scratchpad`() = runTest {
        File(workspace.scratchpad("someone-else").topicDirectory("expenses"), "theirs.csv")
            .writeText("secret")

        val result = save("path" to "theirs.csv", "topic" to "expenses")

        assertTrue(result.text("error")!!.contains("no file"))
        assertTrue(sink.saved.isEmpty())
    }

    private companion object {
        const val CONVERSATION = "conversation-one"
    }
}
