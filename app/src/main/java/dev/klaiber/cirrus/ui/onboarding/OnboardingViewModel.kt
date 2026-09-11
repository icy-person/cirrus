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
enum class HostChoice { CLOUD, LOCAL_OLLAMA, LM_STUDIO }

enum class OnboardingStep { WELCOME, HOST, KEY, MODEL, EXTRAS, DONE }

/** The result of actually trying the connection, rather than of having typed something. */
sealed interface ConnectionProbe {
    data object Untried : ConnectionProbe

    data object Trying : ConnectionProbe

    data class Reached(val modelCount: Int) : ConnectionProbe

    data class Failed(val message: String) : ConnectionProbe
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val host: HostChoice = HostChoice.CLOUD,
    val localUrl: String = DEFAULT_LOCAL_URL,
    val lmStudioUrl: String = DEFAULT_LM_STUDIO_URL,
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
    val isCloud: Boolean get() = host == HostChoice.CLOUD
    val isLmStudio: Boolean get() = host == HostChoice.LM_STUDIO
    val needsApiKey: Boolean get() = isCloud

    /** The wizard never blocks, but it can say when a step has not been finished properly. */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.KEY -> !needsApiKey || hasSavedKey || apiKey.isNotBlank()
            OnboardingStep.MODEL -> selectedModel.isNotBlank() || models.isEmpty()
            else -> true
        }

    val stepNumber: Int get() = visibleSteps.indexOf(step) + 1

    val stepCount: Int get() = visibleSteps.size

    /** A local host needs no API key, so that step is not merely skipped — it never existed. */
    val visibleSteps: List<OnboardingStep>
        get() = OnboardingStep.entries.filter { it != OnboardingStep.KEY || needsApiKey }
}

