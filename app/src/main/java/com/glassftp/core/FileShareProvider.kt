package com.glassftp.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Tiny stand-in for AndroidX FileProvider: content://com.glassftp.files/<absolute path>. */
class FileShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val f = File(uri.path ?: throw FileNotFoundException()).canonicalFile
        val ctx = context ?: throw FileNotFoundException()
        val allowed = listOfNotNull(
            ctx.cacheDir, ctx.filesDir, ctx.getExternalFilesDir(null),
            android.os.Environment.getExternalStorageDirectory(),
        ).map { it.canonicalPath }
        if (allowed.none { f.path.startsWith(it) }) throw FileNotFoundException("Not shareable")
        return f
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String = FileKind.mime(uri.lastPathSegment ?: "")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.glassftp.files"

        fun uriFor(@Suppress("UNUSED_PARAMETER") ctx: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).path(f.absolutePath).build()
    }
}
