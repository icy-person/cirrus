package dev.klaiber.cirrus.domain.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's `~/Downloads`, or the closest thing this machine has to one.
 *
 * A plain file copy, which is all a desktop needs — there is no MediaStore to insert into and no
 * permission to hold. What it does have to get right is the two things a naive copy gets wrong: it
 * must not silently overwrite a file the user already had, and it must not fail outright when
 * `~/Downloads` does not exist, which is normal on a fresh Linux install and on any account whose
 * locale names it something else.
 *
 * The fallback is the home directory rather than the app's data folder. A file in `~` is findable;
 * a file in `~/.local/share/Cirrus` is the same problem this class exists to solve.
 */
class DesktopDownloadSink(
    private val home: File = File(System.getProperty("user.home") ?: "."),
) : DownloadSink {

    override suspend fun save(
        source: File,
        displayName: String,
        mimeType: String?,
    ): SavedDownload? = withContext(Dispatchers.IO) {
        if (!source.exists() || source.length() == 0L) return@withContext null

        val directory = File(home, "Downloads").takeIf { it.isDirectory || it.mkdirs() } ?: home
        val destination = uniqueIn(directory, displayName) ?: return@withContext null

        val copied = runCatching { source.copyTo(destination, overwrite = false) }.isSuccess
        if (!copied) return@withContext null

        SavedDownload(
            name = destination.name,
            // Relative to home when it is under it, which is how anybody would say where it is.
            location = destination.relativeToOrNull(home)?.path ?: destination.absolutePath,
        )
    }

    /**
     * `report.csv`, then `report (1).csv`, and so on — the convention every browser uses.
     *
     * Overwriting would be the one behaviour with no way back: the file being replaced is the
     * user's, not ours, and nothing in a chat turn knows whether it mattered.
     */
    private fun uniqueIn(directory: File, name: String): File? {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }

        File(directory, name).takeIf { !it.exists() }?.let { return it }
        for (index in 1..MAX_ATTEMPTS) {
            File(directory, "$base ($index)$extension").takeIf { !it.exists() }?.let { return it }
        }
        return null
    }

    private companion object {
        const val MAX_ATTEMPTS = 99
    }
}
