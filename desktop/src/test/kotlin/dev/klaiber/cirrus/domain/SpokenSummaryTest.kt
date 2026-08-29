package dev.klaiber.cirrus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of read-aloud that have to work when the model cannot be asked.
 *
 * The model-written summary is the good path and is not testable without a server. What is
 * asserted here is the path taken when that fails — no default model, a request past its deadline,
 * an empty reply — because that path is the one nobody notices until somebody presses play on a
 * long answer and hears nothing, or hears the whole thing after all.
 */
class SpokenSummaryTest {

    private fun paragraphs(count: Int, each: Int = 300): String =
        (1..count).joinToString("\n\n") { "Paragraph $it. " + "word ".repeat(each / 5) }

    // ---- The local fallback ------------------------------------------------------------------

    @Test
    fun `short text is already its own summary`() {
        val short = "The build passes. Two tests were failing on the timeout, and both now pass."

        assertEquals(short, condense(short))
    }

    /**
     * A written answer puts its subject first and its conclusion last, so both ends have to
     * survive. Keeping only the opening is the version that sounds fine and is wrong: the
     * recommendation lives at the bottom.
     */
    @Test
    fun `a long answer keeps its opening and its closing`() {
        val text = "Opening statement about the problem.\n\n" +
            paragraphs(8) + "\n\n" +
            "So the recommendation is to use the second approach."

        val spoken = condense(text, target = 400)

        assertTrue("the opening has to survive", spoken.startsWith("Opening statement"))
        assertTrue("and so does the conclusion", spoken.contains("the recommendation is"))
        assertFalse("the middle is what gets dropped", spoken.contains("Paragraph 5"))
    }

    /**
     * The listener has to be told a fuller version exists, or they will act on the summary as
     * though it were the whole answer — which, having heard it, is exactly what it looks like.
     */
    @Test
    fun `the listener is told the full answer is on screen`() {
        val spoken = condense(paragraphs(6), target = 300)

        assertTrue(spoken.contains("full answer is on screen"))
    }

    /** One enormous paragraph has no seam to cut at, and still must not be read out whole. */
    @Test
    fun `a single unbroken paragraph is cut at a word boundary`() {
        val wall = "sentence ".repeat(400)

        val spoken = condense(wall, target = 500)

        assertTrue(spoken.length < wall.length)
        assertTrue(spoken.contains("full answer is on screen"))
        assertFalse("never mid-word", spoken.contains("senten…"))
    }

    // ---- What the model is shown -------------------------------------------------------------

    @Test
    fun `a very long answer is clipped from the middle, not the end`() {
        val text = "THE BEGINNING\n" + "filler ".repeat(4_000) + "\nTHE CONCLUSION"

        val clipped = clipForSummary(text, max = 2_000)

        assertTrue(clipped.startsWith("THE BEGINNING"))
        assertTrue(clipped.endsWith("THE CONCLUSION"))
        assertTrue("the join has to be announced", clipped.contains("omitted"))
        // Two thirds head, one third tail, plus the marker.
        assertTrue(clipped.length < 2_200)
    }

    @Test
    fun `an answer within the limit is passed through untouched`() {
        val text = "Short enough to summarise whole."

        assertEquals(text, clipForSummary(text, max = 1_000))
    }

    // ---- Cleaning up what came back ----------------------------------------------------------

    /**
     * The engine would say every one of these out loud. A model told "no markdown" produces it
     * anyway often enough that stripping it is cheaper than re-prompting.
     */
    @Test
    fun `markdown a model produced despite being asked not to is stripped`() {
        val raw = """
            ## Summary
            - The first point, which matters.
            - The second point.

            **In short**: it works.
        """.trimIndent()

        val spoken = tidy(raw)

        assertFalse(spoken.contains("#"))
        assertFalse(spoken.contains("- "))
        assertFalse(spoken.contains("**"))
        assertTrue(spoken.contains("The first point, which matters."))
        assertTrue("it stays one spoken run, not a list", spoken.lines().size == 1)
    }

    /**
     * Reasoning lands in its own field only when the server parsed it. A model whose template emits
     * raw `<think>` would otherwise have its deliberation read aloud in place of the summary.
     */
    @Test
    fun `reasoning is not read out`() {
        assertEquals(
            "The answer is yes.",
            tidy("<think>Let me weigh this up.</think>The answer is yes."),
        )
        // An unclosed opener is reasoning that ran out of budget: everything after it is discarded.
        assertEquals("Here it is.", tidy("Here it is.<think>Now let me check"))
    }
}
