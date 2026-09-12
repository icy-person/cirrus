package dev.klaiber.cirrus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsVoice
import dev.klaiber.cirrus.data.remote.spotify.SpotifyCredentials
import dev.klaiber.cirrus.di.AppContainer
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.domain.model.ElevenLabsModel
import dev.klaiber.cirrus.domain.model.ReadAloudMode
import dev.klaiber.cirrus.domain.model.ScratchpadRetention
import dev.klaiber.cirrus.domain.model.SpeechEngine
import dev.klaiber.cirrus.domain.model.ThemeMode
import dev.klaiber.cirrus.domain.userMessage
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.HelpBadge
import dev.klaiber.cirrus.ui.components.HelpTooltip
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.components.PillStyle
import dev.klaiber.cirrus.ui.components.ScreenTopBar
import dev.klaiber.cirrus.ui.components.SectionLabel
import dev.klaiber.cirrus.ui.components.readingMeasure
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.util.rememberClipboard
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File

/**
 * What to call this build when it names itself.
 *
 * Read from the jar's manifest so a package cannot disagree with itself, with the current release
 * as the fallback for a `:desktop:run` — which has no manifest to read and is where this string is
 * least important anyway.
 */
private val AppVersion: String =
    AppContainer::class.java.`package`?.implementationVersion ?: "1.9.0"

