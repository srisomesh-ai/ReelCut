package com.srisomesh.reelcut.engine

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Real YouTube search + download, only when RC_YT=1 (CI's information-only step; YouTube may block CI servers,
 * so it never fails the build). Writes app/build/yt-report.txt.
 */
class YouTubeLiveTest {
    @Test
    fun searchAndDownload() = runBlocking {
        if (System.getenv("RC_YT") != "1") return@runBlocking
        val report = File("build/yt-report.txt")
        val sb = StringBuilder()
        try {
            val r = YouTube.search("butta bomma song")
            sb.appendLine("YT search: ${r.size} results")
            r.take(5).forEach { sb.appendLine("YT  ${it.title} | ${it.channel} | ${it.seconds}s | thumb=${it.thumb != null}") }
            val f = YouTube.download(r.first(), File("build/yt")) {}
            sb.appendLine("YT download: ${f.name} ${f.length()} bytes")
        } catch (e: Throwable) {
            sb.appendLine("YT failed: ${e.javaClass.name}: ${e.message}")
            e.cause?.let { sb.appendLine("YT cause: ${it.javaClass.name}: ${it.message}") }
        }
        report.parentFile?.mkdirs()
        report.writeText(sb.toString())
    }
}
