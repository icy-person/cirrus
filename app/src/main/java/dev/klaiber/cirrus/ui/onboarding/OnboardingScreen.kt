package dev.klaiber.cirrus.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.domain.model.AgentTemplate
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.components.PillStyle
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.theme.Pill

private const val KEYS_URL = "https://ollama.com/settings/keys"
private const val SIGN_UP_URL = "https://ollama.com"
private const val DOWNLOAD_URL = "https://ollama.com/download"
private const val GITHUB_TOKEN_URL = "https://github.com/settings/tokens"
private const val ELEVENLABS_URL = "https://elevenlabs.io/app/settings/api-keys"

/**
 * The first five minutes.
 *
 * Cirrus cannot do anything at all until it can reach a model, and nothing on a blank chat screen
 * says how. The wizard's job is not to collect settings — settings can be collected later — but to
 * end on a request that demonstrably worked, so the first thing anybody types has somewhere to go.
 *
 * It can be skipped from any step, and skipping counts as finished. A wizard that reappears until
 * it gets its way is worse than no wizard at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Step ${state.stepNumber} of ${state.stepCount}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                actions = {
                    TextButton(onClick = { viewModel.finish(onFinished) }) {
                        Text(if (state.step == OnboardingStep.DONE) "Close" else "Skip")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                Hairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.step != state.visibleSteps.first()) {
                        PillButton(
                            label = "Back",
                            onClick = viewModel::back,
                            style = PillStyle.Secondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    StepDots(current = state.stepNumber, total = state.stepCount)
                    Spacer(Modifier.weight(1f))
                    if (state.step == OnboardingStep.DONE) {
                        PillButton(
                            label = "Start chatting",
                            onClick = { viewModel.finish(onFinished) },
                        )
                    } else {
                        PillButton(
                            label = "Continue",
                            onClick = viewModel::next,
                            icon = Icons.AutoMirrored.Outlined.ArrowForward,
                            enabled = state.canAdvance,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.HOST -> HostStep(state, viewModel)
                OnboardingStep.KEY -> KeyStep(state, viewModel)
                OnboardingStep.MODEL -> ModelStep(state, viewModel)
                OnboardingStep.EXTRAS -> ExtrasStep(state, viewModel)
                OnboardingStep.DONE -> DoneStep(state, viewModel)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current - 1) 8.dp else 6.dp)
                    .clip(Pill)
                    .background(
                        if (index == current - 1) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

/** A heading and a paragraph, set the same way on every step so the wizard reads as one thing. */
@Composable
private fun StepHeader(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(52.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Cirrus",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "A chat client for Ollama and LM Studio.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))

    FeatureRow(
        icon = Icons.Outlined.Bolt,
        title = "It can do things, not just answer",
        body = "Web search, GitHub and any MCP server you attach — each one switched on by you.",
    )
    FeatureRow(
        icon = Icons.Outlined.Schedule,
        title = "It can run without you",
        body = "Agents are prompts on a schedule. Their answers wait for you, out of the way of " +
            "your own conversations.",
    )
    FeatureRow(
        icon = Icons.Outlined.CheckCircle,
        title = "Your keys stay here",
        body = "Every secret is encrypted with a key held by this device and sent only to the " +
            "service it belongs to.",
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Two questions and you are done.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(modifier = Modifier.padding(bottom = 18.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HostStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val uriHandler = LocalUriHandler.current

    StepHeader(
        title = "Choose your AI provider",
        body = "Connect Cirrus to Ollama or LM Studio. Your provider controls where model requests " +
            "go; GitHub, MCP, web and the rest of Cirrus remain available on top of it.",
    )

    ChoiceCard(
        selected = state.host == HostChoice.CLOUD,
        icon = Icons.Outlined.Cloud,
        title = "Ollama's hosted API",
        body = "Nothing to install. Needs a free account and an API key from ollama.com.",
        onClick = { viewModel.setHost(HostChoice.CLOUD) },
    )
    Spacer(Modifier.height(10.dp))
    ChoiceCard(
        selected = state.host == HostChoice.LOCAL_OLLAMA,
        icon = Icons.Outlined.Computer,
        title = "Ollama on my network",
        body = "Ollama running on your computer. Nothing leaves your network and no key is normally needed.",
        onClick = { viewModel.setHost(HostChoice.LOCAL_OLLAMA) },
    )
    Spacer(Modifier.height(10.dp))
    ChoiceCard(
        selected = state.host == HostChoice.LM_STUDIO,
        icon = Icons.Outlined.Computer,
        title = "LM Studio",
        body = "Use LM Studio's OpenAI-compatible local server, with model discovery and tool calling.",
        onClick = { viewModel.setHost(HostChoice.LM_STUDIO) },
    )

    if (state.host == HostChoice.LOCAL_OLLAMA) {
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = state.localUrl,
            onValueChange = viewModel::setLocalUrl,
            label = { Text("Address") },
            placeholder = { Text(DEFAULT_LOCAL_URL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = ContainerShape,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your phone cannot reach \"localhost\" — that would be the phone itself. Use " +
                "the computer's address on your network, and start Ollama with " +
                "OLLAMA_HOST=0.0.0.0 so it accepts connections from other devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LinkButton("Install Ollama") { uriHandler.openUri(DOWNLOAD_URL) }

        Spacer(Modifier.height(16.dp))
        ProbeRow(state = state, onTest = viewModel::testConnection)
    }

    if (state.host == HostChoice.LM_STUDIO) {
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = state.lmStudioUrl,
            onValueChange = viewModel::setLmStudioUrl,
            label = { Text("LM Studio server") },
            placeholder = { Text(DEFAULT_LM_STUDIO_URL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = ContainerShape,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "LM Studio uses its OpenAI-compatible API at /v1. On Android, enter the PC's " +
                "LAN address (for example http://192.168.1.10:1234/v1), not localhost. Enable the " +
                "server in LM Studio and allow LAN access if required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ProbeRow(state = state, onTest = viewModel::testConnection)
    }
 }

@Composable
private fun KeyStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val uriHandler = LocalUriHandler.current
    var visible by remember { mutableStateOf(false) }

    StepHeader(
        title = "Your Ollama key",
        body = "Sign in at ollama.com, create a key, and paste it here. It is stored on this " +
            "device only, encrypted with a key that never leaves the phone.",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkButton("Create a key") { uriHandler.openUri(KEYS_URL) }
        LinkButton("ollama.com") { uriHandler.openUri(SIGN_UP_URL) }
    }

    Spacer(Modifier.height(18.dp))
    OutlinedTextField(
        value = state.apiKey,
        onValueChange = viewModel::setApiKey,
        label = { Text(if (state.hasSavedKey) "Replace key" else "API key") },
        placeholder = { Text("paste your key") },
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
                    contentDescription = if (visible) "Hide key" else "Show key",
                )
            }
        },
        shape = ContainerShape,
        modifier = Modifier.fillMaxWidth(),
    )

    if (state.hasSavedKey && state.apiKey.isBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A key is already saved on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    ProbeRow(state = state, onTest = viewModel::testConnection)
}

/**
 * The one control that turns a form into a setup.
 *
 * Typing a key proves nothing; fetching the catalogue proves everything at once — the address, the
 * key, the network, and whether there is a single model to talk to at the other end.
 */
@Composable
private fun ProbeRow(state: OnboardingUiState, onTest: () -> Unit) {
    Column {
        PillButton(
            label = if (state.probe is ConnectionProbe.Reached) "Test again" else "Test connection",
            onClick = onTest,
            style = PillStyle.Secondary,
            enabled = state.probe !is ConnectionProbe.Trying,
        )
        Spacer(Modifier.height(12.dp))
        when (val probe = state.probe) {
            is ConnectionProbe.Untried -> Unit
            is ConnectionProbe.Trying -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "Finding models…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is ConnectionProbe.Reached -> StatusPanel(
                ok = true,
                title = "Connected",
                body = "Found ${probe.modelCount} model${if (probe.modelCount == 1) "" else "s"}.",
            )
            is ConnectionProbe.Failed -> StatusPanel(
                ok = false,
                title = "Connection failed",
                body = probe.message,
            )
        }
    }
}

@Composable
private fun StatusPanel(ok: Boolean, title: String, body: String) {
    OutlinedPanel {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeader(
        title = "Pick a model",
        body = "Cirrus found these models at your provider. You can change this later for each " +
            "conversation or agent.",
    )

    if (state.models.isEmpty()) {
        StatusPanel(
            ok = false,
            title = "No models returned",
            body = "You can continue and choose a model later in Settings.",
        )
    } else {
        state.models.forEach { model ->
            ChoiceCard(
                selected = state.selectedModel == model.name,
                icon = Icons.Outlined.Computer,
                title = model.name,
                body = model.parameterSize?.let { "${it} parameters" } ?: "Available model",
                onClick = { viewModel.selectModel(model.name) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ExtrasStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val uriHandler = LocalUriHandler.current

    StepHeader(
        title = "Optional extras",
        body = "GitHub and ElevenLabs are optional. You can skip both and enable them later in " +
            "Settings.",
    )

    OutlinedPanel {
        Column {
            Text("GitHub", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (state.gitHubSaved) {
                    "A GitHub token is already saved."
                } else {
                    "Give the model access to repositories, issues and pull requests."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinkButton("Create GitHub token") { uriHandler.openUri(GITHUB_TOKEN_URL) }
            Spacer(Modifier.height(10.dp))
            SecretField(
                value = state.gitHubToken,
                onValueChange = viewModel::setGitHubToken,
                label = "GitHub token",
                placeholder = "github_pat_…",
                saved = state.gitHubSaved,
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    OutlinedPanel {
        Column {
            Text("ElevenLabs", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (state.elevenLabsSaved) {
                    "An ElevenLabs key is already saved."
                } else {
                    "Optional cloud voice synthesis for read-aloud."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinkButton("Get an ElevenLabs key") { uriHandler.openUri(ELEVENLABS_URL) }
            Spacer(Modifier.height(10.dp))
            SecretField(
                value = state.elevenLabsKey,
                onValueChange = viewModel::setElevenLabsKey,
                label = "ElevenLabs API key",
                placeholder = "paste your key",
                saved = state.elevenLabsSaved,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    NotificationsCard()
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    saved: Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (saved && value.isBlank()) "Saved $label" else label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                )
            }
        },
        shape = ContainerShape,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NotificationsCard() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        OutlinedPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Notifications, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Notifications", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Allow notifications so scheduled agents can tell you when they finish.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Allow")
                }
            }
        }
    }
}

@Composable
private fun DoneStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeader(
        title = "You are ready",
        body = "Cirrus is connected. Everything else can be changed later from Settings.",
    )

    StatusPanel(
        ok = true,
        title = "${state.models.size} model${if (state.models.size == 1) "" else "s"} available",
        body = state.selectedModel.takeIf { it.isNotBlank() }?.let { "Default: $it" }
            ?: "Choose a model from Settings when you are ready.",
    )

    Spacer(Modifier.height(16.dp))
    Text(
        text = "Optional integrations",
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    if (state.gitHubSaved) SavedRow("GitHub token saved")
    if (state.elevenLabsSaved) SavedRow("ElevenLabs key saved")
    if (!state.gitHubSaved && !state.elevenLabsSaved) {
        Text(
            "Nothing else is required. MCP servers, memory, skills and tools are available in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(20.dp))
    Text(
        text = "Provider: ${if (state.isLmStudio) "LM Studio" else if (state.isCloud) "Ollama hosted" else "Local Ollama"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SavedRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ChoiceCard(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LargeContainerShape),
        tonalElevation = if (selected) 2.dp else 0.dp,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
