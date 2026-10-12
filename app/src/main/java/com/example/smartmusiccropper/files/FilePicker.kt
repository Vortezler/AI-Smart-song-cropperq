package com.example.smartmusiccropper.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract

/** Storage Access Framework helpers: picking the source file and choosing where to save. */
object FilePicker {

    /** Filter for the open picker. Anything audio is pickable; unsupported codecs get a clear error after. */
    val AUDIO_MIME_TYPES = arrayOf("audio/*")

    const val OUTPUT_MIME_TYPE = "audio/mp4"

    /**
     * Keeps access to [uri] across process death / restarts. Write access is requested when the
     * provider granted it (needed for "Overwrite original"); falls back to read-only.
     */
    fun takePersistablePermission(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, rw)
        } catch (e: SecurityException) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /**
     * Best-effort human-readable location for a SAF document, e.g. "Internal storage › Music".
     * Returns null when the provider's document IDs don't encode a path.
     */
    fun describeFolder(context: Context, uri: Uri): String? = runCatching {
        if (!DocumentsContract.isDocumentUri(context, uri)) return@runCatching null
        when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                // Document IDs look like "primary:Music/Album/song.m4a".
                val docId = DocumentsContract.getDocumentId(uri)
                val volume = docId.substringBefore(':')
                val path = docId.substringAfter(':', "").substringBeforeLast('/', "")
                val root = if (volume == "primary") "Internal storage" else volume
                listOf(root).plus(path.split('/').filter { it.isNotEmpty() }).joinToString(" › ")
            }
            "com.android.providers.downloads.documents" -> "Downloads"
            else -> null
        }
    }.getOrNull()

    /** `song.mp3` → `song_cropped.m4a`. */
    fun croppedFileName(originalName: String): String = "${originalName.substringBeforeLast('.')}_cropped.m4a"

    /** `song.mp3` → `song.m4a`. */
    fun m4aFileName(originalName: String): String = "${originalName.substringBeforeLast('.')}.m4a"

    data class CreateRequest(val suggestedName: String, val initialLocation: Uri?)

    /**
     * ACTION_CREATE_DOCUMENT for an .m4a, opening in the original file's folder when the provider
     * supports it, so the new file lands alongside the original by default.
     */
    class CreateM4aDocument : ActivityResultContract<CreateRequest, Uri?>() {
        override fun createIntent(context: Context, input: CreateRequest): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(OUTPUT_MIME_TYPE)
                .putExtra(Intent.EXTRA_TITLE, input.suggestedName)
                .apply { input.initialLocation?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
    }
}
