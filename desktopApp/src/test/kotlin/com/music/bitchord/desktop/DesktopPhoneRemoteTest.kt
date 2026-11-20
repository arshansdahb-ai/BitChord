package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections
import kotlin.test.*

class DesktopPhoneRemoteTest {
    private val songs = listOf(Song(videoId = "abcdefghijk", title = "First song", artist = "Artist A", thumbnailUrl = null, fromAutoplay = false),
        Song(videoId = "lmnopqrstuv", title = "Second song", artist = "Artist B", thumbnailUrl = null, fromAutoplay = false))
    private val calls = Collections.synchronizedList(mutableListOf<PhoneAction>())
    private val remote = DesktopPhoneRemote(search = { songs }, dispatcher = Dispatchers.Unconfined, command = { calls.add(it) })
    private lateinit var base: String
    private fun client() = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(2)).build()
    @BeforeTest fun start() { remote.start("127.0.0.1", 0); base = remote.controllerLink.value.substringBefore("/#") }
    @AfterTest fun stop() = remote.close()
    private fun post(client: HttpClient, path: String, body: String, origin: String = base): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(5)).header("Origin", origin).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    private fun get(client: HttpClient, path: String) = client.send(HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
    private fun join(client: HttpClient, controller: Boolean = true, name: String = "Test phone"): HttpResponse<String> = post(client, "/api/join", buildJsonObject {
        put("token", (if (controller) remote.controllerLink.value else remote.guestLink.value).substringAfter('#')); put("name", name)
    }.toString())
    @Test fun pageIsPublicButPlaybackRequiresAnInvitation() {
        val client = client()
        val page = get(client, "/")
        assertEquals(200, page.statusCode()); assertTrue(page.body().contains("BitChord Music"))
        assertFalse(page.body().contains(remote.controllerLink.value.substringAfter('#')))
        assertEquals(401, get(client, "/api/state").statusCode())
        assertEquals(401, post(client, "/api/join", """{"token":"wrong","name":"Nobody"}""").statusCode())
        assertEquals(401, post(client, "/api/command", """{"action":"pause"}""").statusCode())
        assertTrue(calls.isEmpty())
    }
    @Test fun controllerSearchesAndControlsThePcThroughHttpOnlySessionCookie() {
        val client = client(); val joined = join(client)
        assertEquals(200, joined.statusCode())
        val cookie = joined.headers().firstValue("Set-Cookie").get()
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Strict"))
        assertFalse(joined.body().contains("bitchord_phone"))
        val result = post(client, "/api/search", """{"query":"artist"}""")
        val track = Json.parseToJsonElement(result.body()).jsonArray.first().jsonObject
        assertEquals(200, result.statusCode())
        assertEquals(200, post(client, "/api/command", """{"action":"playSong","id":"${track["id"]!!.jsonPrimitive.content}"}""").statusCode())
        assertEquals(songs.first(), calls.single().song)
        assertEquals(200, post(client, "/api/command", """{"action":"volume","value":0.3}""").statusCode())
        assertEquals(0.3, calls.last().value)
    }
    @Test fun twoGuestsShareTheQueueButCannotSkipOrPauseUnlessHostAllowsIt() {
        val a = client(); val b = client()
        assertEquals(200, join(a, false, "Alice").statusCode()); assertEquals(200, join(b, false, "Bob").statusCode())
        val results = post(a, "/api/search", """{"query":"song"}""")
        val id = Json.parseToJsonElement(results.body()).jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(200, post(a, "/api/command", """{"action":"queue","id":"$id"}""").statusCode())
        assertEquals(200, post(b, "/api/command", """{"action":"queue","id":"$id"}""").statusCode())
        assertEquals(403, post(a, "/api/command", """{"action":"next"}""").statusCode())
        assertEquals(403, post(b, "/api/command", """{"action":"pause"}""").statusCode())
        assertEquals(403, post(b, "/api/command", """{"action":"playBatch","ids":["$id"]}""").statusCode())
        remote.guestControl.value = true
        assertEquals(200, post(b, "/api/command", """{"action":"pause"}""").statusCode())
        assertEquals(200, post(b, "/api/command", """{"action":"playBatch","ids":["$id"]}""").statusCode())
        assertEquals(2, remote.members.value.size)
        assertEquals(listOf("queue", "queue", "pause", "playBatch"), calls.map { it.kind })
    }
    @Test fun crossOriginRequestsCannotPairOrChangePlayback() {
        val client = client()
        assertEquals(403, joinWithForeignOrigin(client).statusCode())
        join(client)
        assertEquals(403, post(client, "/api/command", """{"action":"next"}""", "https://evil.invalid").statusCode())
        assertTrue(calls.isEmpty())
    }
    private fun joinWithForeignOrigin(client: HttpClient) = post(client, "/api/join", """{"token":"${remote.controllerLink.value.substringAfter('#')}"}""", "https://evil.invalid")
    @Test fun disconnectAndRestartRevokeOldSessionsAndInvitations() {
        val client = client(); join(client)
        val oldInvite = remote.controllerLink.value.substringAfter('#')
        remote.disconnect(remote.members.value.single().id)
        assertEquals(401, get(client, "/api/state").statusCode())
        remote.guestControl.value = true
        remote.close(); remote.start("127.0.0.1", 0); base = remote.controllerLink.value.substringBefore("/#")
        assertFalse(remote.guestControl.value)
        assertNotEquals(oldInvite, remote.controllerLink.value.substringAfter('#'))
        assertEquals(401, post(client, "/api/join", """{"token":"$oldInvite"}""").statusCode())
    }
    @Test fun queueEditsRequireTheExpectedRowAndAreNotArbitraryFileOrNetworkCommands() {
        val client = client(); join(client)
        remote.publish(songs[0], true, 1000, 10000, 0.5f, listOf(4 to songs[1]), false)
        val state = get(client, "/api/state").body()
        assertTrue(state.contains("Second song")); assertFalse(state.contains("localPath"))
        val id = DesktopPhoneRemote.trackId(songs[1])
        assertEquals(409, post(client, "/api/command", """{"action":"remove","index":3,"id":"$id"}""").statusCode())
        assertEquals(200, post(client, "/api/command", """{"action":"remove","index":4,"id":"$id"}""").statusCode())
        assertEquals(400, post(client, "/api/command", """{"action":"openFile","path":"C:/secret.txt"}""").statusCode())
        assertEquals(409, post(client, "/api/command", """{"action":"playSong","id":"https://example.com/audio"}""").statusCode())
        assertEquals(1, calls.size)
    }
    @Test fun malformedAndOversizedRequestsAreBounded() {
        val client = client(); join(client)
        assertEquals(400, post(client, "/api/command", "not json").statusCode())
        assertEquals(413, post(client, "/api/command", " ".repeat(4100)).statusCode())
        assertEquals(400, post(client, "/api/command", """{"action":"volume","value":2}""").statusCode())
        assertEquals(400, post(client, "/api/command", """{"action":"volume"}""").statusCode())
        assertEquals(400, post(client, "/api/search", """{"query":"a"}""").statusCode())
        assertTrue(calls.isEmpty())
    }
    @Test fun mismatchedHostAndPathTraversalCannotExposeFiles() {
        val port = URI(base).port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("GET /api/state HTTP/1.1\r\nHost: attacker.invalid\r\nConnection: close\r\n\r\n".toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readLine().contains("403"))
        }
        assertEquals(404, get(client(), "/../CUSTOM-WINDOWS.md").statusCode())
    }
    @Test fun phoneHtmlHasNativeAudioAndUsesNoExternalScripts() {
        val client = client()
        val page = get(client, "/"); val js = get(client, "/remote.js")
        assertTrue(page.body().contains("<audio")); assertTrue(page.body().contains("playsinline"))
        assertTrue(js.body().contains("textContent")); assertFalse(js.body().contains("innerHTML"))
        assertTrue(page.headers().firstValue("Content-Security-Policy").get().contains("frame-ancestors 'none'"))
        assertTrue(page.body().contains("apple-touch-icon"))
        assertTrue(get(client, "/manifest.webmanifest").body().contains("/app-icon.png"))
        val icon = client.send(HttpRequest.newBuilder(URI(base + "/app-icon.png")).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, icon.statusCode()); assertEquals("image/png", icon.headers().firstValue("Content-Type").get())
        assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), icon.body().take(8).toByteArray())
        assertEquals(404, get(client, "/api/files").statusCode())
    }
    @Test fun existingInternetListenTogetherLockPreventsLocalMutation() {
        val client = client(); join(client)
        remote.publish(songs[0], true, 0, 10000, 1f, emptyList(), true)
        assertEquals(409, post(client, "/api/command", """{"action":"pause"}""").statusCode())
        assertTrue(calls.isEmpty())
    }
}
