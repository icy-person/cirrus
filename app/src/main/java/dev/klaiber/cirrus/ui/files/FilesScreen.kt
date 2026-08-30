package dev.klaiber.cirrus.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.klaiber.cirrus.domain.files.FileKind
import dev.klaiber.cirrus.domain.files.FilePreview
import dev.klaiber.cirrus.domain.files.ScratchpadFile
import dev.klaiber.cirrus.domain.files.TopicListing
import dev.klaiber.cirrus.domain.files.delimiterFor
import dev.klaiber.cirrus.domain.files.parseDelimited
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.markdown.CodeBlock
import dev.klaiber.cirrus.ui.markdown.MarkdownText
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.util.rememberClipboard

/**
 * The scratch files this conversation has, and a way to look at them.
 *
 * The shell has been able to list and read these since it shipped; the person whose phone they are
 * on could not. A model would say "I saved the totals to expenses/totals.csv" — true, and there was
 * no screen in the app where that file existed, so the only way to see your own working file was to
 * ask for it to be printed back at you one `cat` at a time.
 *
 * Grouped by topic rather than shown flat, because a topic is the job: the useful question is "what
 * did the expenses work leave behind", not "what files are there". The download button is on the
 * viewer rather than on every row for the same reason a preview exists at all — you look first,
 * then decide whether you want it out of here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onBack: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmTopic by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    // Back closes the viewer before it leaves the screen, which is what the title bar's arrow does
    // too — one gesture, one meaning.
    BackHandler(enabled = state.viewing != null) { viewModel.closeViewer() }

    val open = state.viewing
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = open?.file?.name ?: "Files",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { if (open != null) viewModel.closeViewer() else onBack() },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = if (open != null) "Back to files" else "Back",
                            )
                        }
                    },
                    actions = {
                        if (open != null) {
                            ViewerActions(open = open, onDownload = viewModel::download)
                        } else if (!state.isEmpty) {
                            IconButton(onClick = { confirmClearAll = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete all files")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Hairline()
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                open != null -> FileViewer(
                    open = open,
                    onDelete = viewModel::deleteOpenFile,
                )

                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp))
                }

                else -> TopicList(
                    state = state,
                    onOpen = viewModel::open,
                    onDeleteTopic = { confirmTopic = it },
                )
            }
        }
    }

    if (confirmClearAll) {
        ConfirmDelete(
            title = "Delete all scratch files?",
            body = "Every file this conversation's commands have written goes. Anything you want " +
                "to keep should be downloaded first — this cannot be undone.",
            onConfirm = {
                viewModel.deleteEverything()
                confirmClearAll = false
            },
            onDismiss = { confirmClearAll = false },
        )
    }

    confirmTopic?.let { topic ->
        ConfirmDelete(
            title = "Delete \"$topic\"?",
            body = "Every file in that job goes. This cannot be undone.",
            onConfirm = {
                viewModel.deleteTopic(topic)
                confirmTopic = null
            },
            onDismiss = { confirmTopic = null },
        )
    }
}

@Composable
private fun ViewerActions(open: OpenFile, onDownload: () -> Unit) {
    val clipboard = rememberClipboard()
    val readable = open.preview as? FilePreview.Readable

    if (readable != null) {
        IconButton(
            onClick = { clipboard.copy(readable.text) },
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Icon(Icons.Outlined.Notes, contentDescription = "Copy the text")
        }
    }
    IconButton(
        onClick = onDownload,
        enabled = !open.saving,
        modifier = Modifier.minimumInteractiveComponentSize(),
    ) {
        if (open.saving) {
            CircularProgressIndicator(Modifier.size(18.dp))
        } else {
            Icon(Icons.Outlined.Download, contentDescription = "Save to Downloads")
        }
    }
}

@Composable
private fun TopicList(
    state: FilesUiState,
    onOpen: (ScratchpadFile) -> Unit,
    onDeleteTopic: (String) -> Unit,
) {
    if (state.isEmpty) {
        EmptyFiles()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            Text(
                text = "${state.fileCount} file${if (state.fileCount == 1) "" else "s"} · " +
                    formatBytes(state.sizeBytes) + " · written by shell commands in this " +
                    "conversation. They are cleaned up on their own after a while, so download " +
                    "anything you want to keep.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.topics.forEach { topic ->
            item(key = "topic-${topic.name}") {
                TopicCard(topic = topic, onOpen = onOpen, onDelete = { onDeleteTopic(topic.name) })
            }
        }
    }
}

@Composable
private fun TopicCard(
    topic: TopicListing,
    onOpen: (ScratchpadFile) -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(topic.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${topic.files.size} file${if (topic.files.size == 1) "" else "s"}" +
                            " · ${formatBytes(topic.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete ${topic.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            topic.files.forEach { file ->
                Hairline(startIndent = 16.dp)
                FileRow(file = file, onClick = { onOpen(file) })
            }
        }
    }
}

@Composable
private fun FileRow(file: ScratchpadFile, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconFor(file.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = file.path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatBytes(file.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The file itself.
 *
 * Six kinds, six renderings, and each one exists because the others get it wrong: a CSV shown as
 * text is a wall of commas, markdown shown as text is a wall of asterisks, and an image shown as
 * text is nothing at all. `CodeBlock` does the work for everything textual — it already highlights,
 * wraps and copies, and reusing it means a JSON file looks the same here as it does in an answer.
 */
