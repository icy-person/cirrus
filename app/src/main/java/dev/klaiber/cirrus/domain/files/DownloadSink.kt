package dev.klaiber.cirrus.domain.files

import java.io.File

/**
 * Where a downloaded file goes so that the *user* can open it.
 *
 * This exists because the first version of `download_file` did not have it, and the result was a
 * tool that reported success and delivered nothing. It saved into the shell's scratch workspace —
 * which is correct for the model, since that is the only place `run_command` can read — and the
 * workspace is inside the app's private cache, so the person who asked for the file had no way to
 * reach it. "Downloaded to expenses/report.csv" is a true sentence describing a file they cannot
 * open, which is worse than a failure: a failure would at least have been actionable.
 *
 * So a download now goes to two places, and neither is optional. The scratchpad copy is the model's
 * working copy and gets swept like anything else there; the copy this interface makes is the
 * user's, lands in the same folder every other download on their device lands in, and outlives the
 * conversation. Two writes of a file capped at a couple of megabytes is not a cost worth designing
 * around.
 *
 * Platform-specific because "the Downloads folder" is: Android reaches it through MediaStore, which
 * needs no permission on API 29+ and makes the file visible to every file manager and share sheet;
 * the desktop writes to `~/Downloads` like everything else does.
 */
interface DownloadSink {

    /**
     * Copies [source] into the user's downloads, and reports where it landed.
     *
     * Returns null when the platform refused — a full disk, a revoked MediaStore volume, a Downloads
     * directory that is not writable. Null rather than an exception because the caller is a tool in
     * the middle of a turn, and the working copy has already succeeded by this point: the honest
     * report is "here is the file, and it is not in your Downloads", not a failed download.
     */
    suspend fun save(source: File, displayName: String, mimeType: String?): SavedDownload?
}

/**
 * Where a file ended up, described the way the user would find it.
 *
 * [location] is for a person to read — "Downloads/report.csv" — rather than an absolute path, which
 * on Android is not something anybody can act on and on the desktop is longer than it is useful.
 * [name] is separate because the sink may have had to rename the file to avoid clobbering one that
 * was already there, and the model has to tell the user what it is actually called.
 */
data class SavedDownload(val name: String, val location: String)
