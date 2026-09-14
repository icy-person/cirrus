package dev.klaiber.cirrus.domain.tools

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.klaiber.cirrus.data.mcp.McpClient
import dev.klaiber.cirrus.data.mcp.SseMcpTransport
import dev.klaiber.cirrus.data.mcp.StreamableHttpMcpTransport
import dev.klaiber.cirrus.data.prefs.SecretCipher
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.remote.OllamaClient
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsCredentials
import dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials
import dev.klaiber.cirrus.data.remote.github.GitHubClient
import dev.klaiber.cirrus.data.remote.github.GitHubCredentials
import dev.klaiber.cirrus.data.repository.McpServerRepository
import dev.klaiber.cirrus.data.repository.MemoryRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.notify.Notifier
import dev.klaiber.cirrus.domain.tools.github.CommentTool
import dev.klaiber.cirrus.domain.tools.github.CreateIssueTool
import dev.klaiber.cirrus.domain.tools.github.GetIssueTool
import dev.klaiber.cirrus.domain.tools.github.GetPullRequestTool
import dev.klaiber.cirrus.domain.tools.github.ListDirectoryTool
import dev.klaiber.cirrus.domain.tools.github.ListIssuesTool
import dev.klaiber.cirrus.domain.tools.github.ListPullRequestsTool
import dev.klaiber.cirrus.domain.tools.github.ListReposTool
import dev.klaiber.cirrus.domain.tools.github.ReadFileTool
import dev.klaiber.cirrus.domain.tools.github.ReviewPullRequestTool
import dev.klaiber.cirrus.domain.tools.github.SearchCodeTool
import dev.klaiber.cirrus.domain.tools.github.WriteFileTool
import dev.klaiber.cirrus.testing.InMemoryMemoryDao
import dev.klaiber.cirrus.domain.tools.shell.ShellWorkspace
import dev.klaiber.cirrus.data.repository.SkillRepository
import dev.klaiber.cirrus.data.remote.skills.SkillsRegistryClient
import dev.klaiber.cirrus.domain.settings.SettingSwitch
import dev.klaiber.cirrus.domain.files.DownloadSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class ToolRegistryTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var settings: SettingsRepository
    private lateinit var gitHubCredentials: GitHubCredentials
    private lateinit var spotifyCredentials: SpotifyCredentials
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(
                System.getProperty("java.io.tmpdir"),
                "cirrus-registry-${System.nanoTime()}.preferences_pb",
            )
        }
        gitHubCredentials = GitHubCredentials()
        spotifyCredentials = SpotifyCredentials()
        val apiCredentials = ApiCredentials()
        apiCredentials.update(
            apiKey = "test-key",
            baseUrl = ApiCredentials.DEFAULT_BASE_URL,
        )
        settings = SettingsRepository(
            dataStore = dataStore,
            secretCipher = SecretCipher(),
            credentials = apiCredentials,
            gitHubCredentials = gitHubCredentials,
            elevenLabsCredentials = ElevenLabsCredentials(),
            spotifyCredentials = spotifyCredentials,
            json = json,
            scope = scope,
        )

        val http = OkHttpClient()
        val ollama = OllamaClient(http, json, apiCredentials)
        val gitHub = GitHubClient(http, json, gitHubCredentials)
        val mcp = McpClient(StreamableHttpMcpTransport(http), SseMcpTransport(http, json), json)
        val memories = MemoryRepository(InMemoryMemoryDao())

        registry = ToolRegistry(
            webSearchTool = WebSearchTool(ollama, settings),
            webFetchTool = WebFetchTool(ollama),
            downloadFileTool = DownloadFileTool(
                OkHttpClient(),
                ShellWorkspace(
                    File(
                        System.getProperty("java.io.tmpdir"),
                        "cirrus-download-${System.nanoTime()}",
                    ),
                ),
                object : DownloadSink {
                    override suspend fun save(
                        source: File,
                        displayName: String,
                        mimeType: String?,
                    ) = null
                },
            ),
            gitHubTools = GitHubToolSet(
                listRepos = ListReposTool(gitHub),
                searchCode = SearchCodeTool(gitHub),
                readFile = ReadFileTool(gitHub),
                listDirectory = ListDirectoryTool(gitHub),
                listIssues = ListIssuesTool(gitHub),
                getIssue = GetIssueTool(gitHub),
                listPulls = ListPullRequestsTool(gitHub),
                getPull = GetPullRequestTool(gitHub),
                createIssue = CreateIssueTool(gitHub),
                comment = CommentTool(gitHub),
                reviewPull = ReviewPullRequestTool(gitHub),
                writeFile = WriteFileTool(gitHub),
            ),
            mcpTools = McpToolSet(
                repository = McpServerRepository(
                    dataStore = dataStore,
                    secretCipher = SecretCipher(),
                    client = mcp,
                    json = json,
                    scope = scope,
                ),
                client = mcp,
            ),
            memoryTools = MemoryToolSet(
                RememberTool(memories),
                RecallTool(memories),
                ForgetTool(memories),
            ),
            skillTools = SkillToolSet(
                repository = SkillRepository(
                    dataStore = dataStore,
                    registry = SkillsRegistryClient(OkHttpClient(), json),
                    json = json,
                    scope = scope,
                ),
                list = StubTool("list_skills"),
                use = StubTool("use_skill"),
            ),
            notificationTool = SendNotificationTool(SilentNotifier()),
            deviceTools = DeviceToolSet(
                shell = listOf(StubTool("run_command")),
                apps = listOf(StubTool("open_app")),
                location = listOf(StubTool("get_location")),
            ),
            spotifyTools = SpotifyToolSet(
                all = listOf(StubTool("spotify_search"), StubTool("spotify_edit", writes = true)),
            ),
            settingsTool = DescribeSettingsTool(settings),
            settingsRepository = settings,
            gitHubCredentials = gitHubCredentials,
            spotifyCredentials = spotifyCredentials,
            apiCredentials = apiCredentials,
        )
    }

    // ---- The external-tools switch --------------------------------------------------------

    @Test
    fun `web tools are offered and runnable with the switch on`() = runBlocking {
        assertTrue(offeredNames(externalTools = true).contains("web_search"))
        assertNotNull(registry.find("web_search", externalTools = true))
    }

    @Test
    fun `web tools are neither offered nor runnable with the switch off`() = runBlocking {
        assertFalse(offeredNames(externalTools = false).contains("web_search"))
        assertNull(registry.find("web_search", externalTools = false))
    }

    // ---- Memory and notifications sit outside that switch ---------------------------------

    @Test
    fun `memory tools are offered even with external tools off`() = runBlocking {
        setMemoryEnabled(true)
        assertTrue(offeredNames(externalTools = false).contains("remember"))
        assertNotNull(registry.find("remember", externalTools = false))
    }

    @Test
    fun `memory tools disappear entirely when memory is switched off`() = runBlocking {
        setMemoryEnabled(false)
        assertFalse(offeredNames(externalTools = true).contains("remember"))
        assertNull(registry.find("remember", externalTools = true))
    }

    @Test
    fun `skill tools are offered even with external tools off, and follow their own switch`() = runBlocking {
        settings.setSkillsEnabled(true)
        await("skills on") { settings.current.value.skillsEnabled }
        assertTrue(offeredNames(externalTools = false).contains("use_skill"))
        assertNotNull(registry.find("use_skill", externalTools = false))
        settings.setSkillsEnabled(false)
        await("skills off") { !settings.current.value.skillsEnabled }
        assertFalse(offeredNames(externalTools = true).contains("use_skill"))
        assertNull(registry.find("use_skill", externalTools = true))
        assertTrue(registry.explainRefusal("use_skill").contains(SettingSwitch.SKILLS.path))
    }

    @Test
    fun `the notification tool follows its own setting`() = runBlocking {
        settings.setNotificationToolEnabled(false)
        await("notifications off") { !settings.current.value.notificationToolEnabled }
        assertFalse(offeredNames(externalTools = true).contains("send_notification"))
        assertNull(registry.find("send_notification", externalTools = true))
    }

    // ---- The device tools sit outside that switch too ---------------------------------------

    @Test
    fun `shell tools are offered even with external tools off`() = runBlocking {
        assertTrue(offeredNames(externalTools = false).contains("run_command"))
        assertNotNull(registry.find("run_command", externalTools = false))
    }

    @Test
    fun `shell tools disappear entirely when the setting is off`() = runBlocking {
        settings.setShellToolsEnabled(false)
        await("shell off") { !settings.current.value.shellToolsEnabled }
        assertFalse(offeredNames(externalTools = true).contains("run_command"))
        assertNull(registry.find("run_command", externalTools = true))
    }

    @Test
    fun `app tools are absent by default and appear only when switched on`() = runBlocking {
        assertFalse(offeredNames(externalTools = true).contains("open_app"))
        assertNull(registry.find("open_app", externalTools = true))
        settings.setAppControlEnabled(true)
        await("app control on") { settings.current.value.appControlEnabled }
        assertTrue(offeredNames(externalTools = false).contains("open_app"))
        assertNotNull(registry.find("open_app", externalTools = false))
    }

    // ---- The GitHub gates ------------------------------------------------------------------

    @Test
    fun `github tools are absent without a token`() = runBlocking {
        configureGitHub(token = null, toolsEnabled = true, writesAllowed = true)
        assertTrue(offeredNames(externalTools = true).none { it.startsWith("github_") })
        assertNull(registry.find("github_list_repos", externalTools = true))
    }

    @Test
    fun `a github tool cannot run while the feature is switched off`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = false, writesAllowed = false)
        assertTrue(offeredNames(externalTools = true).none { it.startsWith("github_") })
        assertNull(registry.find("github_list_repos", externalTools = true))
    }

    @Test
    fun `write tools are withheld unless writes are allowed`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = true, writesAllowed = false)
        val offered = offeredNames(externalTools = true)
        assertTrue(offered.contains("github_list_repos"))
        assertFalse(offered.contains("github_create_issue"))
        assertNull(registry.find("github_create_issue", externalTools = true))
    }

    @Test
    fun `write tools appear once writes are allowed`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = true, writesAllowed = true)
        assertTrue(offeredNames(externalTools = true).contains("github_create_issue"))
        assertNotNull(registry.find("github_create_issue", externalTools = true))
    }

    @Test
    fun `github tools never run when external tools are off, token or not`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = true, writesAllowed = true)
        assertNull(registry.find("github_list_repos", externalTools = false))
        assertNull(registry.find("github_create_issue", externalTools = false))
    }

    // ---- Names ------------------------------------------------------------------------------

    @Test
    fun `an unknown name resolves to nothing rather than throwing`() = runBlocking {
        assertNull(registry.find("definitely_not_a_tool", externalTools = true))
    }

    @Test
    fun `every offered definition carries a name the registry can resolve`() = runBlocking {
        configureGitHub(token = "ghp_pretend", toolsEnabled = true, writesAllowed = true)
        setMemoryEnabled(true)
        val offered = offeredNames(externalTools = true)
        assertTrue("expected a non-trivial set of tools", offered.size > 5)
        offered.forEach { name ->
            assertNotNull(
                "$name was offered to the model but cannot be resolved",
                registry.find(name, externalTools = true),
            )
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun offeredNames(externalTools: Boolean): List<String> =
        registry.definitions(externalTools = externalTools).map { definition ->
            definition.jsonObject.getValue("function").jsonObject
                .getValue("name").jsonPrimitive.content
        }

    private suspend fun setMemoryEnabled(enabled: Boolean) {
        settings.setMemoryEnabled(enabled)
        await("memory enabled=$enabled") { settings.current.value.memoryEnabled == enabled }
    }

    private suspend fun configureGitHub(
        token: String?,
        toolsEnabled: Boolean,
        writesAllowed: Boolean,
    ) {
        settings.setGitHubToolsEnabled(toolsEnabled)
        settings.setWriteToolsAllowed(writesAllowed)
        val derivedWrites = toolsEnabled && writesAllowed
        await("gitHubToolsEnabled=$toolsEnabled") {
            settings.current.value.gitHubToolsEnabled == toolsEnabled
        }
        await("credential mirror caught up") {
            gitHubCredentials.writesAllowed == derivedWrites
        }
        gitHubCredentials.update(token = token, writesAllowed = derivedWrites)
    }

    private fun await(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(5)
        }
        fail("timed out waiting for: $what")
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}

private class StubTool(
    override val name: String,
    override val writes: Boolean = false,
) : CirrusTool {
    override val definition: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", "stub")
        }
    }

    override suspend fun execute(arguments: JsonObject): String = "{}"
}

private class SilentNotifier : Notifier {
    override fun notify(
        title: String,
        body: String,
        channel: Notifier.Channel,
        conversationId: String?,
    ): Boolean = true
}
