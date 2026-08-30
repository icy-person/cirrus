package dev.klaiber.cirrus.domain.tools.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The workspace, and the two promises it makes about cleaning up.
 *
 * Both are the kind of thing that is easy to write and easy to get subtly wrong — a sweep that
 * takes the topic somebody is working in, a name that resolves to somewhere else — and neither
 * fails loudly when it does. So they are asserted here rather than trusted.
 */
class ShellWorkspaceTest {

    private lateinit var root: File
    private lateinit var workspace: ShellWorkspace

    /**
     * One conversation's pad. Everything the old flat workspace did is now a scratchpad's job, so
     * the cases below are unchanged apart from what they are called on — which is itself the
     * assertion that the split did not quietly change any of the rules.
     */
    private lateinit var pad: Scratchpad

    @Before
    fun setUp() {
        root = Files.createTempDirectory("cirrus-workspace").toFile()
        workspace = ShellWorkspace(root)
        pad = workspace.scratchpad("conversation-one")
    }

    private fun write(topic: String, name: String, text: String = "x"): File =
        File(pad.topicDirectory(topic), name).apply { writeText(text) }


    @Test
    fun `a topic is its own directory`() {
        write("expenses", "totals.txt")
        write("log-counts", "counts.txt")

        assertEquals(listOf("expenses", "log-counts"), pad.topics().map { it.name }.sorted())
        assertEquals(listOf("totals.txt"), pad.topicEntries("expenses").map { it.path })
    }

    /** The point of normalising rather than rejecting: the model said something perfectly clear. */
    @Test
    fun `topic names are normalised into something safe`() {
        assertEquals("invoice-totals-q3", Scratchpad.topicName("Invoice Totals (Q3)"))
        assertEquals("scratch", Scratchpad.topicName(null))
        assertEquals("scratch", Scratchpad.topicName("   "))
        assertEquals("notes", Scratchpad.topicName("  notes  "))
    }

    /**
     * A topic name arrives as a tool argument, so it never passes through [CommandPolicy]. It has
     * to be unable to name anywhere else on its own.
     */
    @Test
    fun `a topic name cannot climb out of the workspace`() {
        val escape = Scratchpad.topicName("../../etc")

        assertFalse(escape.contains('/'))
        assertFalse(escape.contains(".."))
    }

    @Test
    fun `clearing one topic leaves the others alone`() {
        write("expenses", "totals.txt")
        write("expenses", "raw.csv")
        write("notes", "todo.txt")

        assertEquals(2, pad.clear("expenses"))
        assertEquals(listOf("notes"), pad.topics().map { it.name })
        assertEquals(1, pad.entries().count { !it.isDirectory })
    }

    @Test
    fun `clearing everything empties the workspace but keeps the directory`() {
        write("expenses", "totals.txt")
        write("notes", "todo.txt")

        assertEquals(2, pad.clear())
        assertTrue(pad.topics().isEmpty())
        assertTrue(workspace.directory().isDirectory)
    }

    @Test
    fun `the sweep retires topics nothing has touched, and says which`() {
        val stale = write("old-job", "note.txt")
        write("current-job", "note.txt")
        stale.setLastModified(System.currentTimeMillis() - 2 * Scratchpad.IDLE_MS)

        assertEquals(listOf("old-job"), pad.sweep(force = true))
        assertEquals(listOf("current-job"), pad.topics().map { it.name })
    }

    /**
     * The case idle time cannot answer: a session that opens a fresh topic every few minutes stays
     * inside the window forever, and is the flat scratch directory again with extra steps.
     */
    @Test
    fun `the sweep caps how many live topics there can be`() {
        // All well inside the idle window, so only the cap can be what removes any of them.
        val now = System.currentTimeMillis()
        val count = Scratchpad.MAX_TOPICS + 3
        repeat(count) { index ->
            write("job-$index", "note.txt").setLastModified(now - (count - index) * 1_000L)
        }

        val removed = pad.sweep(force = true)

        assertEquals(listOf("job-0", "job-1", "job-2"), removed)
        assertEquals(Scratchpad.MAX_TOPICS, pad.topics().size)
    }

    @Test
    fun `an untouched workspace sweeps to nothing`() {
        write("current-job", "note.txt")

        assertTrue(pad.sweep(force = true).isEmpty())
        assertEquals(1, pad.topics().size)
    }

    /**
     * The budget is a refusal, not a deletion, and that distinction is the whole point of it.
     *
     * [ShellWorkspace.trimTo] does enforce a cap, but it enforces it by deleting oldest-first
     * across every topic — so the price of one runaway command is somebody else's working files,
     * paid silently. Refusing the *next* command instead costs nothing that already exists and
     * tells the model something it can act on.
     */
    @Test
    fun `a topic over its byte budget refuses the next command and says which tool fixes it`() {
        write("build", "page.html", "x".repeat(3_000))

        assertNull("well under the cap", pad.budgetProblem("build", maxBytes = 10_000))

        val problem = pad.budgetProblem("build", maxBytes = 2_000)
        assertNotNull(problem)
        assertTrue("it has to name the topic", "build" in problem!!)
        assertTrue("and the way out", "clean_workspace" in problem)
    }

    /** Four hundred tiny files stay under any byte cap and are still not one job. */
    @Test
    fun `a topic over its file budget is caught even while it is small`() {
        repeat(6) { write("split", "part-$it.txt") }

        assertNull(pad.budgetProblem("split", maxFiles = 10))
        assertNotNull(pad.budgetProblem("split", maxFiles = 3))
    }

    @Test
    fun `an unused topic has no budget problem`() {
        assertNull(pad.budgetProblem("never-used"))
        assertNull(pad.budgetProblem(null))
    }

