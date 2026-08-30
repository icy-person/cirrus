package dev.klaiber.cirrus.domain.tools.shell

import java.io.File

/**
 * Every scratch file the shell has, divided by the conversation that made it.
 *
 * A [Scratchpad] is one conversation's working directory, and the workspace is the shelf they sit
 * on. The split exists because a flat workspace shared by every thread was wrong in two ways that
 * both look, from the transcript, like the app losing your work. Two conversations both working in
 * a topic called "notes" wrote into the same directory, so one thread's files turned up in
 * another's listing and `clean_workspace` in either took both. And with one shared topic cap, a
 * busy conversation swept away the topics belonging to a thread nobody had touched that hour but
 * which the user was plainly still in the middle of.
 *
 * Scoping to the conversation fixes both without any new rules: a scratchpad's lifetime is its
 * thread's, its topic budget is its own, and cleaning up one cannot reach another. It also makes
 * the honest answer to "where did my file go?" available — it is in the thread you made it in.
 *
 * The whole tree lives under the app's own data directory on purpose. Work nobody asked to keep
 * belongs in a scratch space, and keeping it out of the user's documents is the point.
 *
 * Housekeeping is the workspace's job rather than something every command pays for. [prune] runs at
 * startup and drops scratchpads whose conversations are gone, plus anything nobody has touched in a
 * week; [trimTo] is the backstop that caps the total. Neither runs mid-turn. What a command still
 * pays for is [Scratchpad.budgetProblem], which refuses rather than deletes.
 */
class ShellWorkspace(private val root: File) {

    /** Creates the directory if it is not there, and hands it over. */
    fun directory(): File = root.apply { mkdirs() }

    /** Where files land, as the model should refer to it when explaining itself to the user. */
    val path: String get() = root.absolutePath

    /**
     * The scratchpad for one conversation.
     *
     * A null id — a tool called outside a turn, or a test — gets the shared pad rather than an
     * error. Nothing is lost by that: the shared pad behaves exactly like any other, and the
     * alternative is a tool that cannot run at all in the one situation nobody is watching.
     */
    fun scratchpad(conversationId: String?): Scratchpad =
        Scratchpad(File(root, scopeName(conversationId)))

    /** Every scratchpad currently on disk, most recently touched first. */
    fun scratchpads(): List<File> = (root.listFiles()?.asList() ?: emptyList())
        .filter { it.isDirectory }
        .sortedByDescending { it.lastModifiedDeeply() }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Every file in the workspace, for `system_info`'s summary. */
    fun entries(): List<Scratchpad.Entry> = root
        .walkTopDown()
        .filter { it != root }
        .map {
            Scratchpad.Entry(
                path = it.toRelativeString(root),
                isDirectory = it.isDirectory,
                sizeBytes = it.length(),
                modifiedAt = it.lastModified(),
            )
        }
        .sortedBy { it.path }
        .toList()

    /**
     * Startup housekeeping: drop what belongs to nothing, and what nobody has come back to.
     *
     * This replaces wiping the workspace on every process start, which was a backstop that cost
     * far more than it saved. A conversation is a thing you return to — the next morning, after an
     * update, after the machine decided to restart — and finding that the file you made yesterday is
     * gone because the app was closed in between is indistinguishable from a bug. Two rules that
     * cannot make that mistake: a scratchpad whose conversation has been deleted has nothing left
     * to belong to, and one nobody has touched in [staleMs] is not a job in hand.
     *
     * [liveConversationIds] being empty is treated as "not known yet" rather than as "every thread
     * was deleted". A repository that has not finished loading must never look like a user who
     * cleared their history, because the two would be told apart only by the files that had
     * already gone.
     */
    fun prune(
        liveConversationIds: Set<String>,
        staleMs: Long = STALE_MS,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val removed = mutableListOf<String>()
        scratchpads().forEach { directory ->
            val id = directory.name
                .takeIf { it.startsWith(SCOPE_PREFIX) }
                ?.removePrefix(SCOPE_PREFIX)
            val orphaned = id != null &&
                liveConversationIds.isNotEmpty() &&
                id !in liveConversationIds
            val stale = now - directory.lastModifiedDeeply() > staleMs
            if ((orphaned || stale) && directory.deleteRecursively()) removed += directory.name
        }
        return removed
    }

