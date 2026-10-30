package com.offcast.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Respaldo local en Descargas/Offcast/offcast-backup.zip (sobrevive a desinstalar). */
object Backup {
    private const val NAME = "offcast-backup.zip"
    private const val REL_PATH = "Download/Offcast"

    fun has(ctx: Context): Boolean = findUri(ctx) != null || directFile().exists()

    fun findUri(ctx: Context): Uri? {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=?"
        val args = arrayOf(NAME)
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    private fun directFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Offcast/$NAME")

    fun export(ctx: Context): Boolean {
        return try {
            val resolver = ctx.contentResolver
            findUri(ctx)?.let { resolver.delete(it, null, null) }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, REL_PATH)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                val zos = ZipOutputStream(out)
                addFile(zos, "library.json", File(ctx.filesDir, "library.json"))
                addFile(zos, "listen.json", File(ctx.filesDir, "listen.json"))
                addFile(zos, "follows.json", File(ctx.filesDir, "follows.json"))
                val podDir = Core.podDir
                podDir.listFiles()?.forEach { f ->
                    zos.putNextEntry(ZipEntry("podcasts/${f.name}"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                zos.close()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, v, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun restore(ctx: Context): Boolean {
        val uri = findUri(ctx)
        return try {
            val input = if (uri != null) ctx.contentResolver.openInputStream(uri)
            else if (directFile().exists()) directFile().inputStream()
            else null
            input?.use { ins ->
                val zis = ZipInputStream(ins)
                var entry = zis.nextEntry
                while (entry != null) {
                    val target = when {
                        entry.name == "library.json" || entry.name == "listen.json" || entry.name == "follows.json" ->
                            File(ctx.filesDir, entry.name)
                        entry.name.startsWith("podcasts/") ->
                            File(Core.podDir, entry.name.removePrefix("podcasts/"))
                        else -> null
                    }
                    if (target != null) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            } ?: return false
            Core.reload()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun addFile(zos: ZipOutputStream, name: String, file: File) {
        if (!file.exists()) return
        zos.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }
}