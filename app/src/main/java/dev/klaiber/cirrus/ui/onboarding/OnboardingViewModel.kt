package dev.klaiber.cirrus.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.data.remote.ApiCredentials
import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.data.repository.ModelRepository
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.agents.AgentScheduler
import dev.klaiber.cirrus.domain.model.AgentTemplate
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.domain.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where someone's models live. The rest of the wizard is shaped by this one answer. */
enum class HostChoice {
    CLOUD,
    LOCAL,
    LM_STUDIO,
}

enum class OnboardingStep {
    WELCOME,
    HOST,
    KEY,
    MODEL,
    EXTRAS,
    DONE,
}

/** The result of actually trying the connection, rather than of having typed something. */
sealed interface ConnectionProbe {

    data object Untried : ConnectionProbe

    data object Trying : ConnectionProbe

    data class Reached(
        val modelCount: Int,
    ) : ConnectionProbe

    data class Failed(
        val message: String,
    ) : ConnectionProbe
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,

    val host: HostChoice = HostChoice.CLOUD,

    /**
     * Ollama local server address.
     *
     * Example:
     * http://192.168.1.10:11434
     */
    val localUrl: String = DEFAULT_LOCAL_URL,

    /**
     * LM Studio server address shown to the user.
     *
     * IMPORTANT:
     * This is intentionally only `host:port`.
     *
     * Example:
     * 192.168.1.10:1234
     *
     * `/v1` and the scheme are added internally by ApiCredentials.
     */
    val lmStudioAddress: String = "",

    val apiKey: String = "",
    val hasSavedKey: Boolean = false,

    val probe: ConnectionProbe = ConnectionProbe.Untried,

    val models: List<ModelInfo> = emptyList(),
    val selectedModel: String = "",

    val gitHubToken: String = "",
    val gitHubSaved: Boolean = false,

    val elevenLabsKey: String = "",
    val elevenLabsSaved: Boolean = false,

    val starterTemplate: AgentTemplate? = null,
) {
    val isCloud: Boolean
        get() = host == HostChoice.CLOUD

    val isLmStudio: Boolean
        get() = host == HostChoice.LM_STUDIO

    /**
     * Whether the current step can be advanced.
     *
     * The connection itself is not mandatory because the wizard can be skipped,
     * but an empty local/LM Studio address should not be persisted as a backend URL.
     */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.HOST ->
                when {
                    isCloud -> true
                    isLmStudio -> lmStudioAddress.trim().isNotEmpty()
                    else -> localUrl.trim().isNotEmpty()
                }

            OnboardingStep.KEY ->
                !isCloud || hasSavedKey || apiKey.isNotBlank()

            OnboardingStep.MODEL ->
                selectedModel.isNotBlank() || models.isEmpty()

            else ->
                true
        }

    val stepNumber: Int
        get() = visibleSteps.indexOf(step) + 1

    val stepCount: Int
        get() = visibleSteps.size

    /**
     * A local host needs no API key, so that step does not exist for local backends.
     */
    val visibleSteps: List<OnboardingStep>
        get() = OnboardingStep.entries.filter {
            it != OnboardingStep.KEY || isCloud
        }
}

