package dev.klaiber.cirrus.domain.files

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's own Downloads folder, through MediaStore.
 *
 * MediaStore rather than a path, and that is the whole reason this class is not four lines. Since
 * API 29 an app cannot write into shared storage by opening a `File`; it inserts a row and writes
 * through the resolver, and what it gets back is a file the *system* owns — indexed, visible in
 * every file manager and share sheet, and left behind when Cirrus is uninstalled. That last part is
 * the point: a file the user asked for is theirs, not the app's.
 *
 * It needs no permission at all on API 29+, which is worth stating because the obvious alternative
 * — `WRITE_EXTERNAL_STORAGE` and a path — would need one, would be refused on modern Android, and
 * is the version of this that most code on the internet still shows.
 *
 * `IS_PENDING` brackets the write. Without it the row is visible from the moment it is inserted, so
 * a gallery or a file manager scanning at the wrong instant sees a zero-byte file and caches that
 * as the truth.
 */
@Singleton
class AndroidDownloadSink @Inject constructor(
    @ApplicationContext private val context: Context,
) : DownloadSink {

    override suspend fun save(
        source: File,
        displayName: String,
        mimeType: String?,
    ): SavedDownload? = withContext(Dispatchers.IO) {
        if (!source.exists() || source.length() == 0L) return@withContext null

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            // A blank or absent type makes some file managers refuse to open the file at all,
            // where the generic one at least offers a chooser.
            put(MediaStore.Downloads.MIME_TYPE, mimeType?.takeIf { it.isNotBlank() } ?: FALLBACK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return@withContext null

        val written = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: throw java.io.IOException("no output stream")
        }.isSuccess

        if (!written) {
            // Leaving a pending row behind would be an invisible zero-byte file forever.
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }

        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        }

        // MediaStore renames on collision rather than overwriting — `report.csv` becomes
        // `report (1).csv` — so what it settled on is read back rather than assumed. Telling
        // somebody to open a name that is not the one on disk is the whole failure this class is
        // here to avoid, one level down.
        val actual = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: displayName

        SavedDownload(name = actual, location = "Downloads/$actual")
    }

    private companion object {
        const val FALLBACK_MIME = "application/octet-stream"
    }
}