/**
 * The first-run wizard.
 *
 * Cirrus is useless until it can reach a model, and the two ways to arrange that — a key from
 * ollama.com, or a machine on your network running Ollama — are both perfectly ordinary and neither
 * is guessable from a blank chat screen. The wizard's real job is not collecting settings; it is
 * proving, before it lets go, that a request actually succeeds. Everything else it asks for is
 * optional and says so.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    private val agents: AgentRepository,
    private val scheduler: AgentScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        // Somebody re-running the wizard from settings has answers already; show them their own.
        val current = settings.current.value
        _uiState.update { state ->
            state.copy(
                host = when {
                    current.baseUrl.contains(OLLAMA_HOST, ignoreCase = true) -> HostChoice.CLOUD
                    current.baseUrl.trimEnd('/').endsWith("/v1", ignoreCase = true) -> HostChoice.LM_STUDIO
                    else -> HostChoice.LOCAL_OLLAMA
                },
                localUrl = current.baseUrl.takeIf { !it.contains(OLLAMA_HOST) && !it.trimEnd('/').endsWith("/v1", ignoreCase = true) } ?: DEFAULT_LOCAL_URL,
                lmStudioUrl = current.baseUrl.takeIf { it.trimEnd('/').endsWith("/v1", ignoreCase = true) } ?: DEFAULT_LM_STUDIO_URL,
                hasSavedKey = current.hasApiKey,
                selectedModel = current.defaultModel,
                gitHubSaved = current.hasGitHubToken,
                elevenLabsSaved = current.hasElevenLabsKey,
                models = models.models.value,
            )
        }
    }

    fun setHost(choice: HostChoice) = _uiState.update {
        it.copy(host = choice, probe = ConnectionProbe.Untried)
    }

    fun setLocalUrl(url: String) = _uiState.update {
        it.copy(localUrl = url, probe = ConnectionProbe.Untried)
    }

    fun setLmStudioUrl(url: String) = _uiState.update {
        it.copy(lmStudioUrl = url, probe = ConnectionProbe.Untried)
    }

    fun setApiKey(key: String) = _uiState.update {
        it.copy(apiKey = key, probe = ConnectionProbe.Untried)
    }

    fun setGitHubToken(token: String) = _uiState.update { it.copy(gitHubToken = token) }

    fun setElevenLabsKey(key: String) = _uiState.update { it.copy(elevenLabsKey = key) }

    fun selectModel(name: String) {
        _uiState.update { it.copy(selectedModel = name) }
        viewModelScope.launch { settings.setDefaultModel(name) }
    }

    fun chooseStarter(template: AgentTemplate?) = _uiState.update {
        it.copy(starterTemplate = if (it.starterTemplate == template) null else template)
    }

    /**
     * Saves what has been typed and then actually asks the host for its models.
     *
     * Saving first is deliberate: the credential holder the HTTP layer reads is fed from the same
     * store, so a probe against unsaved values would test something other than what the app will
     * do afterwards.
     */
    fun testConnection() {
        val state = _uiState.value
        _uiState.update { it.copy(probe = ConnectionProbe.Trying) }
        viewModelScope.launch {
            val url = when (state.host) {
                HostChoice.CLOUD -> ApiCredentials.DEFAULT_BASE_URL
                HostChoice.LOCAL_OLLAMA -> state.localUrl.trim()
                HostChoice.LM_STUDIO -> state.lmStudioUrl.trim()
            }
            settings.setBaseUrl(url)
            if (state.apiKey.isNotBlank()) settings.setApiKey(state.apiKey)

            models.refresh().fold(
                onSuccess = { list ->
                    _uiState.update { current ->
                        current.copy(
                            probe = ConnectionProbe.Reached(list.size),
                            models = list,
                            apiKey = "",
                            hasSavedKey = current.hasSavedKey || state.apiKey.isNotBlank(),
                            // Nothing chosen yet, and exactly one model, is not a choice.
                            selectedModel = current.selectedModel.ifBlank {
                                list.firstOrNull()?.name.orEmpty()
                            },
                        )
                    }
                    _uiState.value.selectedModel
                        .takeIf { it.isNotBlank() }
                        ?.let { settings.setDefaultModel(it) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(probe = ConnectionProbe.Failed(error.userMessage())) }
                },
            )
        }
    }

    fun saveGitHubToken() {
        val token = _uiState.value.gitHubToken.trim()
        if (token.isEmpty()) return
        viewModelScope.launch {
            settings.setGitHubToken(token)
            // A token nobody offers to the model is a token that does nothing.
            settings.setGitHubToolsEnabled(true)
            _uiState.update { it.copy(gitHubToken = "", gitHubSaved = true) }
        }
    }

    fun saveElevenLabsKey() {
        val key = _uiState.value.elevenLabsKey.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            settings.setElevenLabsKey(key)
            _uiState.update { it.copy(elevenLabsKey = "", elevenLabsSaved = true) }
        }
    }

    /**
     * Moves on, saving whatever the step collected.
     *
     * Testing the connection also saves, but nobody is obliged to test: a key typed carefully and
     * then lost because the user pressed Continue instead of "Test connection" is the single most
     * annoying way for a setup wizard to fail.
     */
    fun next() {
        val state = _uiState.value
        when (state.step) {
            OnboardingStep.HOST -> viewModelScope.launch {
                settings.setBaseUrl(
                    when (state.host) {
                        HostChoice.CLOUD -> ApiCredentials.DEFAULT_BASE_URL
                        HostChoice.LOCAL_OLLAMA -> state.localUrl.trim()
                        HostChoice.LM_STUDIO -> state.lmStudioUrl.trim()
                    },
                )
            }
            OnboardingStep.KEY -> if (state.apiKey.isNotBlank()) {
                val key = state.apiKey
                _uiState.update { it.copy(apiKey = "", hasSavedKey = true) }
                viewModelScope.launch { settings.setApiKey(key) }
            }
            OnboardingStep.EXTRAS -> {
                saveGitHubToken()
                saveElevenLabsKey()
            }
            else -> Unit
        }
        advance(1)
    }

    fun back() = advance(-1)

    private fun advance(delta: Int) = _uiState.update { state ->
        val steps = state.visibleSteps
        val at = steps.indexOf(state.step)
        state.copy(step = steps.getOrElse(at + delta) { state.step })
    }

    /**
     * Closes the wizard.
     *
     * Marked done even when it was skipped: a wizard that reappears until it gets its way is worse
     * than no wizard, and everything it asks for is reachable from settings afterwards.
     */
    fun finish(onDone: () -> Unit) {
        val state = _uiState.value
        val template = state.starterTemplate
        viewModelScope.launch {
            // Skipping should not throw away something that was typed. "I pasted the key and it
            // did not save" is indistinguishable from a broken key from where the user is sitting.
            if (state.apiKey.isNotBlank()) settings.setApiKey(state.apiKey)
            if (state.gitHubToken.isNotBlank()) {
                settings.setGitHubToken(state.gitHubToken.trim())
                settings.setGitHubToolsEnabled(true)
            }
            if (state.elevenLabsKey.isNotBlank()) settings.setElevenLabsKey(state.elevenLabsKey.trim())

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

/** A phone cannot reach `localhost`; the machine running Ollama has an address on the network. */
const val DEFAULT_LOCAL_URL = "http://192.168.1.10:11434"
const val DEFAULT_LM_STUDIO_URL = "http://192.168.1.10:1234/v1"
