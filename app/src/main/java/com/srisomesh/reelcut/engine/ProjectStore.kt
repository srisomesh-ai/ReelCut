package com.srisomesh.reelcut.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Timeline projects saved as JSON in the app's own storage (files/projects/<id>.json + <id>.jpg cover). */
object ProjectStore {

    private fun dir(ctx: Context) = File(ctx.filesDir, "projects").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    fun cover(ctx: Context, id: String) = File(dir(ctx), "$id.jpg")

    fun list(ctx: Context): List<Project> =
        (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.updated }

    fun load(ctx: Context, id: String): Project? =
        runCatching { fromJson(JSONObject(file(ctx, id).readText())) }.getOrNull()

    fun save(ctx: Context, p: Project) {
        val f = file(ctx, p.id)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(toJson(p).toString())
        tmp.renameTo(f)
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
        cover(ctx, id).delete()
    }

    fun newProject(ctx: Context, clips: List<TClip>): Project {
        val now = System.currentTimeMillis()
        val n = list(ctx).size + 1
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val base = Project(id, "Project $n", emptyList(), now, now)
        return TimelineEdits.add(base, 0, clips)?.first ?: base
    }

    /** Reads what the timeline needs about picked files (photo or video, video length) and keeps access to them. */
    fun probe(ctx: Context, uris: List<Uri>): List<TClip> = uris.mapNotNull { u ->
        try {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* not every picker allows it; the file still opens while the app runs */ }
        val type = ctx.contentResolver.getType(u) ?: ""
        val isImage = type.startsWith("image") ||
            (type.isEmpty() && u.toString().substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic"))
        if (isImage) TClip(0, u.toString(), true, 0.0, 0.0, TimelineEdits.PHOTO_DEFAULT)
        else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, u)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (ms < 300) null else TClip(0, u.toString(), false, ms / 1000.0, 0.0, ms / 1000.0)
            } catch (e: Exception) {
                Log.w("ReelCut", "cannot read $u", e); null
            } finally { r.release() }
        }
    }

    /** Small cover picture from the first clip, for the Recent list. */
    fun writeCover(ctx: Context, p: Project) {
        val c = p.clips.firstOrNull() ?: return
        val bmp: Bitmap? = try {
            val u = Uri.parse(c.uri)
            if (c.isImage) ctx.contentResolver.openInputStream(u)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, u)
                    r.getScaledFrameAtTime(((c.inS + c.dur / 2) * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                } finally { r.release() }
            }
        } catch (_: Exception) { null }
        bmp ?: return
        cover(ctx, p.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    private fun toJson(p: Project) = JSONObject().apply {
        put("v", 1)
        put("id", p.id); put("name", p.name); put("created", p.created); put("updated", p.updated)
        put("clips", JSONArray().apply {
            p.clips.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("uri", c.uri); put("img", c.isImage)
                    put("src", c.srcDur); put("in", c.inS); put("out", c.outS)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Project {
        val arr = o.getJSONArray("clips")
        val clips = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            TClip(c.getLong("id"), c.getString("uri"), c.getBoolean("img"), c.getDouble("src"), c.getDouble("in"), c.getDouble("out"))
        }
        return Project(o.getString("id"), o.getString("name"), clips, o.getLong("created"), o.getLong("updated"))
    }
}