    /** Everything, for the wipe a user can still ask for. */
    fun clear(): Int {
        if (!root.exists()) return 0
        val removed = root.walkTopDown().count { it != root && it.isFile }
        root.deleteRecursively()
        root.mkdirs()
        return removed
    }

    /**
     * Deletes oldest-first until the whole workspace fits in [maxBytes].
     *
     * Oldest rather than largest: the one big file a command has just written is usually the point
     * of the command, and deleting it to make room for itself is the one behaviour that would be
     * worse than doing nothing.
     */
    fun trimTo(maxBytes: Long = MAX_BYTES): Int {
        var used = usedBytes()
        if (used <= maxBytes) return 0

        var removed = 0
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (used <= maxBytes) return@forEach
                val size = file.length()
                if (file.delete()) {
                    used -= size
                    removed++
                }
            }
        return removed
    }

    companion object {
        /** Generous for text, small enough that a mistake is not a storage incident. */
        const val MAX_BYTES: Long = 16L * 1024 * 1024

        /**
         * How long a scratchpad survives with nobody touching it.
         *
         * A week, where the old idle sweep used forty-five minutes. That number was chosen when a
         * topic was a job inside one sitting; a scratchpad is a conversation, and people come back
         * to conversations days later expecting to find what they left in them.
         */
        const val STALE_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Where a call with no conversation behind it lands. */
        const val SHARED_SCOPE = "shared"

        private const val SCOPE_PREFIX = "c-"

        /**
         * A conversation id, made safe as a directory name.
         *
         * Ids are UUIDs, so this normally changes nothing — but an id reaches here from a stored
         * row rather than from a command, so it never passes [CommandPolicy], and the same rule
         * that stops a topic name meaning somewhere else has to apply to it too.
         */
        fun scopeName(conversationId: String?): String {
            val slug = conversationId.orEmpty().filter { it.isLetterOrDigit() || it == '-' }.take(48)
            return if (slug.isEmpty()) SHARED_SCOPE else "$SCOPE_PREFIX$slug"
        }
    }
}

/** The newest thing anywhere inside, or the directory's own stamp when it holds no files. */
private fun File.lastModifiedDeeply(): Long =
    walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: lastModified()

/**
 * One conversation's scratch files, divided by job.
 *
 * Every command runs with a topic directory as its working directory, and [CommandPolicy] refuses
 * absolute paths and `..`, so "the topic" and "everywhere this command can reach" are the same
 * place. That is what makes the write-capable programs — `rm`, `mv`, `tee`, `sed -i` — safe to
 * offer at all: the worst a mistake can do is destroy scratch files that were never meant to
 * outlive the conversation, and it cannot even reach the ones belonging to a different job, or to
 * a different thread.
 *
 * Topics exist because the models use this constantly, and a single flat directory turns into a
 * pile of `out.txt`, `out2.txt`, `tmp.txt` within one long session — at which point the model
 * starts reading the wrong file, or refuses to overwrite its own. A topic is a name for the job in
 * hand (`invoice-totals`, `log-counts`); files inside one belong together, and cleaning up is a
 * decision about a job rather than about a filename.
 *
 * Cleaning up no longer happens on every command. [sweep] still retires topics nobody has come back
 * to, but it is rate-limited to [SWEEP_INTERVAL_MS] and its idle window is a day — because a sweep
 * running before every single command was deleting files between one step of a job and the next.
 * The model wrote `totals.csv`, spent two commands thinking about it, and found it gone.
 * [budgetProblem] is the rule that still applies every time, and it refuses rather than deleting.
 */
class Scratchpad(private val root: File) {

    /** Where these files live, for a tool that has to explain itself. */
    val path: String get() = root.absolutePath

    /**
     * The directory for one topic, created if this is its first command.
     *
     * The name is normalised rather than rejected. A model that asks for "Invoice Totals (Q3)" has
     * said something perfectly clear about which job it means, and answering that with an error
     * spends a round trip teaching it a naming convention instead of doing the work.
     */
    fun topicDirectory(name: String?): File =
        File(root, topicName(name)).apply { mkdirs() }

