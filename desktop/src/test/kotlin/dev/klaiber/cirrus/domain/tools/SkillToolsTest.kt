package dev.klaiber.cirrus.domain.tools

import dev.klaiber.cirrus.data.remote.skills.SkillsRegistryClient
import dev.klaiber.cirrus.data.repository.JsonStore
import dev.klaiber.cirrus.data.repository.SkillRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The chooser, from the model's side.
 *
 * Three things have to hold, and all three are the kind that fail silently. The brief has to carry
 * the descriptions and *not* the instructions, or the whole point of the two-step design is lost
 * and every turn pays for every skill. `use_skill` has to accept the name a model actually sends,
 * which is a paraphrase of one it read three messages ago. And the caveat has to arrive attached
 * to the instructions, because a skill written for a coding agent will otherwise have the model
 * reaching for a package manager that this app spends a whole policy file refusing.
 */
class SkillToolsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var file: File
    private lateinit var repository: SkillRepository
    private lateinit var tools: SkillToolSet

    @Before
    fun setUp() = runBlocking {
        file = File.createTempFile("cirrus-skills-", ".json")
        // Seeded through the store rather than through `install`, which would need the registry.
        // Writing the stored shape by hand also means the decode path is exercised here.
        file.writeText(
            """
            [
              {
                "id": "alice/skills/changelog-writer",
                "name": "changelog-writer",
                "description": "Turn a list of commits into a changelog people will read.",
                "source": "alice/skills",
                "installs": 4200,
                "instructions": "Start with what changed for the user. Run npm run build first.",
                "references": ["references/examples.md"],
                "enabled": true,
                "installedAt": 1
              },
              {
                "id": "bob/skills/invoice-totals",
                "name": "invoice-totals",
                "description": "Adds up an invoice and checks the arithmetic.",
                "source": "bob/skills",
                "installs": 12,
                "instructions": "Read every line before totalling.",
                "references": [],
                "enabled": false,
                "installedAt": 2
              }
            ]
            """.trimIndent(),
        )
        repository = SkillRepository(
            store = JsonStore(file, json),
            registry = SkillsRegistryClient(OkHttpClient(), json),
        )
        repository.load()
        tools = SkillToolSet(
            repository = repository,
            list = ListSkillsTool(repository),
            use = UseSkillTool(repository),
        )
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private suspend fun use(name: String): JsonObject =
        Json.parseToJsonElement(tools.use.execute(buildJsonObject { put("name", name) })).jsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    // ---- The brief -----------------------------------------------------------------------------

    /**
     * The whole design in one assertion: descriptions are cheap and go in every turn, instructions
     * are expensive and wait to be asked for.
     */
    @Test
    fun `the brief carries descriptions and never the instructions`() {
        val brief = tools.brief()!!

        assertTrue(brief.contains("changelog-writer"))
        assertTrue(brief.contains("Turn a list of commits"))
        assertFalse("the body is what use_skill is for", brief.contains("Start with what changed"))
        assertTrue("it has to say what to do about them", brief.contains("use_skill"))
    }

    /** A skill switched off is installed and not offered — that is the whole point of the switch. */
    @Test
    fun `a disabled skill is absent from the brief and cannot be loaded`() = runTest {
        assertFalse(tools.brief()!!.contains("invoice-totals"))
        assertTrue(use("invoice-totals").text("error")!!.contains("No installed skill"))
    }

    /** A sentence saying there is nothing is a sentence paid for on every turn to say nothing. */
    @Test
    fun `an empty library contributes no brief at all`(): Unit = runBlocking {
        val empty = File.createTempFile("cirrus-skills-empty-", ".json").also { it.delete() }
        val bare = SkillRepository(
            store = JsonStore(empty, json),
            registry = SkillsRegistryClient(OkHttpClient(), json),
        )
        bare.load()

        assertNull(
            SkillToolSet(bare, ListSkillsTool(bare), UseSkillTool(bare)).brief(),
        )
        empty.delete()
    }

    // ---- Loading one ---------------------------------------------------------------------------

    @Test
    fun `loading a skill returns its instructions and the environment it did not expect`() = runTest {
        val result = use("changelog-writer")

        assertEquals("alice/skills", result.text("from"))
        assertTrue(result.text("instructions")!!.startsWith("Start with what changed"))

        // The instructions here say to run npm. The caveat is what stops that being attempted.
        val environment = result.text("environment")!!
        assertTrue(environment.contains("chat client"))
        assertTrue(environment.contains("no package manager"))
        assertTrue("it has to say what to do instead", environment.contains("in your answer"))
    }

    /**
     * Models paraphrase a name they read several messages ago. Answering that with "no such skill"
     * spends a round trip on spelling, so a contains match is accepted — but only after the exact
     * ones, or a fuzzy hit could win over a real name.
     */
    @Test
    fun `a paraphrased name still finds the skill`() = runTest {
        assertEquals("changelog-writer", use("changelog").text("name"))
        assertEquals("changelog-writer", use("Changelog-Writer").text("name"))
        assertEquals("changelog-writer", use("alice/skills/changelog-writer").text("name"))
        assertEquals("changelog-writer", use("the changelog-writer skill").text("name"))
    }

    /**
     * A miss must not read as a missing feature. The model is told to carry on, because the user
     * asked for a job and not for a skill and has no idea one was looked for.
     */
    @Test
    fun `a name that matches nothing lists what there is and says to carry on`() = runTest {
        val result = use("tax-return")

        assertTrue(result.text("error")!!.contains("tax-return"))
        assertEquals(
            listOf("changelog-writer"),
            result["available"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(result.text("note")!!.contains("Carry on"))
    }

    /**
     * The instructions will name these files. Without the list, a model goes looking for
     * `references/examples.md` in a shell that cannot reach it and spends a turn on the puzzle.
     */
    @Test
    fun `files the package ships but Cirrus does not fetch are named`() = runTest {
        val result = use("changelog-writer")

        assertEquals(
            listOf("references/examples.md"),
            result["files_not_available"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(result.text("files_note")!!.contains("does not download"))
    }

    // ---- Listing -------------------------------------------------------------------------------

    @Test
    fun `listing returns descriptions only, and only the enabled ones`() = runTest {
        val result = Json.parseToJsonElement(tools.list.execute(buildJsonObject {})).jsonObject
        val listed = result["skills"]!!.jsonArray.map { it.jsonObject }

        assertEquals(1, listed.size)
        assertEquals("changelog-writer", listed[0].text("name"))
        assertNull("instructions are use_skill's job", listed[0]["instructions"])
    }

    @Test
    fun `a query narrows the list and a miss explains itself`() = runTest {
        val hit = Json.parseToJsonElement(
            tools.list.execute(buildJsonObject { put("query", "changelog") }),
        ).jsonObject
        assertEquals(1, hit["skills"]!!.jsonArray.size)

        val miss = Json.parseToJsonElement(
            tools.list.execute(buildJsonObject { put("query", "astrophysics") }),
        ).jsonObject
        assertEquals(0, miss["skills"]!!.jsonArray.size)
        assertTrue(miss.text("note")!!.contains("Nothing matched"))
    }
}
