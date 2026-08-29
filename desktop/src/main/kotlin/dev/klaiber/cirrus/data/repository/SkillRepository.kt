package dev.klaiber.cirrus.data.repository

import dev.klaiber.cirrus.data.remote.skills.SkillsRegistryClient
import dev.klaiber.cirrus.domain.model.Skill
import dev.klaiber.cirrus.domain.model.SkillListing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The skills the user has installed, backed by a JSON file.
 *
 * Same shape as the Android store and the same reasoning, minus the parts this build does not
 * have. There is no DataStore here, so it is a [JsonStore] like every other store; there is no
 * cipher either, and unlike the MCP servers that costs nothing — a skill is public text from a
 * public repository, so there is no secret in it to protect.
 *
 * [current] is a synchronous snapshot because `SkillToolSet.brief` reads it while a turn is being
 * assembled, which is the wrong moment to suspend on a file.
 */
class SkillRepository(
    private val store: JsonStore,
    private val registry: SkillsRegistryClient,
) {

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())

    val skills: Flow<List<Skill>> = _skills.asStateFlow()

    val current: StateFlow<List<Skill>> = _skills.asStateFlow()

    suspend fun load() {
        _skills.value = store
            .read(ListSerializer(StoredSkill.serializer())) { emptyList() }
            .map { it.toSkill() }
    }

    /** What the model may actually be told about. */
    fun active(): List<Skill> = _skills.value.filter { it.enabled }

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
            enabled = _skills.value.firstOrNull { it.id == listing.id }?.enabled ?: true,
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
        val updated = block(_skills.value)
        _skills.value = updated
        store.write(
            ListSerializer(StoredSkill.serializer()),
            updated.map { StoredSkill.from(it) },
        )
    }

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
    ) {
        fun toSkill(): Skill = Skill(
            id = id,
            name = name,
            description = description,
            source = source,
            installs = installs,
            instructions = instructions,
            references = references,
            enabled = enabled,
            installedAt = installedAt,
        )

        companion object {
            fun from(skill: Skill): StoredSkill = StoredSkill(
                id = skill.id,
                name = skill.name,
                description = skill.description,
                source = skill.source,
                installs = skill.installs,
                instructions = skill.instructions.take(Skill.MAX_INSTRUCTION_CHARS),
                references = skill.references,
                enabled = skill.enabled,
                installedAt = skill.installedAt,
            )
        }
    }
}
