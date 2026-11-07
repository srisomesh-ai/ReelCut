package com.srisomesh.reelcut

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exported reels kept inside the app (for Recent projects) plus a copy in the phone gallery. */
object Library {

    class Item(val file: File, val thumb: File, val label: String)

    private fun dir(ctx: Context) = File(ctx.filesDir, "reels").apply { mkdirs() }

    fun newFile(ctx: Context): File =
        File(dir(ctx), "ReelCut_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")

    fun list(ctx: Context): List<Item> =
        (dir(ctx).listFiles { f -> f.extension == "mp4" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val base = f.path.removeSuffix(".mp4")
                val label = File("$base.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { "Reel" }
                Item(f, File("$base.jpg"), label)
            }

    /** Saves the label and a small cover picture next to the reel. */
    fun finish(file: File, label: String) {
        val base = file.path.removeSuffix(".mp4")
        File("$base.txt").writeText(label)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
            val small = Bitmap.createScaledBitmap(frame, 270, 480, true)
            File("$base.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        } catch (_: Exception) {
        } finally {
            r.release()
        }
    }

    fun saveToGallery(ctx: Context, file: File): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ReelCut")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not save to the gallery")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun delete(item: Item) {
        val base = item.file.path.removeSuffix(".mp4")
        item.file.delete(); File("$base.jpg").delete(); File("$base.txt").delete()
    }
}
