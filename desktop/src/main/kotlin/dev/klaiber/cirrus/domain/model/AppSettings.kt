package dev.klaiber.cirrus.domain.model

import kotlinx.serialization.Serializable

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * Everything configurable from the settings screen.
 *
 * [defaultParams] seeds each new conversation; conversations then own their own copy so that
 * changing defaults later never rewrites the settings of an existing thread.
 */
@Serializable
data class AppSettings(
    val baseUrl: String = "https://ollama.com",
    val hasApiKey: Boolean = false,
    val defaultModel: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Surfaces the raw request/response inspector and per-message stats. */
    val developerMode: Boolean = false,
    val defaultParams: GenerationParams = GenerationParams.Default,
    val toolsEnabledByDefault: Boolean = false,
    val webSearchMaxResults: Int = 5,
    /** Guards against a model looping on tool calls forever. */
    val maxToolIterations: Int = 6,
    val showStats: Boolean = true,
    val renderMarkdown: Boolean = true,
    /** Ask the model for a short title after the first exchange. */
    val autoTitleConversations: Boolean = true,
    /**
     * How many prior messages to replay as context. Zero means send the whole thread and let
     * the server-side context window do the truncating.
     */
    val contextMessageLimit: Int = 0,
    val sendOnEnter: Boolean = false,
    /** Offers the GitHub tools to the model. Requires a token to have any effect. */
    val gitHubToolsEnabled: Boolean = false,
    val hasGitHubToken: Boolean = false,
    /**
     * Lets every tool that changes something outside Cirrus actually run: opening a GitHub issue,
     * committing a file, or an MCP tool that has not declared itself read-only.
     *
     * Default off, and one switch rather than one per integration. Reading is recoverable and
     * writing is not, whoever is being written to.
     */
    val writeToolsAllowed: Boolean = false,
    /**
     * Offers the shell and the everyday-work tools: run_command, the clock, the calendar and the
     * device summary.
     *
     * On by default, and not behind the conversation's tools switch, for the same reason memory is
     * not: none of it leaves the machine, none of it costs a round trip, and a model that cannot
     * find out what today's date is answers scheduling questions from the year it was trained in.
     * The shell itself is safe to leave on because CommandPolicy decides what may run before
     * anything does, and the working directory is a scratch folder in Cirrus's own data
     * directory.
     */
    val shellToolsEnabled: Boolean = true,
    /**
     * How long a conversation's scratch files survive before Cirrus clears them itself.
     *
     * Never, by default. These were treated as disposable when nothing in the app could show them;
     * now that the Files screen can, they are the user's work, and deleting somebody's work on a
     * timer they did not set is not a default they should have to discover. A total size cap still
     * applies as a backstop — see [ScratchpadRetention].
     */
    val scratchpadRetention: ScratchpadRetention = ScratchpadRetention.Default,
    /**
     * Lets the model list and open applications on this computer.
     *
     * Off by default. Everything else in the local set answers a question; this one acts — it
     * puts another application in front of whatever the user was reading.
     */
    val appControlEnabled: Boolean = false,
    /** Offers the Spotify tools. Not wired on desktop yet. */
    val spotifyEnabled: Boolean = false,
    val spotifyClientId: String = "",
    val hasSpotifyAccount: Boolean = false,
    val spotifyAccountName: String = "",
    val spotifyPremium: Boolean = false,
    /** Offers the read-aloud button on each answer. */
    val readAloudEnabled: Boolean = true,
    /**
     * Whether read-aloud speaks a summary of a long answer or every word of it.
     *
     * Summary by default. Speech is linear and slow, so an answer written to be skimmed —
     * headings, a table, a code block to skip — becomes six minutes of audio with nothing to
     * skip past. Short answers are read verbatim either way; there is nothing to summarise.
     */
    val readAloudMode: ReadAloudMode = ReadAloudMode.SUMMARY,
    /** Who does the talking when an answer is read aloud. */
    val speechEngine: SpeechEngine = SpeechEngine.DEVICE,
    val hasElevenLabsKey: Boolean = false,
    val elevenLabsVoiceId: String = "",
    val elevenLabsVoiceName: String = "",
    val elevenLabsModelId: String = ElevenLabsModel.Default.id,
    /** Offers the remember/recall/forget tools, and sends pinned memories with every turn. */
    /**
     * Offers the skills library: the chooser tools, and the list of installed skills in the brief.
     *
     * On by default, and not behind the conversation's tools switch, because using a skill is
     * local — the instructions were downloaded when it was installed and nothing leaves the device
     * to read one. Installing is the part that touches the network, and that happens on a screen
     * the user is looking at, which is a different kind of decision from a tool call.
     *
     * The switch earns its place anyway: skills are text written by strangers that a model reads
     * as instruction, and somebody who wants none of that in their conversations should be able to
     * say so once rather than uninstalling them one at a time.
     */
    val skillsEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    /** Lets a model put something on the desktop notification tray. */
    val notificationToolEnabled: Boolean = true,
    /** Runs the nightly pass that merges duplicate memories and retires stale ones. */
    val memoryConsolidationEnabled: Boolean = true,
    /** Local hour at which that pass runs. Late enough to be asleep, early enough to be charged. */
    val memoryConsolidationHour: Int = 3,
    val lastConsolidationAt: Long = 0L,
    /**
     * Whether the first-run wizard has been through.
     */
    val onboardingCompleted: Boolean = false,
    /** Suggested openers on an empty chat. Off for people who know what they want to type. */
    val showStarterPrompts: Boolean = true,
)