/**
 * The settings hub.
 *
 * Nine groups and two destinations, in place of the one long scroll this screen used to be. The
 * old page was ordered by when each control was written, which put the context-window field below
 * the GitHub token and made "where is the theme?" a scrolling exercise; a wide window renders that
 * faithfully and does not make it scannable. Grouping costs one click and buys a screen you can
 * read at a glance — and, as on the phone, it leaves an obvious place to put the next thing, which
 * is exactly how the old one got so long.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onClose: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAgents: () -> Unit,
    onRunSetup: () -> Unit,
    topInset: Dp = 0.dp,
    leadingInset: Dp = 0.dp,
) {
    // `collectAsState()` with no argument on a `StateFlow` the container loaded before the first
    // window: supplying an initial value selects the plain-`Flow` overload and shows a default
    // `AppSettings` for one composition, which here would read as the connection panel flashing
    // "no API key" on every visit.
    val settings by container.settingsRepository.settings.collectAsState()
    val memoryCount by container.memoryRepository.activeCount.collectAsState(0)
    val agents by container.agentRepository.agents.collectAsState(emptyList())

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Settings",
            onBack = onClose,
            topInset = topInset,
            leadingInset = leadingInset,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.readingMeasure()) {
                ConnectionSummary(
                    hasKey = settings.hasApiKey,
                    host = settings.baseUrl,
                    model = settings.defaultModel,
                    onClick = { onOpenSection(SettingsSection.CONNECTION) },
                )

                SectionLabel("What Cirrus knows")
                HubCard {
                    HubRow(
                        icon = SettingsDestination.MEMORY.icon,
                        title = SettingsDestination.MEMORY.title,
                        summary = if (memoryCount > 0) {
                            "$memoryCount remembered"
                        } else {
                            SettingsDestination.MEMORY.summary
                        },
                        onClick = onOpenMemory,
                    )
                    HubDivider()
                    HubRow(
                        icon = SettingsDestination.AGENTS.icon,
                        title = SettingsDestination.AGENTS.title,
                        summary = if (agents.isNotEmpty()) {
                            "${agents.size} scheduled"
                        } else {
                            SettingsDestination.AGENTS.summary
                        },
                        onClick = onOpenAgents,
                    )
                }

                SectionLabel("Settings")
                HubCard {
                    SettingsSection.entries.forEachIndexed { index, section ->
                        if (index > 0) HubDivider()
                        HubRow(
                            icon = section.icon,
                            title = section.title,
                            summary = section.summary,
                            onClick = { onOpenSection(section) },
                        )
                    }
                }

                SectionLabel("Getting set up")
                HubCard {
                    // The wizard is where the connection is proved rather than merely typed, which
                    // makes it the right answer to "it stopped working" as well as to "I am new
                    // here".
                    HubRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "Run setup again",
                        summary = "Walk through the host, key and model, and test the connection",
                        onClick = onRunSetup,
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Cirrus $AppVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * One group of controls, on its own screen.
 *
 * Every control here is the one that was already on the long page; only where it lives has
 * changed. The two additions are the sections that page never had — Diagnostics and Data — both of
 * which existed on the phone and neither of which had anywhere obvious to go when the screen was
 * one scroll ordered by age.
 */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMcpServers: () -> Unit,
    onOpenSkills: () -> Unit,
    topInset: Dp = 0.dp,
    leadingInset: Dp = 0.dp,
) {
    val settings by container.settingsRepository.settings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = section.title,
            onBack = onBack,
            topInset = topInset,
            leadingInset = leadingInset,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.readingMeasure()) {
                when (section) {
                    SettingsSection.CONNECTION -> ConnectionBody(container, settings)
                    SettingsSection.GENERATION -> GenerationBody(container, settings)
                    SettingsSection.TOOLS -> ToolsBody(container, settings)
                    SettingsSection.SKILLS -> SkillsBody(container, settings, onOpenSkills)
                    SettingsSection.INTEGRATIONS -> IntegrationsBody(container, settings, onOpenMcpServers)
                    SettingsSection.MUSIC -> MusicBody(container, settings)
                    SettingsSection.VOICE -> VoiceBody(container, settings)
                    SettingsSection.APPEARANCE -> AppearanceBody(container, settings)
                    SettingsSection.DIAGNOSTICS -> DiagnosticsBody(container, settings)
                    SettingsSection.DATA -> DataBody(container)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The hub's own furniture
// ─────────────────────────────────────────────────────────────────────────────

/** The one thing worth showing without a click: whether Cirrus can reach a model at all. */
@Composable
private fun ConnectionSummary(hasKey: Boolean, host: String, model: String, onClick: () -> Unit) {
    val connected = hasKey || !host.contains("ollama.com")

    // Connected is the ordinary case, so it gets the ordinary treatment: an outlined row like every
    // other. Only the failure is tinted. A green-equivalent "all is well" panel spends the reader's
    // attention on the state that needed none of it.
    OutlinedPanel(
        onClick = onClick,
        shape = LargeContainerShape,
        color = if (connected) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        borderColor = if (connected) {
            MaterialTheme.colorScheme.outlineVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        val onContainer = if (connected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (connected) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.ErrorOutline
                },
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (connected) "Connected" else "No API key yet",
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                )
                Text(
                    text = buildString {
                        append(host.removePrefix("https://").removePrefix("http://"))
                        if (model.isNotBlank()) append(" · ").append(model)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        onContainer
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A group of rows as one bordered object, hairline-separated — the reference site's list idiom. */
@Composable
private fun HubCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun HubDivider() {
    Hairline(startIndent = 56.dp)
}

@Composable
private fun HubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The sections
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Where a connection state lives while somebody is proving one.
 *
 * Local to the screen rather than in a repository, because it is a fact about this visit — the
 * phone keeps it in a `SettingsViewModel` for exactly as long as the screen is up, and this is
 * that lifetime written a different way.
 */
private sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus

    data object Testing : ConnectionStatus

    data class Success(val model: String) : ConnectionStatus

    data class Failure(val message: String) : ConnectionStatus
}

/**
 * One tap to fill the host field with a known-good address, instead of typing or remembering the
 * port and the `/v1` suffix that switches Cirrus into OpenAI-compatible mode. Filling the draft
 * rather than saving immediately keeps this consistent with typing the address by hand: nothing is
 * applied until "Apply host" is pressed below.
 */
@Composable
private fun HostPresetRow(onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        PillButton(label = "Ollama Cloud", style = PillStyle.Secondary, onClick = { onSelect("https://ollama.com") })
        PillButton(label = "Local Ollama", style = PillStyle.Secondary, onClick = { onSelect("http://localhost:11434") })
        PillButton(label = "LM Studio", style = PillStyle.Secondary, onClick = { onSelect("http://localhost:1234/v1") })
    }
}

/**
 * The connection, and proof that it works.
 *
 * Key first and host second, as on the phone. The order looks backwards written down — the host is
 * where requests go, so surely it comes first — but the hosted API is what almost everybody is
 * setting up, and for them the host is already right and the key is the entire job. Somebody
 * pointing Cirrus at a machine on their own network is the rarer case and is looking for the field
 * rather than falling into it.
 *
 * The key is saved *before* the connection is tested, because the credential holder the HTTP layer
 * reads is fed from the same store — testing an unsaved key tests the old one. The host is applied
 * the same way and by its own button: two secrets and an address committed together by one control
 * meant no way to correct a host without re-entering a key.
 */
@Composable
private fun ConnectionBody(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository

    var host by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var status by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle) }

    SecretField(
        label = "API key",
        fieldLabel = "API key",
        replaceLabel = "Replace API key",
        placeholder = "ollama api key",
        saveLabel = "Save key",
        help = "Needed for the hosted API at ollama.com. A local Ollama instance or LM Studio " +
            "usually needs no key at all — LM Studio only checks for one if you have turned on " +
            "\"Require API key\" under its own server settings, in which case any non-empty " +
            "value here is accepted. Unlike the phone build there is no Keystore here to wrap it " +
            "in: it is a file in Cirrus's own data folder, readable by anything running as you. " +
            "Settings → Data names the folder.",
        isSet = settings.hasApiKey,
        footer = "",
        onSave = { key ->
            scope.launch {
                repository.setApiKey(key)
                status = ConnectionStatus.Idle
                // A new key usually means a different account and therefore a different catalogue.
                container.modelRepository.refresh()
            }
        },
        onClear = {
            scope.launch {
                repository.clearApiKey()
                status = ConnectionStatus.Idle
            }
        },
        trailing = {
            PillButton(
                label = "Test",
                style = PillStyle.Secondary,
                enabled = settings.hasApiKey && status != ConnectionStatus.Testing,
                onClick = {
                    scope.launch {
                        status = ConnectionStatus.Testing
                        status = testConnection(container)
                    }
                },
            )
        },
    )

    Spacer(Modifier.height(6.dp))
    ConnectionStatusRow(hasKey = settings.hasApiKey, status = status)

    Spacer(Modifier.height(12.dp))
    LabelWithHelp(
        label = "Host",
        help = "Where every request goes. Use https://ollama.com for the hosted API, " +
            "http://<address>:11434 for an Ollama instance on your own machine, or " +
            "http://<address>:1234/v1 for LM Studio. The trailing /v1 is what tells Cirrus to " +
            "speak LM Studio's OpenAI-compatible API instead of Ollama's — streaming, tool calls " +
            "and image attachments all work the same way over it. If LM Studio runs on a " +
            "different machine, turn on \"Serve on Local Network\" under its Developer tab and " +
            "use that machine's LAN IP rather than localhost. A trailing /api is stripped " +
            "automatically.",
    )
    HostPresetRow(onSelect = { host = it })
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("Host") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        shape = ContainerShape,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = "Point at a local instance (http://localhost:11434, or " +
            "http://localhost:1234/v1 for LM Studio) to use your own hardware.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    // Shown only once the field has been changed, which is what makes it an answer to "did that
    // save?" rather than a button sitting permanently under a field nobody has touched.
    if (host != settings.baseUrl) {
        Spacer(Modifier.height(6.dp))
        PillButton(
            label = "Apply host",
            onClick = {
                scope.launch {
                    repository.setBaseUrl(host)
                    status = ConnectionStatus.Idle
                    container.modelRepository.refresh()
                }
            },
        )
    }
}

/**
 * Reaches the host with the credentials as they now stand, and says which model answered.
 *
 * Naming the model is the point: "connected" on its own is also what a host with an empty
 * catalogue would say, and a catalogue is what the next screen needs.
 */
private suspend fun testConnection(container: AppContainer): ConnectionStatus {
    val configured = container.settingsRepository.settings.value.defaultModel
    val model = configured.ifBlank { container.modelRepository.models.value.firstOrNull()?.name.orEmpty() }
    if (model.isBlank()) container.modelRepository.refresh()
    val resolved = model.ifBlank { container.modelRepository.models.value.firstOrNull()?.name.orEmpty() }
    if (resolved.isBlank()) {
        return ConnectionStatus.Failure("Could not list any models from this host.")
    }
    return container.ollamaClient.validateCredentials(resolved).fold(
        onSuccess = { ConnectionStatus.Success(resolved) },
        onFailure = { ConnectionStatus.Failure(it.userMessage()) },
    )
}

/** One line under the key field, saying where the connection actually stands. */
@Composable
private fun ConnectionStatusRow(hasKey: Boolean, status: ConnectionStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            ConnectionStatus.Idle -> Text(
                text = if (hasKey) {
                    "A key is stored, as a file in Cirrus's own data folder."
                } else {
                    "Create one at ollama.com/settings/keys, or leave this blank for a local " +
                        "Ollama or LM Studio host."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConnectionStatus.Testing -> {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Testing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ConnectionStatus.Success -> {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Connected — reached ${status.model}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is ConnectionStatus.Failure -> {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Everything about how a chat behaves before a single word has been typed into it. */
@Composable
private fun GenerationBody(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository
    val models by container.modelRepository.models.collectAsState()

    ModelDropdownRow(
        selected = settings.defaultModel,
        models = models.map { it.name },
        onSelect = { scope.launch { repository.setDefaultModel(it) } },
    )
    SwitchRow(
        title = "Web tools by default",
        subtitle = "Enable web search and page fetch for new conversations",
        help = "Lets the model run web searches and fetch pages mid-answer. It decides when to " +
            "call them, and each call is an extra round trip to your host. You can still flip " +
            "this per conversation from the composer.",
        checked = settings.toolsEnabledByDefault,
        onCheckedChange = { scope.launch { repository.setToolsEnabledByDefault(it) } },
    )
    SwitchRow(
        title = "Auto-title conversations",
        subtitle = "Name threads from their content, and keep the name current",
        help = "Cirrus names a new thread from its first exchange, then re-summarises it as the " +
            "conversation grows — at most once every 30 minutes, so a long session costs a " +
            "handful of short requests rather than one per turn. Rename a thread yourself and it " +
            "is never overwritten.",
        checked = settings.autoTitleConversations,
        onCheckedChange = { scope.launch { repository.setAutoTitle(it) } },
    )
    SwitchRow(
        title = "Suggested openers",
        subtitle = "Four things to try on an empty conversation",
        help = "A blank composer asks a question it does not answer. The suggestions are matched " +
            "to what you have switched on — nothing offers to read your repositories unless a " +
            "GitHub token is configured. Turn them off once you know what you want to type.",
        checked = settings.showStarterPrompts,
        onCheckedChange = { scope.launch { repository.setShowStarterPrompts(it) } },
    )
    SwitchRow(
        title = "Send on Enter",
        subtitle = "Otherwise Enter starts a line and you click send",
        help = "Turns Enter into send and Shift+Enter into a newline. Handy for short " +
            "back-and-forth chats, awkward when you write multi-line prompts.",
        checked = settings.sendOnEnter,
        onCheckedChange = { scope.launch { repository.setSendOnEnter(it) } },
    )
    StepperRow(
        title = "Context messages",
        subtitle = if (settings.contextMessageLimit == 0) {
            "Sending the full thread every turn"
        } else {
            "Sending the last ${settings.contextMessageLimit} messages"
        },
        help = "How much of the thread is replayed with every turn. A smaller number means " +
            "cheaper, faster requests but a shorter memory: the model literally cannot see what " +
            "fell outside the window. \"All\" sends everything and lets the model's own context " +
            "window do the truncating.",
        value = settings.contextMessageLimit.toFloat(),
        range = 0f..100f,
        steps = 19,
        format = { if (it.toInt() == 0) "all" else it.toInt().toString() },
        onChange = { scope.launch { repository.setContextMessageLimit(it.toInt()) } },
    )
}

/**
 * The tools that do not leave this computer, and the one switch that governs the ones that do.
 *
 * Location is absent, and deliberately: no tool in this build answers it and there is no
 * permission to ask for. A switch the model can read about is a capability it will offer, so one
 * with nothing behind it is worse than no switch at all — `SettingsCatalogTest` asserts it stays
 * gone.
 */
@Composable
private fun ToolsBody(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository

    SwitchRow(
        title = "Shell and everyday tools",
        subtitle = "The clock, the calendar, this computer's details, and safe commands",
        help = "Gives the model four things it otherwise has to guess at: what the date and time " +
            "are, how a month is laid out, what this computer is, and a shell for the small " +
            "mechanical jobs — counting, sorting, checksums. The shell runs in a scratch folder " +
            "inside Cirrus's own data directory and can reach nothing outside it: absolute paths, " +
            "\"..\" and command substitution are refused before anything runs, and only a fixed " +
            "list of programs is allowed at all. Nothing here touches the network, so it is " +
            "offered whatever the per-conversation tools switch says.",
        checked = settings.shellToolsEnabled,
        onCheckedChange = { scope.launch { repository.setShellToolsEnabled(it) } },
    )
    ScratchpadRetentionSelector(
        selected = settings.scratchpadRetention,
        onSelect = { scope.launch { repository.setScratchpadRetention(it) } },
    )
    SwitchRow(
        title = "Apps",
        subtitle = "List what is installed on this computer, and open one",
        help = "Off by default, because these are the local tools that act rather than answer — " +
            "opening an app puts it in front of whatever you were reading. Installing is not " +
            "something it can do by itself.",
        checked = settings.appControlEnabled,
        onCheckedChange = { scope.launch { repository.setAppControlEnabled(it) } },
    )
    SwitchRow(
        title = "Memory",
        subtitle = "Remember things about you between conversations",
        help = "Lets the model save durable facts — how you like to work, what you are building, " +
            "who people are — and look them up later. Everything it keeps is on the Memory " +
            "screen, where you can read, edit or retire any of it. Nothing leaves this computer.",
        checked = settings.memoryEnabled,
        onCheckedChange = { scope.launch { repository.setMemoryEnabled(it) } },
    )
    SwitchRow(
        title = "Nightly memory tidy-up",
        subtitle = "Merge duplicates and retire what has been superseded",
        help = "Once a night, Cirrus reads the conversations you have had since the last pass, " +
            "harvests anything durable, then merges near-duplicate memories and retires ones that " +
            "have been overtaken. Nothing is deleted — retiring is archiving, and the Memory " +
            "screen restores anything. Unlike the phone, this only runs while Cirrus is open.",
        checked = settings.memoryConsolidationEnabled,
        onCheckedChange = { scope.launch { repository.setMemoryConsolidationEnabled(it) } },
        enabled = settings.memoryEnabled,
    )
    StepperRow(
        title = "Nightly pass hour",
        subtitle = "Runs at ${"%02d".format(settings.memoryConsolidationHour)}:00, local time",
        help = "Late enough that nobody is using the model, early enough that the machine is " +
            "probably still awake. A desktop that was asleep or shut down simply misses the pass " +
            "and takes the next one — a fortnight of stale catch-up at launch would be worse.",
        value = settings.memoryConsolidationHour.toFloat(),
        range = 0f..23f,
        steps = 22,
        format = { "%02d:00".format(it.toInt()) },
        onChange = { scope.launch { repository.setMemoryConsolidationHour(it.toInt()) } },
        enabled = settings.memoryConsolidationEnabled && settings.memoryEnabled,
    )
    SwitchRow(
        title = "Notifications",
        subtitle = "Let a reply reach you when you are not looking at Cirrus",
        help = "Mostly for scheduled agents: an answer written at 3am is worthless if nobody " +
            "knows it exists. In an ordinary chat the model is told not to notify you about " +
            "something you are already reading.",
        checked = settings.notificationToolEnabled,
        onCheckedChange = { scope.launch { repository.setNotificationToolEnabled(it) } },
    )
    SwitchRow(
        title = "Allow write actions",
        subtitle = "One switch for anything that changes something outside Cirrus",
        help = "Off by default, and worth leaving off. It governs every integration at once: " +
            "opening a GitHub issue, committing a file, editing a Spotify playlist, and any MCP " +
            "tool that has not declared itself read-only. Reading is recoverable and writing is " +
            "not, and a tool call is decided by a model rather than by you. With this off those " +
            "tools are not offered at all, so nothing can try and fail — everything read-only " +
            "still works.",
        checked = settings.writeToolsAllowed,
        onCheckedChange = { scope.launch { repository.setWriteToolsAllowed(it) } },
    )
    StepperRow(
        title = "Search results",
        subtitle = "How many results web_search returns per call",
        help = "More results give the model more to work with, but each one is pasted into the " +
            "conversation and eats context the model could be using to think.",
        value = settings.webSearchMaxResults.toFloat(),
        range = 1f..10f,
        steps = 8,
        format = { it.toInt().toString() },
        onChange = { scope.launch { repository.setWebSearchMaxResults(it.toInt()) } },
    )
    StepperRow(
        title = "Max tool rounds",
        subtitle = "Upper bound on back-and-forth tool calls in one turn",
        help = "A model can search, read the results, then search again. This caps how many of " +
            "those rounds one turn may take before Cirrus stops the loop, so a model that keeps " +
            "searching forever cannot run up your bill.",
        value = settings.maxToolIterations.toFloat(),
        range = 1f..20f,
        steps = 18,
        format = { it.toInt().toString() },
        onChange = { scope.launch { repository.setMaxToolIterations(it.toInt()) } },
    )
}

/** GitHub, and the MCP servers that are the other way tools get here. */
/**
 * Skills: one switch, and the door to the library.
 *
 * The switch and the screen are separated because they answer different questions — "should the
 * model be told about any of these at all" is a setting, and "which ones do I have" is a list you
 * browse and edit, which is a screen.
 */
@Composable
private fun SkillsBody(container: AppContainer, settings: AppSettings, onOpenSkills: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository
    val skills by container.skillRepository.skills.collectAsState(emptyList())

    SwitchRow(
        title = "Use skills",
        subtitle = "Offer the model the skills you have installed",
        help = "A skill is a page of instructions for one kind of job, published to the public " +
            "library at skills.sh and installed here. The model sees only the names and one-line " +
            "descriptions until it picks one, so an installed skill costs almost nothing until " +
            "the moment it is the right one. Installing needs the network; using one does not. " +
            "Most of the library is written for coding agents with a terminal, so Cirrus tells " +
            "the model to take the method and ignore the parts that assume a development machine.",
        checked = settings.skillsEnabled,
        onCheckedChange = { scope.launch { repository.setSkillsEnabled(it) } },
    )

    NavigationRow(
        title = "Your skills",
        subtitle = skillsSubtitle(skills.size, skills.count { it.enabled }),
        help = "What is installed, with a switch each. Turning one off keeps it here but takes " +
            "it out of what the model is told about — which is the useful state for a skill you " +
            "want back next month. The same screen opens the library, where there are thousands " +
            "more.",
        onClick = onOpenSkills,
    )
}

/**
 * "Three installed, two in use".
 *
 * Both numbers, because they answer different questions and the second is the one that matters:
 * only the switched-on skills are named to the model, so a library of ten with two enabled behaves
 * exactly like a library of two.
 */
private fun skillsSubtitle(installed: Int, active: Int): String = when {
    installed == 0 -> "None installed — browse the library"
    active == installed && installed == 1 -> "1 skill, offered to the model"
    active == installed -> "$installed skills, all offered to the model"
    active == 0 -> "$installed installed · none switched on"
    else -> "$installed installed · $active offered to the model"
}

@Composable
private fun IntegrationsBody(
    container: AppContainer,
    settings: AppSettings,
    onOpenMcpServers: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository
    val servers by container.mcpServerRepository.current.collectAsState()
    val bindings by container.mcpServerRepository.bindings.collectAsState()

    SecretField(
        label = "Personal access token",
        fieldLabel = "GitHub token",
        replaceLabel = "Replace token",
        placeholder = "github_pat_… or ghp_…",
        saveLabel = "Save token",
        help = "A fine-grained or classic GitHub token. Classic tokens need the `repo` scope to " +
            "reach private repositories; a fine-grained token needs read access to Contents, " +
            "Issues and Pull requests, plus write on those you want the model to be able to " +
            "change. Create one at github.com/settings/tokens. Kept in Cirrus's own data folder — " +
            "there is no Keystore on the desktop, so this is a file readable by anything running " +
            "as you. It is sent to api.github.com and nowhere else; the MCP transports " +
            "deliberately use a client with no auth interceptor so an attached server can never " +
            "receive it.",
        isSet = settings.hasGitHubToken,
        footer = if (settings.hasGitHubToken) {
            "A token is stored, as a file in Cirrus's own data folder."
        } else {
            "Without a token the GitHub tools stay hidden from the model."
        },
        onSave = { scope.launch { repository.setGitHubToken(it) } },
        onClear = { scope.launch { repository.clearGitHubToken() } },
    )
    SwitchRow(
        title = "GitHub tools",
        subtitle = "Let the model read your repositories, issues and pull requests",
        help = "Adds tools the model can call mid-answer: list repositories, search code, read " +
            "files, and read issues and pull requests — private ones included, as far as your " +
            "token reaches. Requests go to api.github.com and nowhere else, and your Ollama key " +
            "is never sent there.",
        checked = settings.gitHubToolsEnabled,
        onCheckedChange = { scope.launch { repository.setGitHubToolsEnabled(it) } },
        enabled = settings.hasGitHubToken,
    )
    Text(
        text = "Write actions — opening issues, commenting, committing — are governed by one " +
            "switch for every integration, at Settings → Tools → Allow write actions.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    NavigationRow(
        title = "MCP servers",
        subtitle = mcpSubtitle(servers.size, bindings.size),
        help = "Attach a Model Context Protocol server and its tools become available to the " +
            "model alongside Cirrus's own. Each server is reached and asked what it offers before " +
            "it is saved, and its token is only ever sent to it. A server that does not annotate " +
            "its tools as read-only counts as writing, and offers nothing until writes are allowed.",
        onClick = onOpenMcpServers,
    )
}

/**
 * Spotify: a client ID, then a sign-in, then the switch.
 *
 * In that order because each step is useless without the one before it, and a switch that can be
 * turned on before there is an account behind it produces exactly the "on, but not set up yet"
 * state the catalogue had to grow a vocabulary for.
 *
 * The redirect URI gets a panel of its own with a copy button, rather than a mention in a
 * paragraph, because it is the one value that has to match on both ends character for character,
 * it is invisible from Spotify's side, and getting it wrong produces an error on Spotify's own
 * page that never mentions Cirrus at all. It is also longer and fiddlier here than on the phone —
 * a loopback address with a port, not a tidy `cirrus://` scheme — which makes it the one string on
 * this screen nobody should be retyping by eye.
 */
@Composable
private fun MusicBody(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository
    val clipboard = rememberClipboard()

    var clientId by remember(settings.spotifyClientId) { mutableStateOf(settings.spotifyClientId) }
    var signingIn by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Column {
        LabelWithHelp(
            label = "Client ID",
            help = "Spotify does not hand out a shared key for apps like this one, so Cirrus uses " +
                "yours. Create an app at developer.spotify.com/dashboard — it takes a minute and " +
                "costs nothing — add the redirect URI below to it, and paste the client ID here. " +
                "There is no client secret: the sign-in uses PKCE, which is designed for apps " +
                "that cannot keep one, and a desktop app is a zip file somebody can open.",
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Spotify client ID") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            shape = ContainerShape,
            modifier = Modifier.fillMaxWidth(),
        )
        if (clientId.trim() != settings.spotifyClientId) {
            Spacer(Modifier.height(8.dp))
            PillButton(
                label = "Save client ID",
                enabled = clientId.isNotBlank(),
                onClick = { scope.launch { repository.setSpotifyClientId(clientId) } },
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedPanel(shape = ContainerShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Redirect URI", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = SpotifyCredentials.REDIRECT_URI,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Add this to your Spotify app, exactly as written. The port is fixed " +
                        "because Spotify matches the redirect character for character.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                PillButton(
                    label = "Copy",
                    style = PillStyle.Secondary,
                    onClick = { clipboard.copy(SpotifyCredentials.REDIRECT_URI) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (settings.hasSpotifyAccount) {
                PillButton(
                    label = "Disconnect",
                    style = PillStyle.Ghost,
                    onClick = { scope.launch { container.spotifySession.signOut() } },
                )
            } else {
                PillButton(
                    label = "Connect Spotify",
                    enabled = settings.spotifyClientId.isNotBlank() && !signingIn,
                    onClick = {
                        scope.launch {
                            signingIn = true
                            result = null
                            result = container.spotifySession.signIn().fold(
                                onSuccess = { name -> "Connected as $name." },
                                onFailure = { error -> error.userMessage() },
                            )
                            signingIn = false
                        }
                    },
                )
            }
            if (signingIn) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(16.dp))
            }
        }
        Text(
            text = result ?: when {
                settings.spotifyClientId.isBlank() -> "Add a client ID first."
                signingIn -> "Finish the sign-in in your browser."
                !settings.hasSpotifyAccount ->
                    "Opens Spotify in your browser to sign in, and listens on " +
                        "${SpotifyCredentials.REDIRECT_URI} for just as long as that takes."
                settings.spotifyPremium ->
                    "Connected as ${settings.spotifyAccountName} · Premium."
                // The phone points a free account at `media_control`, which drives Android's own
                // media keys. There is no such tool here, so this says what is true rather than
                // naming a fallback this build does not have.
                else -> "Connected as ${settings.spotifyAccountName}. This account is not " +
                    "Premium, so Spotify will refuse playback control — searching, your library " +
                    "and what is playing all still work."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "Spotify tools",
            subtitle = "Search, playlists, what is playing, and playback control",
            help = "Offers the model five Spotify tools: searching the catalogue, reading your " +
                "playlists and saved music, seeing what is playing, controlling playback, and " +
                "editing playlists. Editing needs the write switch under Tools as well. These go " +
                "to api.spotify.com and nowhere else, and no other key of yours is ever sent " +
                "there.",
            checked = settings.spotifyEnabled,
            onCheckedChange = { scope.launch { repository.setSpotifyEnabled(it) } },
            enabled = settings.hasSpotifyAccount,
        )
    }
}

/**
 * Reading answers aloud, and which voice does it.
 *
 * The phone's counterpart also owns dictation. There is none here — `SpeechRecognizer` has no
 * desktop equivalent worth shipping and a bundled model would dwarf the app — so this section is
 * half the size of Android's, and says what it does rather than advertising a missing control.
 */
@Composable
private fun VoiceBody(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository

    SwitchRow(
        title = "Read answers aloud",
        subtitle = "Show a speak button under finished replies",
        help = "Adds a control that reads a reply out. What gets spoken is not the raw markdown: " +
            "code blocks are announced rather than dictated, links are read as \"link\", tables " +
            "are read as heading-and-value pairs, and maths is spoken as words — x squared, not " +
            "x two.",
        checked = settings.readAloudEnabled,
        onCheckedChange = { scope.launch { repository.setReadAloudEnabled(it) } },
    )
    if (settings.readAloudEnabled) {
        ReadAloudModeSelector(
            selected = settings.readAloudMode,
            onSelect = { scope.launch { repository.setReadAloudMode(it) } },
        )
        SpeechEngineSelector(
            selected = settings.speechEngine,
            onSelect = { scope.launch { repository.setSpeechEngine(it) } },
        )
        if (settings.speechEngine == SpeechEngine.ELEVENLABS) {
            SecretField(
                label = "ElevenLabs API key",
                fieldLabel = "ElevenLabs key",
                replaceLabel = "Replace key",
                placeholder = "sk_…",
                saveLabel = "Save key",
                help = "From elevenlabs.io/app/settings/api-keys. Sent only to " +
                    "api.elevenlabs.io — never to Ollama, and your Ollama key is never sent to " +
                    "ElevenLabs. The desktop asks for raw PCM rather than MP3, because the JVM " +
                    "decodes no MP3 at all and shipping a decoder to play a sentence would be a " +
                    "strange trade.",
                isSet = settings.hasElevenLabsKey,
                footer = if (settings.hasElevenLabsKey) {
                    ""
                } else {
                    "Without a key, read-aloud quietly uses the system voice instead."
                },
                onSave = { scope.launch { repository.setElevenLabsKey(it) } },
                onClear = { scope.launch { repository.clearElevenLabsKey() } },
            )
            if (settings.hasElevenLabsKey) {
                VoicePicker(container = container, settings = settings)
                ElevenLabsModelPicker(
                    selected = ElevenLabsModel.fromId(settings.elevenLabsModelId),
                    onSelect = { scope.launch { repository.setElevenLabsModel(it) } },
                )
            }
        }
    }
}

@Composable
private fun AppearanceBody(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository

    ThemeSelector(
        selected = settings.themeMode,
        onSelect = { scope.launch { repository.setThemeMode(it) } },
    )
    SwitchRow(
        title = "Render markdown",
        subtitle = "Turn off to read raw model output verbatim",
        help = "Formats replies: headings, lists, tables, and syntax-highlighted code blocks. Off " +
            "shows exactly the characters the model produced, asterisks and backticks included — " +
            "useful when you are debugging a prompt's formatting.",
        checked = settings.renderMarkdown,
        onCheckedChange = { scope.launch { repository.setRenderMarkdown(it) } },
    )
}

/** The two switches that are about watching Cirrus work rather than about what it does. */
@Composable
private fun DiagnosticsBody(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val repository = container.settingsRepository

    SwitchRow(
        title = "Show generation stats",
        subtitle = "Tokens per second, token counts and latency under each reply",
        help = "Adds a line under each reply with output speed, prompt and response token counts, " +
            "and time to the first token. The numbers come from the server's own timings, so they " +
            "measure the host, not your connection.",
        checked = settings.showStats,
        onCheckedChange = { scope.launch { repository.setShowStats(it) } },
    )
    SwitchRow(
        title = "Developer mode",
        subtitle = "Capture and display the exact request JSON for every turn",
        help = "Stores the exact JSON body sent for each turn and shows it under the reply — " +
            "system prompt, context window, options and tool definitions included. Nothing extra " +
            "is sent; it only records what already went out.",
        checked = settings.developerMode,
        onCheckedChange = { scope.launch { repository.setDeveloperMode(it) } },
    )
}

/**
 * What is stored, where it is stored, and the one irreversible button.
 *
 * The folder is named rather than merely alluded to, and there is a button that opens it, because
 * this is the build with no Keystore and no Room database — "on this computer only" is a claim the
 * user should be able to go and check. It is also the answer to what backing Cirrus up means,
 * which on Android is the system's problem and here is nobody's until somebody says which folder
 * to copy.
 */
@Composable
private fun DataBody(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "Cirrus $AppVersion for the desktop.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Conversations, memories, agents and keys are files in ${container.dataDir.path}. " +
            "Nothing is synced anywhere, and copying that folder is what backing Cirrus up means. " +
            "There is no Keystore on the desktop, so the keys in it are not encrypted at rest.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    PillButton(
        label = "Open data folder",
        style = PillStyle.Secondary,
        onClick = { revealInFileManager(container.dataDir) },
    )

    Spacer(Modifier.height(24.dp))
    Hairline()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { confirming = true }
            .padding(vertical = 14.dp),
    ) {
        Column {
            Text(
                text = "Delete all conversations",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Memories, agents and settings are left alone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete every conversation?") },
            text = {
                Text(
                    "Every thread and every message goes, including the ones agents wrote. " +
                        "Memories, agents and settings are left alone. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { container.conversationRepository.deleteAllConversations() }
                        confirming = false
                    },
                ) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The rows the sections are built from
// ─────────────────────────────────────────────────────────────────────────────

/** Caption above a field, with the question mark that explains it. */
@Composable
private fun LabelWithHelp(label: String, help: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        HelpBadge(title = label, text = help)
    }
}

/**
 * A setting with its own explanation.
 *
 * [subtitle] says what the switch does in a handful of words; [help] is the paragraph behind the
 * question mark, for the "…but what does that actually change?" question the subtitle cannot
 * answer without turning the list into an essay. The long page this screen replaced had no help
 * text at all, which is half of why it needed reading rather than scanning.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA

    HelpTooltip(title = title, text = help) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
            HelpBadge(title = title, text = help)
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

/** A row that leads somewhere else, rather than changing something in place. */
@Composable
private fun NavigationRow(
    title: String,
    subtitle: String,
    help: String,
    onClick: () -> Unit,
) {
    HelpTooltip(title = title, text = help) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HelpBadge(title = title, text = help)
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Says what attaching servers has actually bought you, which is a tool count, not a server count. */
private fun mcpSubtitle(serverCount: Int, toolCount: Int): String = when {
    serverCount == 0 -> "None attached"
    toolCount == 0 -> "$serverCount attached · no tools available"
    else -> {
        val servers = if (serverCount == 1) "1 server" else "$serverCount servers"
        val tools = if (toolCount == 1) "1 tool" else "$toolCount tools"
        "$servers · $tools offered to the model"
    }
}

/**
 * A bounded number, as a slider rather than a text field.
 *
 * The field this replaces let you type 999 into a setting whose useful range stops at 20, and gave
 * no sense of where in that range you were. A slider carries its own bounds, which for a setting
 * nobody visits twice is most of the documentation.
 */
@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    help: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA

    HelpTooltip(title = title, text = help) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    )
                }
                HelpBadge(title = title, text = help)
                Text(
                    text = format(value),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                )
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
                enabled = enabled,
            )
        }
    }
}

/**
 * A secret: entered, saved, replaced, removed — and never shown back.
 *
 * The same shape as the phone's three key fields, because it is the same job three times over and
 * the phone had learned things this build had not. The reveal toggle is the one that matters: a
 * key is pasted far more often than typed, and a masked field gives you no way to see that you
 * pasted the wrong one. What is revealed is only ever what you have just typed — the stored secret
 * is never read back into the field, on either build.
 *
 * [trailing] is where the API key hangs its Test button, so that the one field with something to
 * prove can prove it without a second row of controls.
 */
@Composable
private fun SecretField(
    label: String,
    fieldLabel: String,
    replaceLabel: String,
    placeholder: String,
    saveLabel: String,
    help: String,
    isSet: Boolean,
    footer: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    Column {
        LabelWithHelp(label = label, help = help)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(if (isSet) replaceLabel else fieldLabel) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (visible) "Hide" else "Show",
                    )
                }
            },
            shape = ContainerShape,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                label = saveLabel,
                enabled = draft.isNotBlank(),
                onClick = {
                    onSave(draft)
                    draft = ""
                },
            )
            trailing()
            if (isSet) {
                PillButton(label = "Remove", style = PillStyle.Ghost, onClick = onClear)
            }
        }

        if (footer.isNotBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ModelDropdownRow(
    selected: String,
    models: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val help = "What a new conversation starts on. The list comes from your host's own " +
        "catalogue, so it is empty until a connection has been tested."

    HelpTooltip(title = "Default model", text = help) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Default model", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "What a new conversation starts on",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HelpBadge(title = "Default model", text = help)
            Box {
                PillButton(
                    label = selected.ifBlank { "None selected" },
                    style = PillStyle.Secondary,
                    onClick = { open = true },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (models.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No models — test the connection first") },
                            onClick = { open = false },
                        )
                    }
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = { onSelect(model); open = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        LabelWithHelp(
            label = "Theme",
            help = "\"Follow system\" tracks this desktop's own light/dark setting. The other two " +
                "pin Cirrus regardless of what the rest of the machine is doing. The traffic " +
                "lights and the menu bar always follow the system, because they are the " +
                "operating system's furniture rather than Cirrus's.",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

/**
 * How much of an answer is spoken.
 *
 * Above the engine picker because it is the larger decision: which voice reads it matters only once
 * you have settled what it is reading.
 */
/**
 * When the shell's scratch files are cleared, if ever.
 *
 * A segmented row rather than a menu: four options, all short, and the choice is one people make
 * once — a dropdown would hide the fact that "never" is even available, which is the option most
 * people want and the one they would not think to go looking for.
 */
@Composable
private fun ScratchpadRetentionSelector(
    selected: ScratchpadRetention,
    onSelect: (ScratchpadRetention) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        LabelWithHelp(
            label = "Clear scratch files after",
            help = "Files written by shell commands live in a scratch folder per conversation, and " +
                "you can see them under Files in a chat's menu. This is when Cirrus " +
                "clears them for you. Never is the default: they are your work once you " +
                "can see them, and deleting somebody's work on a timer they did not set " +
                "is a poor thing to do quietly. Whatever this says, a scratchpad whose " +
                "conversation you have deleted goes with it, and a total size cap still " +
                "applies as a last resort so a runaway command cannot fill the disk.",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ScratchpadRetention.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index,
                        ScratchpadRetention.entries.size,
                    ),
                    label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ReadAloudModeSelector(selected: ReadAloudMode, onSelect: (ReadAloudMode) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        LabelWithHelp(
            label = "How much to read",
            help = "Speech is linear: a written answer you would skim in twenty seconds is six " +
                "minutes read out, and there is no way to skip the part you did not need. " +
                "A spoken summary is a minute or so on what the answer concluded and why, " +
                "written for the ear by the model you are already using. Short answers are " +
                "read in full either way, and the written answer never changes.",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReadAloudMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, ReadAloudMode.entries.size),
                    label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SpeechEngineSelector(selected: SpeechEngine, onSelect: (SpeechEngine) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        LabelWithHelp(
            label = "Voice engine",
            help = "The system voice drives whatever this desktop already has — say on macOS, " +
                "spd-say or espeak on Linux, SAPI on Windows — and needs no account. ElevenLabs " +
                "sounds markedly better and needs a key. Text is handed to the system engine on " +
                "stdin rather than on a command line, because an answer read aloud is arbitrary " +
                "model output and one containing a quote would otherwise become a command.",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SpeechEngine.entries.forEachIndexed { index, engine ->
                SegmentedButton(
                    selected = selected == engine,
                    onClick = { onSelect(engine) },
                    shape = SegmentedButtonDefaults.itemShape(index, SpeechEngine.entries.size),
                    label = { Text(engine.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Which of the account's voices reads an answer back.
 *
 * The list is fetched on demand rather than when the section appears: it is a network call against
 * somebody's paid account, and most openings of this page are on the way to something else. Until
 * it has been asked for, the row says "Load voices" and Cirrus uses whatever the account's default
 * is — which is a working state, not a broken one, and worth saying so.
 */
@Composable
private fun VoicePicker(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<ElevenLabsVoice>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.padding(top = 12.dp)) {
        LabelWithHelp(
            label = "Voice",
            help = "Every voice on your ElevenLabs account, including ones you cloned or made " +
                "yourself. Cirrus uses the default voice until you pick one.",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .clickable {
                    if (voices.isNotEmpty()) {
                        expanded = true
                    } else if (!loading) {
                        scope.launch {
                            loading = true
                            error = null
                            runCatching { container.elevenLabsClient.voices() }
                                .onSuccess {
                                    voices = it
                                    expanded = it.isNotEmpty()
                                    if (it.isEmpty()) error = "That account has no voices on it."
                                }
                                .onFailure { error = it.userMessage() }
                            loading = false
                        }
                    }
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = settings.elevenLabsVoiceName.ifBlank { "Default voice" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (voices.isEmpty()) "Load voices" else "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(voice.name)
                            voice.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        scope.launch {
                            container.settingsRepository.setElevenLabsVoice(voice.id, voice.name)
                        }
                        expanded = false
                    },
                )
            }
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * How the audio is made.
 *
 * Three options with a sentence each, laid out as a segmented row rather than hidden behind a
 * dropdown: the choice is a trade between latency and delivery, and a menu that shows one label at
 * a time is exactly the control that makes a trade invisible.
 */
@Composable
private fun ElevenLabsModelPicker(selected: ElevenLabsModel, onSelect: (ElevenLabsModel) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        LabelWithHelp(
            label = "Synthesis model",
            help = "How the audio is made. Flash starts talking soonest, which is what matters " +
                "when you are waiting to hear an answer; the others sound better but keep you " +
                "waiting longer before the first word.",
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ElevenLabsModel.entries.forEachIndexed { index, model ->
                SegmentedButton(
                    selected = model == selected,
                    onClick = { onSelect(model) },
                    shape = SegmentedButtonDefaults.itemShape(index, ElevenLabsModel.entries.size),
                    label = { Text(model.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Shows a folder in whatever this desktop calls its file manager.
 *
 * `Desktop.open` on a directory is the portable way in, and the two failures worth naming are both
 * "there is no desktop here" — a headless session, or a Linux box with no `xdg-open`. Neither is
 * worth an error dialog over a convenience button, so a failure simply does nothing rather than
 * interrupting somebody who can navigate to the path printed directly above it.
 */
private fun revealInFileManager(directory: File) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(directory)
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