    // ---- One scratchpad per conversation -------------------------------------------------------

    /**
     * The bug the split exists for.
     *
     * Two threads both working in a topic called "notes" used to share one directory, so one
     * thread's files appeared in the other's listing and `clean_workspace` in either took both.
     * Nothing about that reads as a design decision from the transcript; it reads as the app losing
     * work.
     */
    @Test
    fun `two conversations working in the same topic do not see each other`() {
        val first = workspace.scratchpad("thread-one")
        val second = workspace.scratchpad("thread-two")

        File(first.topicDirectory("notes"), "mine.txt").writeText("first")
        File(second.topicDirectory("notes"), "theirs.txt").writeText("second")

        assertEquals(listOf("mine.txt"), first.topicEntries("notes").map { it.path })
        assertEquals(listOf("theirs.txt"), second.topicEntries("notes").map { it.path })

        // And clearing one leaves the other entirely alone.
        first.clear("notes")
        assertEquals(1, second.topicEntries("notes").size)
    }

    @Test
    fun `a topic cannot climb out of its own scratchpad`() {
        val pad = workspace.scratchpad("thread-one")
        val directory = pad.topicDirectory("../../etc")

        assertEquals(File(root, "c-thread-one"), directory.parentFile)
    }

    /** A call with no conversation behind it still works, rather than failing. */
    @Test
    fun `no conversation gets the shared pad`() {
        val shared = workspace.scratchpad(null)
        File(shared.topicDirectory("scratch"), "x.txt").writeText("x")

        assertEquals(1, shared.topicEntries("scratch").size)
        assertTrue(File(root, ShellWorkspace.SHARED_SCOPE).isDirectory)
    }

    // ---- Housekeeping runs between jobs, not during them ----------------------------------------

    /**
     * The sweep used to run before every single command, which is how files disappeared between
     * one step of a job and the next. Now a second call inside the window does nothing at all.
     */
    @Test
    fun `a sweep does not run again straight away`() {
        val thread = workspace.scratchpad("thread-one")
        val old = File(thread.topicDirectory("old-job"), "notes.txt").apply { writeText("x") }
        old.setLastModified(1_000L)

        assertEquals(listOf("old-job"), thread.sweep())

        // A second job's worth of commands, all inside the interval: nothing more may be taken,
        // however idle it looks. This is the whole of the fix — the old code swept here too.
        val second = File(thread.topicDirectory("second-job"), "data.txt").apply { writeText("x") }
        second.setLastModified(1_000L)

        assertTrue("the interval has not elapsed", thread.sweep().isEmpty())
        assertTrue(second.exists())
    }

    /**
     * Startup housekeeping replaced wiping everything, which lost the file you came back for.
     */
    @Test
    fun `pruning drops orphans and stale pads, and keeps what is still live`() {
        val live = workspace.scratchpad("still-here")
        val orphan = workspace.scratchpad("deleted-thread")
        File(live.topicDirectory("job"), "a.txt").writeText("a")
        File(orphan.topicDirectory("job"), "b.txt").writeText("b")

        val removed = workspace.prune(liveConversationIds = setOf("still-here"))

        assertEquals(listOf("c-deleted-thread"), removed)
        assertTrue(File(live.topicDirectory("job"), "a.txt").exists())
    }

    /** An empty set means "not loaded yet", not "the user deleted everything". */
    @Test
    fun `pruning with nothing known deletes nothing that is recent`() {
        val pad = workspace.scratchpad("thread-one")
        File(pad.topicDirectory("job"), "a.txt").writeText("a")

        assertTrue(workspace.prune(liveConversationIds = emptySet()).isEmpty())
        assertTrue(File(pad.topicDirectory("job"), "a.txt").exists())
    }

    // ---- Retention -------------------------------------------------------------------------------

    /**
     * The default, and the reason it changed.
     *
     * These files were swept on a timer when nothing in the app could show them, which made
     * "disposable" a decision taken on the user's behalf about work they had never been shown. With
     * a Files screen they are the user's, so nothing goes on a clock they did not set.
     */
    @Test
    fun `nothing is cleared by age under the default retention`() {
        val pad = workspace.scratchpad("thread-one")
        val old = File(pad.topicDirectory("ancient"), "notes.txt").apply { writeText("x") }
        old.setLastModified(1_000L)

        // Null is what `ScratchpadRetention.NEVER` supplies.
        assertTrue(workspace.prune(setOf("thread-one"), staleMs = null).isEmpty())
        assertTrue(old.exists())
    }

    /**
     * "Never" is about age, not about orphans. A scratchpad whose conversation has been deleted has
     * no screen that can reach it, so keeping it is a leak rather than a promise kept.
     */
    @Test
    fun `an orphan goes even when nothing is cleared by age`() {
        val orphan = workspace.scratchpad("deleted-thread")
        File(orphan.topicDirectory("job"), "b.txt").writeText("b")

        val removed = workspace.prune(setOf("still-here"), staleMs = null)

        assertEquals(listOf("c-deleted-thread"), removed)
    }

    /** Oldest first, so the file the last command wrote is not deleted to make room for itself. */
    @Test
    fun `trimming removes the oldest files until it fits`() {
        val old = write("job", "old.txt", "a".repeat(2_000))
        val new = write("job", "new.txt", "b".repeat(2_000))
        old.setLastModified(1_000L)
        new.setLastModified(System.currentTimeMillis())

        assertEquals(1, workspace.trimTo(maxBytes = 2_500))
        assertFalse(old.exists())
        assertTrue(new.exists())
    }
}
