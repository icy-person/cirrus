package dev.klaiber.cirrus.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Settings, grouped by what someone came here to do.
 *
 * The same nine groups as the phone, in the same order, under the same names — which is the point
 * of them. A user who has both builds should not have to learn where things are twice, and
 * `describe_settings` hands the model "Settings → Tools → Memory" without having to know which
 * machine it is answering on.
 *
 * That this screen was one long scroll was defended on the grounds that a 1180pt window can show
 * it. It can, and it is still the wrong shape: the old page was ordered by when each control was
 * written, so the context-window field sat below the GitHub token, and a window wide enough to
 * render thirty unrelated controls at once is not a window anybody can scan. Grouping costs one
 * click and buys a screen you can read, and it leaves an obvious place to put the next thing.
 *
 * The headings are load-bearing rather than decorative: `SettingSwitch.path` names one, and
 * `SettingsCatalogTest` checks every path against this list. Renaming a section here means
 * renaming it in `SettingsCatalog` too, or the test says so.
 *
 * Memory and Agents are destinations of their own rather than sections — both are content you
 * browse and edit, not switches you flip. See [SettingsDestination].
 */
enum class SettingsSection(
    val title: String,
    val summary: String,
    val icon: ImageVector,
) {
    CONNECTION(
        title = "Connection",
        summary = "Host, API key, default model",
        icon = Icons.Outlined.Cloud,
    ),
    GENERATION(
        title = "Chats",
        summary = "Sampling defaults, context window, titles",
        icon = Icons.Outlined.Tune,
    ),
    TOOLS(
        title = "Tools",
        summary = "The shell, apps, memory, limits",
        icon = Icons.Outlined.Bolt,
    ),
    INTEGRATIONS(
        title = "GitHub and MCP",
        summary = "Repositories, and servers you have attached",
        icon = Icons.Outlined.Terminal,
    ),
    MUSIC(
        title = "Music",
        summary = "Connect Spotify, and control what is playing",
        icon = Icons.Outlined.MusicNote,
    ),
    // Android's counterpart also owns dictation. This build has none — `SpeechRecognizer` has no
    // desktop equivalent worth shipping — so the section is read-aloud only, and says so rather
    // than advertising a control that is not below it.
    VOICE(
        title = "Voice",
        summary = "Reading answers aloud, and the voice that does it",
        icon = Icons.Outlined.RecordVoiceOver,
    ),
    APPEARANCE(
        title = "Appearance",
        summary = "Theme, colour, markdown",
        icon = Icons.Outlined.Palette,
    ),
    DIAGNOSTICS(
        title = "Diagnostics",
        summary = "Generation stats and the request inspector",
        icon = Icons.Outlined.Insights,
    ),
    // The phone's Data section is one delete button, because on Android where the files live is
    // the system's problem. Here it is nobody's until somebody says which folder to copy, so this
    // is also where the build names its data directory and offers to open it — the honest place
    // for it, since "on this computer only" is the claim the section exists to back up.
    DATA(
        title = "Data",
        summary = "Everything stored on this computer",
        icon = Icons.Outlined.Storage,
    ),
    ;

    companion object {
        fun fromRoute(value: String?): SettingsSection =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CONNECTION
    }
}

/** The two entries that open a screen of their own rather than a group of switches. */
enum class SettingsDestination(val title: String, val summary: String, val icon: ImageVector) {
    MEMORY(
        title = "Memory",
        summary = "What Cirrus remembers between conversations",
        icon = Icons.Outlined.Psychology,
    ),
    AGENTS(
        title = "Agents",
        summary = "Prompts that run on a schedule",
        icon = Icons.Outlined.Schedule,
    ),
}
