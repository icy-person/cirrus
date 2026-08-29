package dev.klaiber.cirrus.data.remote.skills

import dev.klaiber.cirrus.domain.model.Skill
import dev.klaiber.cirrus.domain.model.SkillListing
import dev.klaiber.cirrus.domain.model.parseSkillDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** What went wrong talking to the registry, in a sentence the Explore screen can show. */
sealed class SkillsException(message: String) : IOException(message) {
    class Network(detail: String) : SkillsException("Could not reach the skills registry: $detail")
    class Http(code: Int) : SkillsException("The skills registry returned HTTP $code.")
    class NotFound(id: String) :
        SkillsException("\"$id\" is not in the registry any more, or its repository is private.")

    class Malformed(detail: String) :
        SkillsException("That skill could not be read: $detail")
}

/**
 * The public skills registry at skills.sh, over its two endpoints.
 *
 * The registry is the index behind `npx skills`; the skills themselves are ordinary GitHub
 * repositories and the registry serves a flattened snapshot of each. Two calls are all that is
 * needed: `/api/search` to find one, and `/api/download` to get its files. Neither is
 * authenticated, which is why this rides on the credential-free client — a search for "invoices"
 * has no business carrying anyone's Ollama key.
 *
 * There is no "browse everything" endpoint, and the search one rejects a query shorter than two
 * characters. That is a real constraint on what an Explore page can be, and rather than working
 * around it the screen leans into it: the collections it opens with are curated queries, each run
 * through the same [search] as anything the user types.
 *
 * Only `SKILL.md` is kept from a snapshot. A package may also carry reference files, scripts and
 * an agent manifest, none of which Cirrus can execute or read on demand — so their *names* are
 * recorded and their contents are not fetched, which keeps an install to a few kilobytes and keeps
 * the store honest about what it has.
 */
class SkillsRegistryClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    /**
     * Searches the registry, ordered by installs.
     *
     * A query under two characters returns nothing rather than raising: it is what the field looks
     * like while somebody is still typing, and an error banner that appears on the first keystroke
     * is a worse answer than an empty list.
     */
    suspend fun search(
        query: String,
        owner: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<SkillListing> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_CHARS) return emptyList()

        val url = "$BASE_URL/api/search".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("q", trimmed)
            .addQueryParameter("limit", limit.coerceIn(1, MAX_LIMIT).toString())
            .apply { owner?.trim()?.takeIf { it.isNotBlank() }?.let { addQueryParameter("owner", it) } }
            .build()

        val payload = get(url.toString())
        val response = runCatching {
            json.decodeFromString(SearchResponseDto.serializer(), payload)
        }.getOrElse { throw SkillsException.Malformed("the search response was not what was expected") }

        return response.skills
            .mapNotNull { it.toListing() }
            .sortedByDescending { it.installs }
    }

    /**
     * Downloads one skill and reads its `SKILL.md`.
     *
     * [id] is the registry's own `owner/repo/slug`, which is what [search] returns and what the
     * install button carries — parsing it here rather than passing three arguments keeps that one
     * string the single identity of a skill from the search result all the way into the store.
     */
    suspend fun fetch(id: String): Skill {
        val parts = id.split('/').filter { it.isNotBlank() }
        if (parts.size != 3) throw SkillsException.Malformed("\"$id\" is not an owner/repo/skill id")
        val (owner, repo, slug) = parts

        val url = "$BASE_URL/api/download/${owner.encoded()}/${repo.encoded()}/${slug.encoded()}"
        val payload = get(url)
        val snapshot = runCatching {
            json.decodeFromString(DownloadResponseDto.serializer(), payload)
        }.getOrElse { throw SkillsException.Malformed("the download was not a skill package") }

        // Case-insensitively, and the shallowest first: a package may contain several SKILL.md
        // files (a repository of skills), and the one at the top of this snapshot is this skill's.
        val document = snapshot.files
            .filter { it.path.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .minByOrNull { it.path.count { char -> char == '/' } }
            ?: throw SkillsException.Malformed("the package has no SKILL.md")

        val parsed = parseSkillDocument(document.contents)
            ?: throw SkillsException.Malformed("its SKILL.md has no name and description")

        return Skill(
            id = id,
            name = parsed.name,
            description = parsed.description,
            source = "$owner/$repo",
            installs = 0,
            instructions = parsed.body.take(Skill.MAX_INSTRUCTION_CHARS),
            references = snapshot.files
                .map { it.path }
                .filterNot { it.equals(document.path, ignoreCase = true) }
                .sorted()
                .take(MAX_REFERENCES),
            installedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw SkillsException.Network(error.message ?: "no connection")
        }

        response.use {
            if (it.code == 404) throw SkillsException.NotFound(url.substringAfter("/api/download/"))
            if (!it.isSuccessful) throw SkillsException.Http(it.code)
            it.body?.string().orEmpty()
        }
    }

    /** Path segments come from a registry id, but an id is a string somebody else chose. */
    private fun String.encoded(): String =
        replace("/", "").replace("?", "").replace("#", "").trim()

    private companion object {
        const val BASE_URL = "https://skills.sh"
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        /** The registry's own floor: shorter than this is a 400, not an empty result. */
        const val MIN_QUERY_CHARS = 2

        /** Enough to tell the model what it is missing without listing a whole repository. */
        const val MAX_REFERENCES = 20
    }
}

// ---- Wire shapes ------------------------------------------------------------------------------

@Serializable
private data class SearchResponseDto(val skills: List<SearchSkillDto> = emptyList())

/**
 * One search hit. `id` is `owner/repo/slug` and `source` is `owner/repo`, so the two overlap —
 * both are kept because `source` is what the card shows and `id` is what the install call needs,
 * and deriving either from the other assumes a slug can never contain a slash.
 */
@Serializable
private data class SearchSkillDto(
    val id: String = "",
    val name: String = "",
    val source: String = "",
    val installs: Int = 0,
    @SerialName("skillId") val skillId: String = "",
) {
    fun toListing(): SkillListing? {
        if (id.isBlank() || name.isBlank()) return null
        return SkillListing(
            id = id,
            name = name,
            source = source.ifBlank { id.substringBeforeLast('/') },
            installs = installs,
        )
    }
}

@Serializable
private data class DownloadResponseDto(
    val files: List<SnapshotFileDto> = emptyList(),
    val hash: String = "",
)

@Serializable
private data class SnapshotFileDto(val path: String = "", val contents: String = "")
