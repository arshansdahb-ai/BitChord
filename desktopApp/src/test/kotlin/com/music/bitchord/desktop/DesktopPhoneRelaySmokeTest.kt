package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

/** Explicitly enabled network smoke check. No account data or user library is supplied. */
class DesktopPhoneRelaySmokeTest {
    @Test fun outboundHttpsPairingAudioAndOwnedProcessCleanup() = runBlocking {
        assumeTrue("Opt-in integration check", System.getenv("BITCHORD_RELAY_SMOKE") == "1")
        val resources = System.getenv("BITCHORD_SMOKE_RESOURCES")
        System.setProperty("compose.application.resources.dir", resources)
        val remote = DesktopPhoneRemote(dispatcher = Dispatchers.Unconfined, command = {})
        val fixture = Files.createTempFile("bitchord-relay-smoke-", ".wav")
        try {
            val speech = DesktopDjVoice.render("Hey, it's your DJ. Let's get into the music.")
            assertEquals("RIFF", speech.take(4).toByteArray().toString(Charsets.US_ASCII))
            Files.write(fixture, speech)
            val song = Song(videoId = "local:relay-check", title = "Generated DJ voice check", artist = "Local neural voice", thumbnailUrl = null, localPath = fixture.toString())
            remote.publish(song, false, 0, 5000, 1f, emptyList(), false)
            val origin = remote.startNoAdmin()
            // start() resets only previous-session data; publish after start to register the fixture.
            remote.publish(song, false, 0, 5000, 1f, emptyList(), false)
            assertTrue(origin.startsWith("https://") && origin.endsWith(".trycloudflare.com"))
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
            fun get(url: String, range: String? = null): HttpResponse<ByteArray> {
                val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(40))
                if (range != null) builder.header("Range", range)
                return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            fun post(path: String, body: String) = client.send(HttpRequest.newBuilder(URI(origin + path)).timeout(Duration.ofSeconds(45))
                .header("Origin", origin).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            var page: HttpResponse<ByteArray>? = null
            var lastFailure = ""
            for (attempt in 0..119) {
                page = runCatching { get(origin + "/") }.onFailure { lastFailure = it.toString() }.getOrNull()
                if (page?.statusCode() == 200) break
                Thread.sleep(1000)
            }
            assertEquals(200, page?.statusCode(), "The temporary HTTPS link did not become reachable: $lastFailure")
            assertTrue(page!!.body().toString(Charsets.UTF_8).contains("BitChord Music"))
            assertEquals(401, get(origin + "/api/state").statusCode())
            val join = post("/api/join", """{"token":"${remote.controllerLink.value.substringAfter('#')}","name":"HTTPS integration check"}""")
            assertEquals(200, join.statusCode(), join.body()); assertTrue(join.headers().firstValue("Set-Cookie").get().contains("Secure"))
            val state = get(origin + "/api/state"); assertEquals(200, state.statusCode())
            assertTrue(state.body().toString(Charsets.UTF_8).contains("Generated DJ voice check"))
            val prepared = post("/api/prepare", """{"id":"${DesktopPhoneRemote.trackId(song)}"}""")
            assertEquals(200, prepared.statusCode(), prepared.body())
            val url = Json.parseToJsonElement(prepared.body()).jsonObject["url"]!!.jsonPrimitive.content
            val streamed = get(url, "bytes=0-4095")
            assertEquals(206, streamed.statusCode()); assertContentEquals(speech.take(4096).toByteArray(), streamed.body())
            val music = Song(videoId = "4NRXx6U8ABQ", title = "Blinding Lights", artist = "The Weeknd", thumbnailUrl = null)
            remote.publish(music, false, 0, 0, 1f, emptyList(), false)
            val musicPrepared = post("/api/prepare", """{"id":"${DesktopPhoneRemote.trackId(music)}","quality":256}""")
            assertEquals(200, musicPrepared.statusCode(), musicPrepared.body())
            val musicUrl = Json.parseToJsonElement(musicPrepared.body()).jsonObject["url"]!!.jsonPrimitive.content
            val musicRange = get(musicUrl, "bytes=0-4095")
            assertEquals(206, musicRange.statusCode()); assertEquals("ftyp", musicRange.body().copyOfRange(4, 8).toString(Charsets.US_ASCII))
            val owner = ProcessHandle.current().descendants().filter { it.info().command().orElse("").contains("cloudflared", true) }.toList()
            assertTrue(owner.isNotEmpty()); remote.close(); assertTrue(owner.none { it.isAlive })
            val out = java.nio.file.Path.of(System.getenv("BITCHORD_SMOKE_REPORT"))
            Files.writeString(out, """{"httpsPublicPage":true,"unpairedStateDenied":true,"secureCookiePairing":true,"pairedState":true,"neuralVoiceRendered":true,"httpsAudioRangeByteExact":true,"realProviderMp4MusicRange":true,"relayProcessStopped":true,"firewallChanged":false,"adminRequested":false,"physicalIPhoneTested":false,"browserTested":false}""")
        } finally { remote.close(); DesktopDjVoice.stop(); Files.deleteIfExists(fixture) }
        Unit
    }
}
