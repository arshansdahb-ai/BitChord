package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

class DesktopPhonePlayerTest {
    @Test fun safariRangesIncludeClosedOpenAndSuffixForms() {
        assertEquals(PhoneByteRange(0, 99, false), PhoneByteRange.parse(null, 100))
        assertEquals(PhoneByteRange(0, 1, true), PhoneByteRange.parse("bytes=0-1", 100))
        assertEquals(PhoneByteRange(20, 99, true), PhoneByteRange.parse("bytes=20-", 100))
        assertEquals(PhoneByteRange(80, 99, true), PhoneByteRange.parse("bytes=-20", 100))
        assertEquals(PhoneByteRange(0, 99, true), PhoneByteRange.parse("bytes=-200", 100))
        assertEquals(PhoneByteRange(50, 99, true), PhoneByteRange.parse("bytes=50-1000", 100))
    }
    @Test fun invalidRangesCannotReadOutsideTheAudio() {
        for (range in listOf("bytes=100-", "bytes=20-10", "bytes=-0", "bytes=-", "bytes=0-1,4-5", "bytes=99999999999999999999999-", "items=0-2")) {
            assertEquals(416, assertFailsWith<PhoneRemoteError>(range) { PhoneByteRange.parse(range, 100) }.status)
        }
    }
    @Test fun knownLocalAudioStreamsExactRangesWithoutCallingAnExternalResolver() = runBlocking {
        val file = Files.createTempFile("bitchord-phone-audio-", ".m4a")
        try {
            Files.write(file, ByteArray(200) { it.toByte() })
            val song = Song(videoId = "local:test", title = "Local", artist = "Test", thumbnailUrl = null, localPath = file.toString())
            val source = DesktopPhoneAudio(resolver = { _, _ -> error("A registered local file must not resolve remotely") }).source(song, 256)
            assertEquals(200, source.length); assertEquals("audio/mp4", source.mime)
            val out = java.io.ByteArrayOutputStream()
            source.write(PhoneByteRange.parse("bytes=12-26", source.length), out) { true }
            assertContentEquals(ByteArray(15) { (it + 12).toByte() }, out.toByteArray())
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun pairedPhoneCanPrepareHeadAndSeekAudioAndDisconnectRevokesItsTicket() {
        val file = Files.createTempFile("bitchord-phone-range-", ".wav")
        Files.write(file, ByteArray(500) { (it % 128).toByte() })
        val song = Song(videoId = "local:fixture", title = "Private library song", artist = "Fixture", thumbnailUrl = null, localPath = file.toString())
        val remote = DesktopPhoneRemote(search = { listOf(song) }, dispatcher = Dispatchers.Unconfined,
            library = { PhoneLibrarySnapshot(recent = listOf(song), settings = listOf(PhoneSetting("speed", "Speed", "number", JsonPrimitive(1), min = .5, max = 2.0))) }, command = {})
        try {
            remote.start("127.0.0.1", 0)
            val base = remote.controllerLink.value.substringBefore("/#")
            val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
            fun post(path: String, body: String) = client.send(HttpRequest.newBuilder(URI(base + path)).header("Origin", base).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            fun get(url: String, method: String = "GET", range: String? = null): HttpResponse<ByteArray> {
                val request = HttpRequest.newBuilder(URI(url)).method(method, HttpRequest.BodyPublishers.noBody())
                if (range != null) request.header("Range", range)
                return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            assertEquals(401, post("/api/prepare", """{"id":"${DesktopPhoneRemote.trackId(song)}"}""").statusCode())
            assertEquals(200, post("/api/join", """{"token":"${remote.controllerLink.value.substringAfter('#')}"}""").statusCode())
            val library = get(base + "/api/library").body().toString(Charsets.UTF_8)
            assertTrue(library.contains("Private library song")); assertFalse(library.contains(file.toString()))
            assertEquals(400, post("/api/command", """{"action":"setting","key":"speed","option":5}""").statusCode())
            assertEquals(200, post("/api/command", """{"action":"setting","key":"speed","option":1.5}""").statusCode())
            assertEquals(409, post("/api/prepare", """{"id":"C:/secret.wav"}""").statusCode())
            val result = post("/api/prepare", """{"id":"${DesktopPhoneRemote.trackId(song)}"}""")
            assertEquals(200, result.statusCode(), result.body())
            val url = Json.parseToJsonElement(result.body()).jsonObject["url"]!!.jsonPrimitive.content
            val head = get(url, "HEAD"); assertEquals(200, head.statusCode()); assertEquals("500", head.headers().firstValue("Content-Length").get()); assertTrue(head.body().isEmpty())
            val range = get(url, range = "bytes=10-19")
            assertEquals(206, range.statusCode()); assertEquals("bytes 10-19/500", range.headers().firstValue("Content-Range").get())
            assertContentEquals(ByteArray(10) { (it + 10).toByte() }, range.body())
            assertEquals(416, get(url, range = "bytes=500-").statusCode())
            remote.disconnect(remote.members.value.single().id)
            assertEquals(401, get(url).statusCode())
        } finally { remote.close(); Files.deleteIfExists(file) }
    }
    @Test fun guestsCannotReadPcLibraryOrApplySettings() {
        val remote = DesktopPhoneRemote(dispatcher = Dispatchers.Unconfined, command = { error("Guest mutation") })
        try {
            remote.start("127.0.0.1", 0); val base = remote.guestLink.value.substringBefore("/#")
            val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
            fun post(path: String, body: String) = client.send(HttpRequest.newBuilder(URI(base + path)).header("Origin", base).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, post("/api/join", """{"token":"${remote.guestLink.value.substringAfter('#')}"}""").statusCode())
            assertEquals(403, client.send(HttpRequest.newBuilder(URI(base + "/api/library")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
            assertEquals(403, post("/api/command", """{"action":"setting","key":"speed","option":1}""").statusCode())
            assertEquals(403, post("/api/dj", """{"request":"start my dj"}""").statusCode())
        } finally { remote.close() }
    }
    @Test fun unsupportedSourceCannotTurnIntoAnArbitraryUrlProxy() = runBlocking {
        val song = Song(videoId = "local:secret", title = "Unregistered", artist = "Test", thumbnailUrl = null)
        assertFailsWith<IllegalArgumentException> { DesktopPhoneAudio().source(song, 256) }
        val track = song.copy(videoId = "abcdefghijk")
        assertFailsWith<IllegalArgumentException> { DesktopPhoneAudio(resolver = { _, _ -> "http://127.0.0.1/private" to emptyMap() }).source(track, 256) }
        Unit
    }
}
