package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.domain.tools.shell.ShellWorkspace
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The tool that puts a file where a command can read it.
 *
 * Two properties are worth asserting and neither is about HTTP. The bytes have to land in the
 * *topic the model is working in*, because a file saved anywhere else is a file the next
 * `run_command` cannot open — which is the whole reason this exists rather than being a second
 * `web_fetch`. And the size cap has to hold while the body is still arriving, since the failure it
 * prevents is a phone running out of memory on a URL a model chose.
 */
class DownloadFileToolTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var workspace: ShellWorkspace
    private lateinit var tool: DownloadFileTool

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = Files.createTempDirectory("cirrus-download").toFile()
        workspace = ShellWorkspace(root)
        tool = DownloadFileTool(OkHttpClient(), workspace)
    }

    @After
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    private suspend fun run(vararg pairs: Pair<String, String>) =
        Json.parseToJsonElement(
            tool.execute(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }),
        ).jsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `saves the body into the topic the command will read it from`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><body><p>Hello</p></body></html>")
                .build(),
        )

        val result = run(*arrayOf("url" to server.url("/page.html").toString(), "topic" to "Research Notes"))

        // Normalised, exactly as run_command would normalise the same argument — the two have to
        // agree about which directory a topic is, or the file is written where nothing looks.
        assertEquals("research-notes", result.text("topic"))
        assertEquals("page.html", result.text("path"))
        assertEquals("text/html", result.text("content_type"))

        val saved = File(workspace.topicDirectory("research-notes"), "page.html")
        assertTrue("the file has to be on disk, not just in the reply", saved.exists())
        assertTrue(saved.readText().contains("<p>Hello</p>"))
        // The markup is the point: web_fetch would have flattened this away.
        assertTrue(result.text("preview")!!.contains("<body>"))
    }

    @Test
    fun `stops at the topic's cap rather than reading a whole large file into memory`() = runTest {
        val oversized = "x".repeat((ShellWorkspace.MAX_TOPIC_BYTES + 50_000).toInt())
        server.enqueue(MockResponse.Builder().body(oversized).build())

        val result = run(*arrayOf("url" to server.url("/big.txt").toString()))

        assertEquals(ShellWorkspace.MAX_TOPIC_BYTES, result["bytes"]!!.jsonPrimitive.long)
        assertTrue("truncation has to be said out loud", result.text("truncated") != null)
    }

    /**
     * A refusal rather than an error, and it names the tool that fixes it.
     *
     * The alternative — letting the download through and leaving `trimTo` to delete oldest-first
     * afterwards — pays for one runaway fetch with somebody else's working files, silently.
     */
    @Test
    fun `refuses when the topic is already over its budget`() = runTest {
        File(workspace.topicDirectory("full"), "blob.bin")
            .writeText("y".repeat((ShellWorkspace.MAX_TOPIC_BYTES + 1).toInt()))
        server.enqueue(MockResponse.Builder().body("never fetched").build())

        val result = run(*arrayOf("url" to server.url("/x.txt").toString(), "topic" to "full"))

        assertTrue(result["refused"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result.text("reason")!!.contains("clean_workspace"))
        assertEquals("nothing should have been requested", 0, server.requestCount)
    }

    @Test
    fun `an http failure comes back as data, not as an exception`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        val result = run(*arrayOf("url" to server.url("/gone").toString()))

        assertTrue(result.text("error")!!.contains("404"))
    }

    /**
     * A scheme that is not http(s) is refused rather than repaired.
     *
     * Rewriting `file:///etc/passwd` to `https://` would go and fetch a stranger, and the model
     * would have no way of knowing it had asked for one thing and been given another.
     */
    @Test
    fun `only http and https are fetched`() = runTest {
        val result = run(*arrayOf("url" to "file:///etc/passwd"))

        assertTrue(result.text("error")!!.contains("http"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a bare host is treated as https rather than refused`() {
        assertEquals("https://example.com", normalizeUrl("example.com"))
        assertEquals("https://example.com/a", normalizeUrl("  https://example.com/a  "))
        assertNull(normalizeUrl("content://media/external/images"))
        assertNull(normalizeUrl(""))
    }

    /**
     * The name arrives as a tool argument, so it never passes `CommandPolicy` — which is the same
     * hole `ShellWorkspace.topicName` closes, closed the same way.
     */
    @Test
    fun `a file name cannot name a directory or climb out of one`() {
        assertEquals("report.csv", safeFileName("../../report.csv"))
        assertEquals("report.csv", safeFileName("data/report.csv"))
        assertEquals("my-notes.txt", safeFileName("my notes.txt"))
        assertEquals("download", safeFileName("../.."))
        assertFalse(safeFileName("a/b/../c.txt").contains('/'))
    }
}
