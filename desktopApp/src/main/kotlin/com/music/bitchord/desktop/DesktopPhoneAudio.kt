package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

internal data class PhoneByteRange(val start: Long, val end: Long, val partial: Boolean) {
    val count: Long get() = end - start + 1
    companion object {
        fun parse(header: String?, length: Long): PhoneByteRange {
            require(length > 0) { "Empty audio file." }
            if (header.isNullOrBlank()) return PhoneByteRange(0, length - 1, false)
            val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header.trim())
                ?: throw PhoneRemoteError(416, "Use a single audio range.")
            val first = match.groupValues[1]; val last = match.groupValues[2]
            val start: Long
            val end: Long
            if (first.isEmpty()) {
                val count = last.toLongOrNull()?.takeIf { it > 0 } ?: throw PhoneRemoteError(416, "Invalid audio range.")
                start = (length - count).coerceAtLeast(0); end = length - 1
            } else {
                start = first.toLongOrNull() ?: throw PhoneRemoteError(416, "Invalid audio range.")
                end = if (last.isEmpty()) length - 1 else last.toLongOrNull()?.coerceAtMost(length - 1) ?: throw PhoneRemoteError(416, "Invalid audio range.")
            }
            if (start >= length || end < start) throw PhoneRemoteError(416, "Audio range is outside the file.")
            return PhoneByteRange(start, end, true)
        }
    }
}

internal interface PhoneAudioSource {
    val length: Long
    val mime: String
    fun write(range: PhoneByteRange, output: OutputStream, active: () -> Boolean)
}

/** Original encoded audio, served in ranges. No second decode engine and no full-track RAM buffer. */
internal class DesktopPhoneAudio(
    private val resolver: suspend (Song, Int) -> Pair<String, Map<String, String>> = { song, quality ->
        val found = StreamResolver.resolveForDownload(song.videoId, quality, requireM4a = true)
        found.url to StreamResolver.mediaHeadersFor(found.url)
    },
) {
    private data class Cached(val time: Long, val source: PhoneAudioSource)
    private val sources = ConcurrentHashMap<String, Cached>()
    val streams = Semaphore(4)
    suspend fun source(song: Song, quality: Int): PhoneAudioSource = withContext(Dispatchers.IO) {
        val key = "${song.videoId}:$quality"
        val now = System.currentTimeMillis()
        sources[key]?.takeIf { now - it.time < 10 * 60 * 1000 }?.let { return@withContext it.source }
        val local = song.localPath?.let { runCatching { Path.of(it) }.getOrNull() }
        val source = if (local != null && Files.isRegularFile(local) && local.fileName.toString().substringAfterLast('.').lowercase() in setOf("m4a", "mp4", "mp3", "wav", "flac", "aac")) {
            val mime = when (local.fileName.toString().substringAfterLast('.').lowercase()) { "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; "aac" -> "audio/aac"; else -> "audio/mp4" }
            object : PhoneAudioSource {
                override val length = Files.size(local)
                override val mime = mime
                override fun write(range: PhoneByteRange, output: OutputStream, active: () -> Boolean) {
                    java.io.RandomAccessFile(local.toFile(), "r").use { input ->
                        input.seek(range.start)
                        val bytes = ByteArray(64 * 1024); var left = range.count
                        while (left > 0 && active()) {
                            val count = input.read(bytes, 0, minOf(left, bytes.size.toLong()).toInt())
                            if (count < 0) break
                            output.write(bytes, 0, count); left -= count
                        }
                    }
                }
            }
        } else {
            require(Regex("[A-Za-z0-9_-]{11}").matches(song.videoId)) { "This phone cannot play that file format or source." }
            val (url, headers) = resolver(song, quality)
            val uri = java.net.URI(url)
            require(uri.scheme == "https" && (uri.host.orEmpty().endsWith(".googlevideo.com") || uri.host.orEmpty().endsWith(".googleusercontent.com"))) { "The mobile stream host is not supported." }
            val probe = DesktopRangeStream(url, headers)
            check(probe.read(ByteArray(1), 1) == 1 && probe.length > 0) { "No mobile audio came back. Try another track." }
            object : PhoneAudioSource {
                override val length = probe.length
                override val mime = "audio/mp4"
                override fun write(range: PhoneByteRange, output: OutputStream, active: () -> Boolean) {
                    val input = DesktopRangeStream(url, headers); input.seek(range.start)
                    val bytes = ByteArray(64 * 1024); var left = range.count
                    while (left > 0 && active()) {
                        val count = input.read(bytes, minOf(left, bytes.size.toLong()).toInt())
                        if (count < 0) break
                        output.write(bytes, 0, count); left -= count
                    }
                }
            }
        }
        check(source.length in 1..(256L * 1024 * 1024)) { "That audio file is too large for phone streaming." }
        if (sources.size >= 64) sources.entries.sortedBy { it.value.time }.take(16).forEach { sources.remove(it.key) }
        sources[key] = Cached(now, source)
        source
    }
    fun clear() = sources.clear()
}