@Composable
private fun FileViewer(open: OpenFile, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${open.file.topic} · ${formatBytes(open.file.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }

        when (val preview = open.preview) {
            null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }

            is FilePreview.Opaque -> Text(
                text = preview.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )

            is FilePreview.Picture -> Box(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                AsyncImage(
                    model = preview.file,
                    contentDescription = open.file.name,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is FilePreview.Readable -> ReadableBody(open.file, preview)
        }
    }

    if (confirmDelete) {
        ConfirmDelete(
            title = "Delete ${open.file.name}?",
            body = "This cannot be undone. Download it first if you want to keep it.",
            onConfirm = {
                onDelete()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ReadableBody(file: ScratchpadFile, preview: FilePreview.Readable) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        if (preview.truncated) {
            Text(
                text = "This file is too long to show whole; the beginning is below. Download it " +
                    "to get all of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        when (preview.kind) {
            FileKind.MARKDOWN -> MarkdownText(
                markdown = preview.text,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            FileKind.TABLE -> DelimitedTable(
                rows = remember(preview.text, file.name) {
                    parseDelimited(preview.text, delimiterFor(file.name))
                },
            )

            else -> CodeBlock(
                code = preview.text,
                language = preview.language,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * A CSV as a table.
 *
 * Scrolls sideways rather than wrapping cells, because a spreadsheet's columns are the structure —
 * wrapping them to fit a phone produces something with the same characters and none of the shape.
 * The first row is treated as a header, which is right for essentially every file the shell writes
 * and harmless when it is not: a wrongly bold first row is a smaller error than no header at all.
 */
@Composable
private fun DelimitedTable(rows: List<List<String>>) {
    if (rows.isEmpty()) {
        Text(
            text = "There are no rows in this file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }

    val columns = rows.maxOf { it.size }
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, row ->
                if (index > 0) Hairline()
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    repeat(columns) { column ->
                        Text(
                            text = row.getOrElse(column) { "" },
                            style = if (index == 0) {
                                MaterialTheme.typography.labelMedium
                            } else {
                                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(CellWidth).padding(end = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFiles() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("No scratch files", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "When the model runs a shell command that writes something — sorting a list, " +
                "downloading a page, working out totals — the files show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfirmDelete(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val CellWidth = 140.dp

private fun iconFor(kind: FileKind): ImageVector = when (kind) {
    FileKind.MARKDOWN -> Icons.Outlined.Notes
    FileKind.TABLE -> Icons.Outlined.TableChart
    FileKind.CODE -> Icons.Outlined.Code
    FileKind.TEXT -> Icons.AutoMirrored.Outlined.InsertDriveFile
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.BINARY -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** Sizes as a person would say them: no decimal on bytes, one everywhere else. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / (1_024.0 * 1_024))
}
