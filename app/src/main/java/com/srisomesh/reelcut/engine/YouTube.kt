package com.srisomesh.reelcut.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Song search on YouTube inside the app (owner, 23 Nov): search, listen, and download only the sound to the phone.
 * Uses the NewPipe Extractor library (no YouTube account or key). YouTube changes its site from time to time;
 * when search or download stops working, the library version in app/build.gradle.kts needs an update.
 */
object YouTube {

    data class Item(val url: String, val title: String, val channel: String, val seconds: Long, val thumb: String?)

    /** A playable / downloadable sound of one video, with the user agent its address needs. */
    data class Audio(val url: String, val userAgent: String, val ext: String)

    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private val LOC = Localization("en", "IN")

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var ready = false

    @Synchronized
    private fun init() {
        if (ready) return
        NewPipe.init(Net, LOC, ContentCountry("IN"))
        ready = true
    }

    /** NewPipe's network calls through OkHttp. */
    private object Net : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val b = okhttp3.Request.Builder().method(request.httpMethod(), body).url(request.url()).addHeader("User-Agent", BROWSER_UA)
            request.headers().forEach { (name, values) ->
                b.removeHeader(name)
                values.forEach { b.addHeader(name, it) }
            }
            http.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("YouTube asked for a check", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    /** The same results as searching on YouTube (YouTube Music's song search matched names badly). */
    suspend fun search(query: String): List<Item> = withContext(Dispatchers.IO) {
        init()
        find(query, YoutubeSearchQueryHandlerFactory.VIDEOS)
    }

    private fun find(query: String, filter: String): List<Item> {
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>()
            .filter { it.duration in 10..900 }   // songs, not shorts or 3-hour mixes
            .map { s ->
                val thumb = s.thumbnails.sortedBy { it.height }.let { l -> l.firstOrNull { it.height >= 90 } ?: l.lastOrNull() }?.url
                Item(s.url, s.name, s.uploaderName ?: "", s.duration, thumb)
            }
    }

    /** The best sound-only stream of a video (m4a when there is one). */
    suspend fun audio(url: String): Audio = withContext(Dispatchers.IO) {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val streams = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val pick: AudioStream = streams.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: streams.maxByOrNull { it.averageBitrate }
            ?: throw IllegalArgumentException("This song cannot be downloaded. Try another one.")
        val u = pick.content
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(u)) YoutubeParsingHelper.getVisionOsUserAgent(LOC) else BROWSER_UA
        Audio(u, ua, if (pick.format == MediaFormat.M4A) "m4a" else "webm")
    }

    /** Downloads the sound of [item] into [dir] in 1 MB pieces; [onProgress] 0..1. */
    suspend fun download(item: Item, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val a = audio(item.url)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "youtube_song.${a.ext}")
        val chunk = 1L shl 20
        var pos = 0L
        var total = -1L
        out.outputStream().use { os ->
            while (total < 0 || pos < total) {
                coroutineContext.ensureActive()
                val req = okhttp3.Request.Builder().url(a.url)
                    .header("User-Agent", a.userAgent)
                    .header("Range", "bytes=$pos-${pos + chunk - 1}")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (r.code != 206 && r.code != 200) throw IllegalStateException("Download stopped (${r.code}). Try again.")
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    if (r.code == 200) {
                        os.write(bytes); pos += bytes.size; total = pos   // whole file in one go
                    } else {
                        total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (pos + bytes.size)
                        if (bytes.isEmpty()) throw IllegalStateException("Download stopped. Try again.")
                        os.write(bytes); pos += bytes.size
                    }
                }
                if (total > 0) onProgress((pos.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        if (out.length() < 20_000) throw IllegalStateException("The song did not download. Try another one.")
        out
    }
}
