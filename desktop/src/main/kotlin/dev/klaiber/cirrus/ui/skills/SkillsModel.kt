package dev.klaiber.cirrus.ui.skills

import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.data.repository.SkillRepository
import dev.klaiber.cirrus.domain.model.Skill
import dev.klaiber.cirrus.domain.model.SkillListing
import dev.klaiber.cirrus.domain.model.SkillTopic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The installed library, as the Skills screen sees it. */
data class SkillsUiState(
    val skills: List<Skill> = emptyList(),
    val enabledGlobally: Boolean = true,
) {
    val activeCount: Int get() = skills.count { it.enabled }
}

/**
 * The Explore page's state.
 *
 * [topic] and [query] are two views of the same thing — a topic chip *is* a query — but they are
 * held separately because the chip has to stay lit while its results are on screen, and deriving
 * that from the text would light it again the moment somebody typed the same word.
 */
data class ExploreUiState(
    val query: String = "",
    val topic: SkillTopic? = SkillTopic.Default.first(),
    val results: List<SkillListing> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    /** Ids currently being fetched, so each card can show its own spinner. */
    val installing: Set<String> = emptySet(),
    val installed: List<Skill> = emptyList(),
    /** Non-null while the preview panel is open. */
    val preview: SkillPreview? = null,
) {
    val topics: List<SkillTopic> = SkillTopic.Default
}

/** What installing would actually add, fetched before committing to it. */
data class SkillPreview(
    val listing: SkillListing,
    val skill: Skill? = null,
    val error: String? = null,
) {
    val isLoading: Boolean get() = skill == null && error == null
}

/**
 * The skills library and the registry behind it.
 *
 * A plain class remembered in the composition, like every other screen model here: a window has no
 * back stack, so the equivalent lifetime to Android's ViewModel is the composition, and the scope
 * comes in rather than being invented.
 *
 * The search is debounced rather than fired per keystroke. The registry is somebody else's server
 * and a query under two characters is a 400 there, so typing "documentation" would otherwise be
 * eleven requests, ten of them wasted and the last one racing the others to land.
 */
class SkillsModel(
    private val repository: SkillRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    val uiState: StateFlow<SkillsUiState> = combine(
        repository.skills,
        settingsRepository.settings,
    ) { skills, settings ->
        SkillsUiState(
            skills = skills.sortedBy { it.name.lowercase() },
            enabledGlobally = settings.skillsEnabled,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SkillsUiState())

    private val _explore = MutableStateFlow(ExploreUiState())
    val explore: StateFlow<ExploreUiState> = _explore.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Keeps the "Installed" badges on the Explore page honest while the user installs things.
        scope.launch {
            repository.skills.collect { skills -> _explore.update { it.copy(installed = skills) } }
        }
        // The page opens on a shelf rather than on an empty box: there is no endpoint that lists
        // everything, so the alternative to a curated first query is nothing at all.
        SkillTopic.Default.firstOrNull()?.let(::selectTopic)
    }

    // ---- The installed library ----------------------------------------------------------------

    fun setSkillsEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setSkillsEnabled(enabled) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        scope.launch { repository.setEnabled(id, enabled) }
    }

    fun remove(id: String) {
        scope.launch { repository.remove(id) }
    }

    // ---- Exploring ----------------------------------------------------------------------------

    fun onQueryChange(value: String) {
        _explore.update { it.copy(query = value, topic = null) }
        search(value, debounce = true)
    }

    fun selectTopic(topic: SkillTopic) {
        _explore.update { it.copy(topic = topic, query = "") }
        search(topic.query, debounce = false)
    }

    private fun search(query: String, debounce: Boolean) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) {
            _explore.update { it.copy(results = emptyList(), isSearching = false, error = null) }
            return
        }

        searchJob = scope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            _explore.update { it.copy(isSearching = true, error = null) }
            try {
                val results = repository.search(trimmed)
                _explore.update { it.copy(results = results, isSearching = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _explore.update {
                    it.copy(
                        isSearching = false,
                        // The registry's own message, which already names the host and the cause.
                        error = error.message ?: "The skills registry could not be reached.",
                    )
                }
            }
        }
    }

    /** Fetches the skill without saving it, so the panel shows what installing would add. */
    fun preview(listing: SkillListing) {
        _explore.update { it.copy(preview = SkillPreview(listing)) }
        scope.launch {
            try {
                val skill = repository.preview(listing)
                _explore.update { state ->
                    // Only if the panel still belongs to this listing; a fast second click wins.
                    if (state.preview?.listing?.id != listing.id) {
                        state
                    } else {
                        state.copy(preview = state.preview.copy(skill = skill))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _explore.update { state ->
                    if (state.preview?.listing?.id != listing.id) {
                        state
                    } else {
                        state.copy(
                            preview = state.preview.copy(
                                error = error.message ?: "That skill could not be read.",
                            ),
                        )
                    }
                }
            }
        }
    }

    fun dismissPreview() {
        _explore.update { it.copy(preview = null) }
    }

    fun install(listing: SkillListing) {
        if (listing.id in _explore.value.installing) return
        _explore.update { it.copy(installing = it.installing + listing.id) }

        scope.launch {
            try {
                repository.install(listing)
                _explore.update { it.copy(preview = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _explore.update {
                    it.copy(error = error.message ?: "That skill could not be installed.")
                }
            } finally {
                _explore.update { it.copy(installing = it.installing - listing.id) }
            }
        }
    }

    fun dismissExploreError() {
        _explore.update { it.copy(error = null) }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L

        /** The registry's own floor. Below it, a request is a 400 rather than an empty result. */
        const val MIN_QUERY = 2
    }
}
