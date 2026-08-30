package dev.klaiber.cirrus.domain.model

/**
 * How long a conversation's scratch files are kept before Cirrus clears them on its own.
 *
 * The default is [NEVER], and that is a deliberate reversal. These files used to be treated as
 * disposable — swept on an idle timer, wiped when the process started — on the reasoning that
 * nobody asked to keep them and a scratch directory that grows forever is a leak. What that missed
 * is that the user had no way to *see* them, so "disposable" was a decision made on their behalf
 * about work they had never been shown. Now that there is a Files screen and a download button, the
 * files are theirs, and deleting somebody's work on a timer they did not set is not a default
 * anybody should have to discover.
 *
 * "Never" is honest about one exception, and the help text says so: a total size cap still applies
 * as a last resort, because filling the device is the one outcome worse than losing a scratch file.
 * That is a storage backstop rather than a retention policy, and it deletes oldest-first only once
 * the whole workspace is over its limit.
 *
 * The same window does two jobs, which is why there is one number rather than two. It retires idle
 * *topics* inside a conversation, and it drops whole scratchpads whose conversation nobody has come
 * back to. Splitting those into separate settings would be asking the user a question about an
 * implementation detail.
 */
enum class ScratchpadRetention(
    val label: String,
    val description: String,
    /** How long a file survives untouched, or null when nothing is cleared on a timer. */
    val idleMs: Long?,
) {
    NEVER(
        label = "Never",
        description = "Files stay until you delete them. A total size cap still applies as a " +
            "last resort, so a runaway command cannot fill the device.",
        idleMs = null,
    ),
    ONE_DAY(
        label = "1 day",
        description = "Anything untouched for a day is cleared. Tidy, but a conversation you " +
            "come back to on Monday will have lost what you left in it on Friday.",
        idleMs = 24L * 60 * 60 * 1000,
    ),
    ONE_WEEK(
        label = "1 week",
        description = "Long enough to come back to a conversation and find your work; short " +
            "enough that nothing accumulates for months.",
        idleMs = 7L * 24 * 60 * 60 * 1000,
    ),
    ONE_MONTH(
        label = "1 month",
        description = "Effectively keeps everything you are still using, and eventually clears " +
            "what you are not.",
        idleMs = 30L * 24 * 60 * 60 * 1000,
    ),
    ;

    /** True when anything is cleared on a timer at all. */
    val isAutomatic: Boolean get() = idleMs != null

    companion object {
        val Default = NEVER

        fun fromName(value: String?): ScratchpadRetention =
            entries.firstOrNull { it.name == value } ?: Default
    }
}
