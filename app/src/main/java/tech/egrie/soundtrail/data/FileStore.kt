package tech.egrie.soundtrail.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * Songs the user imported from their own device storage (for example audio they
 * produced or downloaded themselves with external tools). Files are copied into
 * private app storage and shared read-only through FileProvider when played.
 */
class FileStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "audio").apply { mkdirs() }

    /** Copies the picked audio into private storage and returns the stored file name. */
    fun importAudio(uri: Uri): String? {
        val name = "${System.nanoTime()}${extensionFor(uri)}"
        return runCatching {
            val target = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (target.length() == 0L) {
                target.delete()
                return null
            }
            name
        }.getOrNull()
    }

    fun openUri(name: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, name))

    fun delete(name: String) {
        runCatching { File(dir, name).delete() }
    }

    private fun extensionFor(uri: Uri): String {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        when (type) {
            "audio/mpeg", "audio/mp3" -> return ".mp3"
            "audio/mp4", "audio/m4a", "audio/aac" -> return ".m4a"
            "audio/ogg", "audio/vorbis", "audio/opus" -> return ".ogg"
            "audio/flac", "audio/x-flac" -> return ".flac"
            "audio/wav", "audio/x-wav" -> return ".wav"
        }
        val display = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull().orEmpty()
        val candidate = display.substringAfterLast('.', "")
        return if (candidate.matches(Regex("[A-Za-z0-9]{1,5}"))) ".$candidate" else ".mp3"
    }
}
