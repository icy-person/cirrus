package dev.klaiber.cirrus.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.data.remote.elevenlabs.ElevenLabsVoice
import dev.klaiber.cirrus.domain.model.ElevenLabsModel
import dev.klaiber.cirrus.domain.model.ReadAloudMode
import dev.klaiber.cirrus.domain.model.ScratchpadRetention
import dev.klaiber.cirrus.domain.model.SpeechEngine
import dev.klaiber.cirrus.domain.model.ThemeMode
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.klaiber.cirrus.ui.components.SectionLabel
import dev.klaiber.cirrus.ui.components.HelpBadge
import dev.klaiber.cirrus.ui.components.HelpTooltip
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.theme.Pill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    section: SettingsSection,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
        ) {
            when (section) {
                SettingsSection.CONNECTION -> {

                ApiKeyField(
                    hasKey = state.settings.hasApiKey,
                    isLmStudio = state.settings.baseUrl.trimEnd('/').endsWith("/v1", ignoreCase = true),
                    status = state.connectionStatus,
                    onSave = viewModel::saveApiKey,
                    onClear = viewModel::clearApiKey,
                    onTest = viewModel::testConnection,
                )

                Spacer(Modifier.height(12.dp))
                BaseUrlField(
                    baseUrl = state.settings.baseUrl,
                    onSave = viewModel::setBaseUrl,
                )

            
                }

                SettingsSection.GENERATION -> {

                ModelDropdownRow(
                    selected = state.settings.defaultModel,
                    models = state.models.map { it.name },
                    onSelect = viewModel::setDefaultModel,
                )

                Spacer(Modifier.height(12.dp))
                GenerationDefaults(
                    params = state.settings.defaultParams,
                    onSave = viewModel::setDefaultParams,
                )

                Spacer(Modifier.height(12.dp))
                ToolDefaults(
                    enabled = state.settings.toolsEnabledByDefault,
                    onChange = viewModel::setToolsEnabledByDefault,
                    maxIterations = state.settings.maxToolIterations,
                    onMaxIterationsChange = viewModel::setMaxToolIterations,
                )

                Spacer(Modifier.height(12.dp))
                contextLimit = state.settings.contextMessageLimit,
                onContextLimitChange = viewModel::setContextMessageLimit,
                )

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = "Show statistics",
                    checked = state.settings.showStats,
                    onCheckedChange = viewModel::setShowStats,
                )

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = "Render Markdown",
                    checked = state.settings.renderMarkdown,
                    onCheckedChange = viewModel::setRenderMarkdown,
                )

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = "Send on Enter",
                    checked = state.settings.sendOnEnter,
                    onCheckedChange = viewModel::setSendOnEnter,
                )

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = "Show starter prompts",
                    checked = state.settings.showStarterPrompts,
                    onCheckedChange = viewModel::setShowStarterPrompts,
                )

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = "Developer mode",
                    checked = state.settings.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                )
                }

                SettingsSection.INTEGRATIONS -> {
                IntegrationSummary(
                    github = state.settings.hasGitHubToken,
                    elevenLabs = state.settings.hasElevenLabsKey,
                    spotify = state.settings.hasSpotifyAccount,
                    mcpServers = state.mcpServerCount,
                    mcpTools = state.mcpToolCount,
                    skills = state.activeSkillCount,
                    memories = state.memoryCount,
                    agents = state.agentCount,
                )
                }

                SettingsSection.VOICE -> {
                VoiceSection(
                    settings = state.settings,
                    voices = state.voices,
                    voiceStatus = state.voiceStatus,
                    onSaveKey = viewModel::saveElevenLabsKey,
                    onClearKey = viewModel::clearElevenLabsKey,
                    onLoadVoices = viewModel::loadElevenLabsVoices,
                    onSetVoice = viewModel::setElevenLabsVoice,
                    onSetModel = viewModel::setElevenLabsModel,
                    onSetMode = viewModel::setReadAloudMode,
                    onSetEngine = viewModel::setSpeechEngine,
                )
                }

                SettingsSection.MCP -> {
                McpSection(
                    serverCount = state.mcpServerCount,
                    toolCount = state.mcpToolCount,
                )
                }

                SettingsSection.ABOUT -> {
                AboutSection(versionName = state.versionName)
                }
            }
        }
    }
}

private fun ConnectionSummary(hasKey: Boolean, host: String, model: String, onClick: () -> Unit) {
    val connected = hasKey || !host.contains("ollama.com")
}

// ...