    /** Every topic that currently has a directory, most recently touched first. */
    fun topics(): List<Topic> = (root.listFiles()?.asList() ?: emptyList())
        .filter { it.isDirectory }
        .map { directory ->
            val files = directory.walkTopDown().filter { it.isFile }.toList()
            Topic(
                name = directory.name,
                fileCount = files.size,
                sizeBytes = files.sumOf { it.length() },
                // The directory's own timestamp is the fallback: an empty topic was still touched
                // when it was made, and treating it as timeless would sweep it on the next command.
                modifiedAt = files.maxOfOrNull { it.lastModified() } ?: directory.lastModified(),
            )
        }
        .sortedByDescending { it.modifiedAt }

    /** Every file in this scratchpad, deepest last, with sizes. */
    fun entries(): List<Entry> = root
        .walkTopDown()
        .filter { it != root && it.name != SWEEP_MARKER }
        .map { Entry(it.toRelativeString(root), it.isDirectory, it.length(), it.lastModified()) }
        .sortedBy { it.path }
        .toList()

    /** Files in one topic, relative to that topic, so the model sees the names it wrote. */
    fun topicEntries(topic: String?): List<Entry> {
        val directory = File(root, topicName(topic))
        if (!directory.isDirectory) return emptyList()
        return directory
            .walkTopDown()
            .filter { it != directory }
            .map {
                Entry(it.toRelativeString(directory), it.isDirectory, it.length(), it.lastModified())
            }
            .sortedBy { it.path }
            .toList()
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Empties this scratchpad, or one topic of it, and reports how many files went.
     *
     * The directory itself is recreated rather than left missing: a working directory that does not
     * exist makes the *next* command fail with an error about the shell rather than about itself.
     */
    fun clear(topic: String? = null): Int {
        val target = if (topic == null) root else File(root, topicName(topic))
        if (!target.exists()) return 0
        val removed = target.walkTopDown().count { it != target && it.isFile && it.name != SWEEP_MARKER }
        target.deleteRecursively()
        if (target == root) root.mkdirs()
        return removed
    }

    /**
     * Retires topics nobody is working on any more, at most once every [SWEEP_INTERVAL_MS].
     *
     * The rate limit is the fix for what this used to get wrong. Running before every single
     * command meant the workspace was re-examined dozens of times in a turn, and a topic that
     * crossed the idle line between two steps of one job vanished underneath the model — which
     * reads, from the transcript, exactly like the app losing a file. Now a job of any realistic
     * length runs from start to finish without a sweep happening at all, and the sweep that does
     * eventually run is looking at a day of idleness rather than three quarters of an hour.
     *
     * Reporting rather than doing it silently stays: the model may be about to read a file from a
     * topic that has just been swept, and "invoice-totals was cleaned up" is something it can act
     * on, where a file that has quietly stopped existing is a puzzle it will spend a turn on.
     */
    fun sweep(
        idleMs: Long = IDLE_MS,
        maxTopics: Int = MAX_TOPICS,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): List<String> {
        if (!force && now - lastSweptAt() < SWEEP_INTERVAL_MS) return emptyList()
        markSwept(now)

        val topics = topics()
        val stale = topics.filter { now - it.modifiedAt > idleMs }
        // Newest first, so the ones over the cap are the oldest survivors.
        val overCap = (topics - stale.toSet()).drop(maxTopics)

        return (stale + overCap)
            .filter { File(root, it.name).deleteRecursively() }
            .map { it.name }
            .sorted()
    }

    /**
     * Why this topic may not be written to any further, or null while it may.
     *
     * Checked *before* a command rather than enforced after one, because after is too late to be
     * useful: [ShellWorkspace.trimTo] does run and does delete, but it deletes oldest-first across
     * the whole workspace, which means the price of one runaway command is somebody else's job. A
     * refusal in front is the version the model can act on — it names the topic, the size, and the
     * tool that fixes it, and the very next call is a `clean_workspace` rather than another
     * megabyte.
     *
     * Two caps rather than one, because they catch different mistakes. Bytes catch the model that
     * has decided to write a website into a scratch directory. The file count catches the one
     * writing `part-001.txt` through `part-400.txt`, which stays well under any byte cap while
     * making the topic listing — and the model's own picture of what it has — useless.
     */
    fun budgetProblem(
        topic: String?,
        maxBytes: Long = MAX_TOPIC_BYTES,
        maxFiles: Int = MAX_TOPIC_FILES,
    ): String? {
        val name = topicName(topic)
        val files = File(root, name).walkTopDown().filter { it.isFile }.toList()
        val bytes = files.sumOf { it.length() }

        return when {
            bytes > maxBytes -> "the \"$name\" topic already holds ${bytes / 1024}KB, over its " +
                "${maxBytes / 1024}KB limit. The workspace is a scratch pad for small text jobs, " +
                "not somewhere to assemble a large file. Call clean_workspace with this topic — " +
                "or a different topic for unrelated work — and put anything worth keeping in " +
                "your answer, where the user can actually read it."

            files.size > maxFiles -> "the \"$name\" topic already holds ${files.size} files, over " +
                "its limit of $maxFiles. A job that needs more than that has stopped being one " +
                "job: call clean_workspace with this topic, then work in fewer, larger steps."

            else -> null
        }
    }

    /**
     * When the last sweep ran, kept as a marker file rather than in memory.
     *
     * In memory it would reset on every process start — which is exactly when somebody reopens a
     * conversation, so the first command of the new session would sweep the files they came back
     * for. On disk it survives, which is the whole point of rate-limiting it at all.
     */
    private fun lastSweptAt(): Long =
        File(root, SWEEP_MARKER).takeIf { it.exists() }?.lastModified() ?: 0L

    private fun markSwept(now: Long) {
        runCatching {
            root.mkdirs()
            File(root, SWEEP_MARKER).apply {
                if (!exists()) createNewFile()
                setLastModified(now)
            }
        }
    }

    data class Entry(
        val path: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val modifiedAt: Long,
    )

    /** One job's worth of scratch files. */
    data class Topic(
        val name: String,
        val fileCount: Int,
        val sizeBytes: Long,
        val modifiedAt: Long,
    )

    companion object {
        /** Where a command lands when it did not say which job it belongs to. */
        const val DEFAULT_TOPIC = "scratch"

        /**
         * How long a topic survives untouched.
         *
         * A day, where it used to be forty-five minutes. A conversation left over lunch and picked
         * up in the afternoon is the ordinary case here, not an abandoned one.
         */
        const val IDLE_MS: Long = 24L * 60 * 60 * 1000

        /** Past this many live topics in one conversation, the oldest is not a job in hand. */
        const val MAX_TOPICS = 16

        /**
         * The least often a sweep is worth doing.
         *
         * The point is that no realistic job spans one: a sequence of commands working on the same
         * files runs from start to finish without anything being deleted underneath it.
         */
        const val SWEEP_INTERVAL_MS: Long = 30L * 60 * 1000

        /**
         * What one job may hold before the next command is refused.
         *
         * Well under [ShellWorkspace.MAX_BYTES], and that gap is the design: the workspace cap is a
         * backstop that deletes, and this one is a refusal that explains. Two megabytes is several
         * novels' worth of text — anything asking for more here is building something, and a
         * build belongs in the user's own terminal.
         */
        const val MAX_TOPIC_BYTES: Long = 8L * 1024 * 1024

        /** Past this many files, a topic has stopped being one job. */
        const val MAX_TOPIC_FILES = 80

        /** Hidden, so it never appears in a listing the model reads. */
        private const val SWEEP_MARKER = ".swept"

        private const val MAX_TOPIC_LENGTH = 32

        /**
         * A topic name that is safe as a directory name, and still recognisable as what was asked
         * for.
         *
         * Lowercase ASCII, digits and single dashes, and nothing else — which incidentally disposes
         * of `..`, `/`, leading dots and every other way a name could mean somewhere else. The
         * policy would refuse those in a command anyway, but a topic arrives as a tool argument
         * rather than as a command word, so it never passes that check.
         */
        fun topicName(raw: String?): String {
            val slug = buildString {
                raw.orEmpty().trim().lowercase().forEach { char ->
                    when {
                        char in 'a'..'z' || char in '0'..'9' -> append(char)
                        endsWith('-') -> Unit
                        isNotEmpty() -> append('-')
                    }
                }
            }.trim('-').take(MAX_TOPIC_LENGTH).trim('-')

            return slug.ifEmpty { DEFAULT_TOPIC }
        }
    }
}