/**
 * The first-run wizard.
 *
 * Cirrus can connect to:
 *
 * 1. Ollama's hosted API
 * 2. A local Ollama server
 * 3. A local LM Studio server
 *
 * LM Studio uses an OpenAI-compatible `/v1` API internally. The UI intentionally
 * asks only for the host and port and lets the transport layer construct the
 * final `/v1` URL.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val agents: AgentRepository,
    private val scheduler: AgentScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OnboardingUiState(),
    )

    val uiState: StateFlow<OnboardingUiState> =
        _uiState.asStateFlow()

    init {
        val current = settings.current.value
        val currentHost = detectHost(current.baseUrl)

        _uiState.update { state ->
            state.copy(
                host = currentHost,

                localUrl = when (currentHost) {
                    HostChoice.LOCAL ->
                        current.baseUrl

                    else ->
                        DEFAULT_LOCAL_URL
                },

                lmStudioAddress = when (currentHost) {
                    HostChoice.LM_STUDIO ->
                        ApiCredentials.lmStudioAddressFromUrl(
                            current.baseUrl,
                        )

                    else ->
                        ""
                },

                hasSavedKey = current.hasApiKey,
                selectedModel = current.defaultModel,
                gitHubSaved = current.hasGitHubToken,
                elevenLabsSaved = current.hasElevenLabsKey,
                models = models.models.value,
            )
        }
    }

    /**
     * Detects which backend is currently configured.
     *
     * `ollama.com` identifies Ollama Cloud.
     * `/v1` identifies an OpenAI-compatible local endpoint such as LM Studio.
     */
    private fun detectHost(
        baseUrl: String,
    ): HostChoice {
        val normalized = baseUrl.trim()

        return when {
            isOllamaCloudUrl(normalized) ->
                HostChoice.CLOUD

            normalized
                .trimEnd('/')
                .endsWith("/v1", ignoreCase = true) ->
                HostChoice.LM_STUDIO

            else ->
                HostChoice.LOCAL
        }
    }

    private fun isOllamaCloudUrl(
        baseUrl: String,
    ): Boolean {
        return runCatching {
            val uri = java.net.URI(baseUrl)

            uri.host?.equals(
                OLLAMA_HOST,
                ignoreCase = true,
            ) == true
        }.getOrElse {
            baseUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .equals(
                    OLLAMA_HOST,
                    ignoreCase = true,
                )
        }
    }

    /**
     * Changes the selected backend.
     *
     * Each backend keeps its own field so Ollama and LM Studio values
     * never overwrite each other.
     */
    fun setHost(
        choice: HostChoice,
    ) {
        _uiState.update { state ->
            state.copy(
                host = choice,
                probe = ConnectionProbe.Untried,
            )
        }
    }

    fun setLocalUrl(
        url: String,
    ) {
        _uiState.update {
            it.copy(
                localUrl = url,
                probe = ConnectionProbe.Untried,
            )
        }
    }

    fun setLmStudioAddress(
        address: String,
    ) {
        _uiState.update {
            it.copy(
                lmStudioAddress = address,
                probe = ConnectionProbe.Untried,
            )
        }
    }

    fun setApiKey(
        key: String,
    ) {
        _uiState.update {
            it.copy(
                apiKey = key,
                probe = ConnectionProbe.Untried,
            )
        }
    }

    fun setGitHubToken(
        token: String,
    ) {
        _uiState.update {
            it.copy(
                gitHubToken = token,
            )
        }
    }

    fun setElevenLabsKey(
        key: String,
    ) {
        _uiState.update {
            it.copy(
                elevenLabsKey = key,
            )
        }
    }

    fun selectModel(
        name: String,
    ) {
        _uiState.update {
            it.copy(
                selectedModel = name,
            )
        }

        viewModelScope.launch {
            settings.setDefaultModel(name)
        }
    }

    fun chooseStarter(
        template: AgentTemplate?,
    ) {
        _uiState.update {
            it.copy(
                starterTemplate =
                    if (it.starterTemplate == template) {
                        null
                    } else {
                        template
                    },
            )
        }
    }

    /**
     * Builds the actual URL consumed by the HTTP layer.
     *
     * UI values:
     *
     * Ollama:
     * http://192.168.1.10:11434
     *
     * LM Studio:
     * 192.168.1.10:1234
     *
     * Stored LM Studio URL:
     * http://192.168.1.10:1234/v1
     */
    private fun buildBaseUrl(
        state: OnboardingUiState,
    ): String {
        return when {
            state.isCloud ->
                ApiCredentials.DEFAULT_BASE_URL

            state.isLmStudio ->
                ApiCredentials.normalizeLmStudioAddress(
                    state.lmStudioAddress,
                )

            else ->
                state.localUrl.trim()
        }
    }

    /**
     * Saves the current backend and probes it by refreshing the model list.
     */
    fun testConnection() {
        val state = _uiState.value

        _uiState.update {
            it.copy(
                probe = ConnectionProbe.Trying,
            )
        }

        viewModelScope.launch {
            val url = buildBaseUrl(state)

            if (url.isBlank()) {
                _uiState.update {
                    it.copy(
                        probe = ConnectionProbe.Failed(
                            connectionAddressError(state),
                        ),
                    )
                }
                return@launch
            }

            settings.setBaseUrl(url)

            if (state.apiKey.isNotBlank()) {
                settings.setApiKey(state.apiKey)
            }

            models.refresh().fold(
                onSuccess = { list ->
                    _uiState.update { current ->
                        current.copy(
                            probe = ConnectionProbe.Reached(
                                list.size,
                            ),
                            models = list,
                            apiKey = "",
                            hasSavedKey =
                                current.hasSavedKey ||
                                    state.apiKey.isNotBlank(),

                            selectedModel =
                                current.selectedModel.ifBlank {
                                    list.firstOrNull()
                                        ?.name
                                        .orEmpty()
                                },
                        )
                    }

                    _uiState.value.selectedModel
                        .takeIf { it.isNotBlank() }
                        ?.let { model ->
                            settings.setDefaultModel(model)
                        }
                },

                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            probe = ConnectionProbe.Failed(
                                error.userMessage(),
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun connectionAddressError(
        state: OnboardingUiState,
    ): String {
        return when {
            state.isLmStudio ->
                "Enter the LM Studio server address, for example 192.168.1.10:1234."

            state.isCloud ->
                "Ollama Cloud is ready for an API key."

            else ->
                "Enter the Ollama server address."
        }
    }

    fun saveGitHubToken() {
        val token = _uiState.value.gitHubToken.trim()

        if (token.isEmpty()) {
            return
        }

        viewModelScope.launch {
            settings.setGitHubToken(token)
            settings.setGitHubToolsEnabled(true)

            _uiState.update {
                it.copy(
                    gitHubToken = "",
                    gitHubSaved = true,
                )
            }
        }
    }

    fun saveElevenLabsKey() {
        val key = _uiState.value.elevenLabsKey.trim()

        if (key.isEmpty()) {
            return
        }

        viewModelScope.launch {
            settings.setElevenLabsKey(key)

            _uiState.update {
                it.copy(
                    elevenLabsKey = "",
                    elevenLabsSaved = true,
                )
            }
        }
    }

    /**
     * Moves to the next step and persists the current backend configuration.
     */
    fun next() {
        val state = _uiState.value

        when (state.step) {

            OnboardingStep.HOST -> {
                val url = buildBaseUrl(state)

                if (url.isBlank()) {
                    return
                }

                viewModelScope.launch {
                    settings.setBaseUrl(url)
                }
            }

            OnboardingStep.KEY -> {
                if (state.apiKey.isNotBlank()) {
                    val key = state.apiKey

                    _uiState.update {
                        it.copy(
                            apiKey = "",
                            hasSavedKey = true,
                        )
                    }

                    viewModelScope.launch {
                        settings.setApiKey(key)
                    }
                }
            }

            OnboardingStep.EXTRAS -> {
                saveGitHubToken()
                saveElevenLabsKey()
            }

            else -> Unit
        }

        advance(1)
    }

    fun back() {
        advance(-1)
    }

    private fun advance(
        delta: Int,
    ) {
        _uiState.update { state ->
            val steps = state.visibleSteps
            val index = steps.indexOf(state.step)

            state.copy(
                step = steps.getOrElse(index + delta) {
                    state.step
                }
            )
        }
    }

    /**
     * Finishes the wizard.
     *
     * IMPORTANT:
     * Even when the wizard is skipped, the currently entered backend
     * configuration is persisted. This prevents a valid LM Studio address
     * from being silently lost.
     */
    fun finish(
        onDone: () -> Unit,
    ) {
        val state = _uiState.value
        val template = state.starterTemplate

        viewModelScope.launch {
            val url = buildBaseUrl(state)

            if (url.isNotBlank()) {
                settings.setBaseUrl(url)
            }

            if (state.apiKey.isNotBlank()) {
                settings.setApiKey(
                    state.apiKey.trim(),
                )
            }

            if (state.gitHubToken.isNotBlank()) {
                settings.setGitHubToken(
                    state.gitHubToken.trim(),
                )

                settings.setGitHubToolsEnabled(true)
            }

            if (state.elevenLabsKey.isNotBlank()) {
                settings.setElevenLabsKey(
                    state.elevenLabsKey.trim(),
                )
            }

            if (template != null) {
                val agent = agents.create(
                    name = template.name,
                    prompt = template.prompt,
                    model = null,
                    minuteOfDay = template.minuteOfDay,
                    days = template.days,
                    toolsEnabled = template.toolsEnabled,
                    notifyOnFinish = true,
                )

                scheduler.schedule(agent)
            }

            settings.setOnboardingCompleted(true)

            onDone()
        }
    }

    private companion object {
        const val OLLAMA_HOST = "ollama.com"
    }
}

/**
 * Default Ollama address.
 *
 * Android users normally need to replace this with the IP address of
 * the computer running Ollama.
 */
const val DEFAULT_LOCAL_URL = "http://192.168.1.10:11434"
