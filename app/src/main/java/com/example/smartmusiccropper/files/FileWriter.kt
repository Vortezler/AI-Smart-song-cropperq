package com.example.smartmusiccropper.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Writes the encoded .m4a to its destination: a new document, or over the original. */
object FileWriter {

    sealed interface OverwriteResult {
        /** The original now holds the cropped audio. [uri]/[name] reflect a rename to .m4a if one happened. */
        data class Overwritten(val uri: Uri, val name: String, val renamed: Boolean) : OverwriteResult

        /** The provider doesn't allow writing to this file; caller should fall back to a new file. */
        data object NotWritable : OverwriteResult
    }

    /** Copies [source] into the document at [target] (truncating it). */
    fun writeToDocument(context: Context, source: File, target: Uri) {
        val out = context.contentResolver.openOutputStream(target, "wt")
            ?: throw IOException("Couldn't open the destination for writing.")
        out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
    }

    /**
     * Replaces [original]'s contents with [source]. Then, because the bytes are now AAC/M4A, tries to
     * rename the document to `.m4a` so players don't misread it; if the provider can't rename, the
     * old name is kept.
     */
    fun overwriteOriginal(context: Context, source: File, original: Uri, originalName: String): OverwriteResult {
        val out = try {
            context.contentResolver.openOutputStream(original, "wt")
        } catch (e: SecurityException) {
            null
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        } ?: return OverwriteResult.NotWritable

        out.use { stream -> source.inputStream().use { it.copyTo(stream) } }

        val m4aName = FilePicker.m4aFileName(originalName)
        if (m4aName == originalName || !DocumentsContract.isDocumentUri(context, original)) {
            return OverwriteResult.Overwritten(original, originalName, renamed = false)
        }
        val renamed = runCatching {
            DocumentsContract.renameDocument(context.contentResolver, original, m4aName)
        }.getOrNull()
        return if (renamed != null) {
            OverwriteResult.Overwritten(renamed, FilePicker.displayName(context, renamed) ?: m4aName, renamed = true)
        } else {
            OverwriteResult.Overwritten(original, originalName, renamed = false)
        }
    }
}
