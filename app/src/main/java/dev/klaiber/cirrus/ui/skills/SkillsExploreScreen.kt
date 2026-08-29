package dev.klaiber.cirrus.ui.skills

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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.domain.model.Skill
import dev.klaiber.cirrus.domain.model.SkillListing
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.components.PillStyle
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.theme.Pill

/**
 * The public skills library, browsable.
 *
 * It opens on a shelf rather than on an empty search box, and that is a constraint rather than a
 * flourish: the registry has no endpoint that lists everything and its search rejects a query under
 * two characters, so a screen that waited to be typed into would open with nothing on it and no
 * hint of what is there. The chips are ordinary searches, which means what you see on arrival is
 * exactly what you would get by typing — no second data path, and nothing curated that can go stale.
 *
 * Nothing installs on a single tap. A skill is instructions a model will follow, written by a
 * stranger, so the card opens a preview with the actual text in it first. The install count is the
 * only trust signal the registry offers, so it is shown on every card rather than buried.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsExploreScreen(
    onBack: () -> Unit,
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    val state by viewModel.explore.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissExploreError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Explore skills") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search the library") },
                placeholder = { Text("changelog, invoices, meeting notes…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.topics.forEach { topic ->
                    FilterChip(
                        selected = state.topic == topic,
                        onClick = { viewModel.selectTopic(topic) },
                        label = { Text(topic.label) },
                        shape = Pill,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.isSearching && state.results.isEmpty()) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(26.dp))
                        }
                    }
                }

                if (!state.isSearching && state.results.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (state.query.isBlank()) {
                                "Pick a subject above, or search for the job you have in mind."
                            } else {
                                "Nothing in the library matches \"${state.query}\". Try one word " +
                                    "rather than a phrase — the registry matches on keywords."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }

                items(state.results, key = { it.id }) { listing ->
                    ListingCard(
                        listing = listing,
                        isInstalled = listing.isInstalled(state.installed),
                        isInstalling = listing.id in state.installing,
                        onOpen = { viewModel.preview(listing) },
                        onInstall = { viewModel.install(listing) },
                    )
                }
            }
        }
    }

    state.preview?.let { preview ->
        SkillPreviewSheet(
            preview = preview,
            isInstalled = preview.listing.isInstalled(state.installed),
            isInstalling = preview.listing.id in state.installing,
            onInstall = { viewModel.install(preview.listing) },
            onDismiss = viewModel::dismissPreview,
        )
    }
}

@Composable
private fun ListingCard(
    listing: SkillListing,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
) {
    OutlinedPanel(
        shape = LargeContainerShape,
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = listing.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // The owner is the whole of what is knowable about trust here, so it sits
                        // next to the install count rather than under a "details" tap.
                        text = "${listing.source} · ${formatInstalls(listing.installs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                when {
                    isInstalling -> CircularProgressIndicator(Modifier.size(20.dp))

                    isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Installed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> PillButton(
                        label = "Install",
                        onClick = onInstall,
                        style = PillStyle.Secondary,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * What installing would actually add.
 *
 * The registry's index carries a name and an install count and nothing else — not even the
 * description — so the sheet fetches the package to show one. That fetch is the same call the
 * install makes, which is why opening this and then installing is fast: the second request is
 * against a cache the first one warmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillPreviewSheet(
    preview: SkillPreview,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(preview.listing.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "${preview.listing.source} · ${formatInstalls(preview.listing.installs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(Modifier.height(14.dp))

            when {
                preview.error != null -> Text(
                    text = preview.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                preview.isLoading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }

                else -> SkillBody(preview.skill!!)
            }

            Spacer(Modifier.height(18.dp))

            PillButton(
                label = when {
                    isInstalled -> "Reinstall"
                    isInstalling -> "Installing…"
                    else -> "Install"
                },
                onClick = onInstall,
                enabled = preview.skill != null && !isInstalling,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SkillBody(skill: Skill) {
    Text(skill.description, style = MaterialTheme.typography.bodyMedium)

    if (skill.references.isNotEmpty()) {
        Text(
            // Said before installing rather than after, because it is the honest limit of what a
            // chat client can do with a package built for an agent with a file system.
            text = "This skill ships ${skill.references.size} extra files that Cirrus does not " +
                "download. Where its instructions point at one, the model works from what it " +
                "already knows instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    Spacer(Modifier.height(14.dp))
    Text("The instructions", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))

    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        // Its own scroller with a cap: a SKILL.md runs to several thousand words, and a sheet that
        // grows to fit one puts its install button somewhere off the bottom of the screen.
        Text(
            text = skill.instructions,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        )
    }
}

/** "135K installs", the way the registry's own CLI writes it. */
internal fun formatInstalls(count: Int): String = when {
    count <= 0 -> "new"
    count >= 1_000_000 -> "${(count / 100_000) / 10.0}M installs".replace(".0M", "M")
    count >= 1_000 -> "${(count / 100) / 10.0}K installs".replace(".0K", "K")
    count == 1 -> "1 install"
    else -> "$count installs"
}
