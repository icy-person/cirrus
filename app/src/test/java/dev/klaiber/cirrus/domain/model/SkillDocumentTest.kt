package dev.klaiber.cirrus.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a `SKILL.md` written by somebody else.
 *
 * The file comes from a public registry of thousands of repositories, so the input is not one
 * format but a family of near-misses: CRLF from Windows, quoted and unquoted values, a description
 * folded over three lines, keys this app has no use for. None of that is exotic and all of it is
 * present in the registry today, which is why it is asserted here rather than assumed.
 *
 * The refusals matter as much as the successes. A skill with no description cannot be chosen
 * between by a model that only ever sees descriptions, so it is rejected at the door rather than
 * installed as a nameless entry somebody has to work out later.
 */
class SkillDocumentTest {

    @Test
    fun `reads the two fields that matter and keeps the rest of the file as the body`() {
        val document = parseSkillDocument(
            """
            ---
            name: changelog-writer
            description: Turn a list of commits into a changelog people will read.
            allowed-tools: Bash(git:*)
            license: MIT
            ---

            # Writing a changelog

            Start with what changed for the user.
            """.trimIndent(),
        )

        assertEquals("changelog-writer", document?.name)
        assertEquals("Turn a list of commits into a changelog people will read.", document?.description)
        assertTrue(document!!.body.startsWith("# Writing a changelog"))
        assertTrue("the keys we do not use are simply ignored", "MIT" !in document.body)
    }

    @Test
    fun `quotes around a value are not part of it`() {
        val document = parseSkillDocument(
            "---\nname: \"invoice-totals\"\ndescription: 'Adds up an invoice.'\n---\nBody.",
        )

        assertEquals("invoice-totals", document?.name)
        assertEquals("Adds up an invoice.", document?.description)
    }

    /**
     * Several skills in the registry fold a long description over three lines. Reading only the
     * first gives a sentence that stops mid-clause, which is then the sentence a model chooses by.
     */
    @Test
    fun `a folded description is read whole`() {
        val document = parseSkillDocument(
            """
            ---
            name: research
            description: >
              Runs a structured literature search, then summarises what was found
              and what remains open, with sources.
            ---
            Body.
            """.trimIndent(),
        )

        assertEquals(
            "Runs a structured literature search, then summarises what was found and what " +
                "remains open, with sources.",
            document?.description,
        )
    }

    @Test
    fun `windows line endings and a leading byte order mark are survivable`() {
        val document = parseSkillDocument(
            "\uFEFF---\r\nname: notes\r\ndescription: Tidies meeting notes.\r\n---\r\n\r\nBody text.",
        )

        assertEquals("notes", document?.name)
        assertEquals("Tidies meeting notes.", document?.description)
        assertEquals("Body text.", document?.body)
    }

    @Test
    fun `a file that is not a skill is refused rather than guessed at`() {
        assertNull("no frontmatter at all", parseSkillDocument("# Just a readme\n\nHello."))
        assertNull("an unterminated block", parseSkillDocument("---\nname: x\ndescription: y"))
        assertNull(
            "no description, so nothing to choose it by",
            parseSkillDocument("---\nname: x\n---\nBody."),
        )
        assertNull(
            "no name, so nothing to call it",
            parseSkillDocument("---\ndescription: does a thing\n---\nBody."),
        )
        assertNull(parseSkillDocument(""))
    }

    @Test
    fun `a runaway description cannot become the whole brief`() {
        val document = parseSkillDocument(
            "---\nname: big\ndescription: ${"word ".repeat(500)}\n---\nBody.",
        )

        assertTrue(document!!.description.length <= 400)
    }

    // ---- The one line that goes into every system prompt ---------------------------------------

    @Test
    fun `a brief line stays short enough to send on every turn`() {
        val skill = Skill(
            id = "owner/repo/thing",
            name = "thing",
            description = "d".repeat(500),
            source = "owner/repo",
            installs = 10,
            instructions = "body",
        )

        assertTrue(skill.brief().length < 200)
        assertTrue(skill.brief().startsWith("thing — "))
    }

    @Test
    fun `installed is matched on id, since two owners may publish the same name`() {
        val mine = Skill(
            id = "alice/skills/changelog",
            name = "changelog",
            description = "d",
            source = "alice/skills",
            installs = 1,
            instructions = "body",
        )
        val theirs = SkillListing(
            id = "bob/skills/changelog",
            name = "changelog",
            source = "bob/skills",
            installs = 2,
        )

        assertTrue("the same id is the same skill", theirs.copy(id = mine.id).isInstalled(listOf(mine)))
        assertTrue("a shared name is not", !theirs.isInstalled(listOf(mine)))
    }
}
