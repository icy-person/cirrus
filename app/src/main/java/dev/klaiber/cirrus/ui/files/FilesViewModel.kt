package dev.klaiber.cirrus.ui.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.klaiber.cirrus.domain.files.DownloadSink
import dev.klaiber.cirrus.domain.files.FilePreview
import dev.klaiber.cirrus.domain.files.ScratchpadBrowser
import dev.klaiber.cirrus.domain.files.ScratchpadFile
import dev.klaiber.cirrus.domain.files.TopicListing
import dev.klaiber.cirrus.domain.tools.shell.mimeTypeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The explorer's state: what this conversation has, and which file is open. */
data class FilesUiState(
    val topics: List<TopicListing> = emptyList(),
    val loading: Boolean = true,
    /** Non-null while a file is open. */
    val viewing: OpenFile? = null,
    val message: String? = null,
) {
    val fileCount: Int get() = topics.sumOf { it.files.size }
    val sizeBytes: Long get() = topics.sumOf { it.sizeBytes }
    val isEmpty: Boolean get() = topics.isEmpty()
}

/** A file the viewer has open, and what came back when it was read. */
data class OpenFile(
    val file: ScratchpadFile,
    val preview: FilePreview? = null,
    val saving: Boolean = false,
)

/**
 * The scratchpad, from the user's side.
 *
 * Reads on the IO dispatcher and holds the result, rather than exposing a flow over the file
 * system: nothing else writes here while the screen is up except the model, and a turn that adds a
 * file mid-scroll would otherwise reorder the list under a finger. [refresh] is what the screen
 * calls when it comes back, which is the moment the answer can actually have changed.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val browser: ScratchpadBrowser,
    private val downloads: DownloadSink,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conversationId: String? =
        savedStateHandle.get<String>(ARG_CONVERSATION_ID)?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val topics = withContext(Dispatchers.IO) { browser.topics(conversationId) }
            _uiState.update { it.copy(topics = topics, loading = false) }
        }
    }

    fun open(file: ScratchpadFile) {
        _uiState.update { it.copy(viewing = OpenFile(file)) }
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) {
                browser.preview(conversationId, file.topic, file.path)
            }
            _uiState.update { state ->
                // A second tap while the first was reading wins; this one is stale.
                if (state.viewing?.file?.path != file.path) {
                    state
                } else {
                    state.copy(viewing = state.viewing.copy(preview = preview))
                }
            }
        }
    }

    fun closeViewer() = _uiState.update { it.copy(viewing = null) }

    /**
     * Puts the open file in the user's Downloads.
     *
     * The same sink `download_file` uses, for the same reason: this is the only place these files
     * can go that the user can then open with something else. It is the answer to the whole
     * category of "the model made me a CSV and I want it in a spreadsheet".
     */
    fun download() {
        val open = _uiState.value.viewing ?: return
        if (open.saving) return
        _uiState.update { it.copy(viewing = open.copy(saving = true)) }

        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                browser.file(conversationId, open.file.topic, open.file.path)
            }
            val saved = source?.let {
                downloads.save(it, open.file.name, mimeTypeOf(open.file.name))
            }
            _uiState.update { state ->
                state.copy(
                    viewing = state.viewing?.copy(saving = false),
                    message = when {
                        saved != null -> "Saved to ${saved.location}"
                        source == null -> "That file is not there any more."
                        else -> "Could not save to Downloads."
                    },
                )
            }
        }
    }

    fun deleteOpenFile() {
        val open = _uiState.value.viewing ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                browser.deleteFile(conversationId, open.file.topic, open.file.path)
            }
            _uiState.update { it.copy(viewing = null, message = "${open.file.name} deleted") }
            refresh()
        }
    }

    fun deleteTopic(topic: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { browser.deleteTopic(conversationId, topic) }
            _uiState.update {
                it.copy(message = if (removed == 1) "1 file deleted" else "$removed files deleted")
            }
            refresh()
        }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { browser.deleteAll(conversationId) }
            _uiState.update {
                it.copy(
                    viewing = null,
                    message = if (removed == 1) "1 file deleted" else "$removed files deleted",
                )
            }
            refresh()
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        const val ARG_CONVERSATION_ID = "conversationId"
    }
}
