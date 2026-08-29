package dev.klaiber.cirrus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.klaiber.cirrus.data.remote.skills.SkillsRegistryClient
import dev.klaiber.cirrus.di.ApplicationScope
import dev.klaiber.cirrus.domain.model.Skill
import dev.klaiber.cirrus.domain.model.SkillListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The skills the user has installed, and the registry they came from.
 *
 * DataStore rather than Room, for the same reason the MCP servers live there: this is a short list
 * of independent records with no relations, nothing joins against it, and adding a table would mean
 * a schema migration on every install that changes what a skill holds. A skill is capped at
 * [Skill.MAX_INSTRUCTION_CHARS] on the way in, so the whole store stays in the low hundreds of
 * kilobytes even for somebody who collects them.
 *
 * [current] is a synchronous snapshot because `ToolRegistry.standingBrief` reads it while a turn is
 * being assembled, which is the wrong moment to suspend on a file — the same reason
 * `McpServerRepository.bindings` exists in the shape it does.
 *
 * Nothing here is a secret, which is the one way this differs from the MCP store: a skill is public
 * text from a public repository, so there is no cipher and nothing to protect. What it *is* is text
 * written by a stranger that a model will read as instruction, and that is a different problem —
 * handled where it belongs, at the moment the instructions are handed over.
 */
@Singleton
class SkillRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val registry: SkillsRegistryClient,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {

    val skills: Flow<List<Skill>> = dataStore.data.map { prefs ->
        prefs[Keys.SKILLS]?.let(::decode).orEmpty()
    }

    val current: StateFlow<List<Skill>> = skills.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** What the model may actually be told about. */
    fun active(): List<Skill> = current.value.filter { it.enabled }

    /** Searches the registry. Failures are the caller's to show; this does not swallow them. */
    suspend fun search(query: String, owner: String? = null): List<SkillListing> =
        registry.search(query, owner)

    /**
     * Fetches a skill and saves it.
     *
     * Re-installing an existing id replaces it rather than adding a second copy — which is what
     * "update" is here, since the registry has no version to compare against and the snapshot is
     * always the current one. The enabled flag survives that replacement, because somebody who
     * parked a skill and then updated it did not thereby ask for it back.
     */
    suspend fun install(listing: SkillListing): Skill {
        val fetched = registry.fetch(listing.id)
        val skill = fetched.copy(
            installs = listing.installs,
            enabled = current.value.firstOrNull { it.id == listing.id }?.enabled ?: true,
        )
        mutate { existing ->
            val index = existing.indexOfFirst { it.id == skill.id }
            if (index >= 0) existing.toMutableList().also { it[index] = skill } else existing + skill
        }
        return skill
    }

    /** Fetches without saving, so the preview sheet can show what installing would actually add. */
    suspend fun preview(listing: SkillListing): Skill =
        registry.fetch(listing.id).copy(installs = listing.installs)

    suspend fun remove(id: String) = mutate { existing -> existing.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { existing ->
        existing.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    private suspend fun mutate(block: (List<Skill>) -> List<Skill>) {
        dataStore.edit { prefs ->
            val existing = prefs[Keys.SKILLS]?.let(::decode).orEmpty()
            prefs[Keys.SKILLS] = encode(block(existing))
        }
    }

    private fun encode(skills: List<Skill>): String = json.encodeToString(
        StoredSkills.serializer(),
        StoredSkills(
            skills.map {
                StoredSkill(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    source = it.source,
                    installs = it.installs,
                    instructions = it.instructions.take(Skill.MAX_INSTRUCTION_CHARS),
                    references = it.references,
                    enabled = it.enabled,
                    installedAt = it.installedAt,
                )
            },
        ),
    )

    private fun decode(raw: String): List<Skill> =
        runCatching { json.decodeFromString(StoredSkills.serializer(), raw) }
            .getOrNull()
            ?.skills
            ?.map {
                Skill(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    source = it.source,
                    installs = it.installs,
                    instructions = it.instructions,
                    references = it.references,
                    enabled = it.enabled,
                    installedAt = it.installedAt,
                )
            }
            .orEmpty()

    private object Keys {
        val SKILLS = stringPreferencesKey("skills")
    }

    @Serializable
    private data class StoredSkills(val skills: List<StoredSkill> = emptyList())

    @Serializable
    private data class StoredSkill(
        val id: String,
        val name: String,
        val description: String,
        val source: String = "",
        val installs: Int = 0,
        val instructions: String = "",
        val references: List<String> = emptyList(),
        val enabled: Boolean = true,
        val installedAt: Long = 0L,
    )
}
