package dev.klaiber.cirrus.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.domain.model.Skill
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.theme.LargeContainerShape

/**
 * The skills the user has installed.
 *
 * A list of what is here, and one button to go and find more. The switch on each row is the part
 * worth having: a skill you have stopped wanting is more often parked than deleted — it was chosen
 * once and will be wanted again — and every enabled one costs a line of the system prompt on every
 * turn, so turning one off is a real saving rather than tidiness.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    onExplore: () -> Unit,
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var removeTarget by remember { mutableStateOf<Skill?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills") },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onExplore,
                icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                text = { Text("Explore library") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = "A skill is a page of instructions for one kind of job, written by " +
                        "somebody who does it. The model is told what each one is for and reads " +
                        "the full instructions only when it picks one — so an installed skill " +
                        "costs a line until it is actually used.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item(key = "master-switch") {
                OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use skills", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (state.enabledGlobally) {
                                    "${state.activeCount} of ${state.skills.size} offered to the model"
                                } else {
                                    "Off — nothing installed here is offered to the model"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.enabledGlobally,
                            onCheckedChange = viewModel::setSkillsEnabled,
                        )
                    }
                }
            }

            if (state.skills.isEmpty()) {
                item(key = "empty") { EmptySkillsCard(onExplore = onExplore) }
            } else {
                items(state.skills, key = { it.id }) { skill ->
                    InstalledSkillCard(
                        skill = skill,
                        dimmed = !state.enabledGlobally,
                        onToggle = { viewModel.setEnabled(skill.id, it) },
                        onRemove = { removeTarget = skill },
                    )
                }
            }

            // Clears the FAB so the last row is reachable.
            item(key = "fab-spacer") { Spacer(Modifier.height(72.dp)) }
        }
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove ${target.name}?") },
            text = {
                Text(
                    "Its instructions are deleted from this device. It stays in the library, so " +
                        "you can install it again from Explore.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(target.id)
                        removeTarget = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InstalledSkillCard(
    skill: Skill,
    dimmed: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = skill.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggle,
                    // Off globally means every row is inert; a live-looking switch that changes
                    // nothing is worse than one that plainly cannot be pressed.
                    enabled = !dimmed,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (skill.references.isEmpty()) {
                        "${skill.instructions.length} characters of instructions"
                    } else {
                        "${skill.instructions.length} characters, " +
                            "${skill.references.size} extra files not downloaded"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Remove ${skill.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySkillsCard(onExplore: () -> Unit) {
    OutlinedPanel(
        shape = LargeContainerShape,
        modifier = Modifier.fillMaxWidth(),
        onClick = onExplore,
    ) {
        Column(Modifier.padding(20.dp)) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text("Nothing installed yet", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "The library has thousands, published as ordinary GitHub repositories. " +
                    "Tap to browse them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
