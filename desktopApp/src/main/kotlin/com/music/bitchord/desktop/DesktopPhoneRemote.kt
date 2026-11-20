package com.music.bitchord.desktop

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.BrowseItem
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@Serializable internal data class PhoneTrack(val id: String, val title: String, val artist: String, val artwork: String = "")
@Serializable internal data class PhoneQueueEntry(val index: Int, val track: PhoneTrack)
@Serializable internal data class PhonePlayback(
    val current: PhoneTrack? = null, val playing: Boolean = false, val positionMs: Long = 0,
    val durationMs: Long = 0, val volume: Float = 1f, val queue: List<PhoneQueueEntry> = emptyList(),
    val locked: Boolean = false,
)
internal data class PhoneAction(val kind: String, val song: Song? = null, val index: Int = -1, val target: Int = -1,
    val expectedId: String = "", val targetId: String = "", val value: Double = 0.0,
    val key: String = "", val text: String = "", val songs: List<Song> = emptyList(), val option: JsonPrimitive? = null)
internal data class PhoneNetwork(val label: String, val address: String)
internal data class PhoneMember(val id: String, val name: String, val controller: Boolean, val seenAt: Long)
internal class PhoneRemoteError(val status: Int, override val message: String) : RuntimeException(message)

/** A paired music companion; no account credentials, arbitrary files, shell, or URL proxy API. */
internal class DesktopPhoneRemote(
    private val search: suspend (String) -> List<Song> = { query ->
        YtMusicRepository.search(query, SearchFilter.SONGS).getOrThrow().filterIsInstance<SearchResult.Track>().map { it.song }
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val library: () -> PhoneLibrarySnapshot = { PhoneLibrarySnapshot() },
    private val audio: DesktopPhoneAudio = DesktopPhoneAudio(),
    private val command: (PhoneAction) -> Unit,
) : AutoCloseable {
    companion object {
        const val PORT = 43187
        fun networks(): List<PhoneNetwork> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { device -> device.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }.map { PhoneNetwork("${device.displayName} - ${it.hostAddress}", it.hostAddress) } }
        fun trackId(song: Song): String = MessageDigest.getInstance("SHA-256").digest(song.videoId.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
    }
    private data class Session(val id: String, val name: String, val controller: Boolean, val created: Long, @Volatile var seen: Long)
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = AtomicReference(PhonePlayback())
    private val sessions = ConcurrentHashMap<String, Session>()
    private val songs = object : LinkedHashMap<String, Song>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean = size > 1500
    }
    private val rates = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val browseItems = ConcurrentHashMap<String, String>()
    private data class MediaTicket(val session: String, val song: String, val quality: Int, val expires: Long)
    private data class VoiceTicket(val session: String, val bytes: ByteArray, val expires: Long)
    private val voiceTickets = ConcurrentHashMap<String, VoiceTicket>()
    private val mediaTickets = ConcurrentHashMap<String, MediaTicket>()
    private val allowedOrigins = ConcurrentHashMap.newKeySet<String>()
    private val relay = DesktopPhoneRelay()
    val pairing = DesktopPhonePairing()
    val relayStatus = MutableStateFlow("")
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var shutdownHook: Thread? = null
    @Volatile private var controllerToken = ""
    @Volatile private var guestToken = ""
    @Volatile private var authority = ""
    @Volatile private var origin = ""
    @Volatile private var generation = 0L
    val running = MutableStateFlow(false)
    val controllerLink = MutableStateFlow("")
    val guestLink = MutableStateFlow("")
    val members = MutableStateFlow<List<PhoneMember>>(emptyList())
    val guestControl = MutableStateFlow(false)
    private fun token() = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun constantEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    @Synchronized fun start(address: String, port: Int = PORT) {
        check(!running.value) { "A phone session is already running." }
        generation++
        val ip = InetAddress.getByName(address)
        require(ip is Inet4Address && (ip.isSiteLocalAddress || ip.isLoopbackAddress)) { "Choose a local network address." }
        require(ip.isLoopbackAddress || networks().any { it.address == address }) { "That network is no longer available." }
        System.setProperty("sun.net.httpserver.maxReqTime", "10")
        System.setProperty("sun.net.httpserver.maxRspTime", "120")
        val service = HttpServer.create(InetSocketAddress(ip, port), 24)
        val pool = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "BitChord-phone").apply { isDaemon = true } })
        service.executor = pool
        authority = "$address:${service.address.port}"
        origin = "http://$authority"
        allowedOrigins.clear(); allowedOrigins.add(origin)
        controllerToken = token(); guestToken = token()
        guestControl.value = false
        controllerLink.value = "$origin/#${controllerToken}"
        guestLink.value = "$origin/#${guestToken}"
        service.createContext("/") { exchange -> handle(exchange) }
        server = service; executor = pool
        running.value = true
        service.start()
        shutdownHook = Thread { close() }.also { Runtime.getRuntime().addShutdownHook(it) }
    }

    suspend fun startNoAdmin(): String {
        start("127.0.0.1", 0)
        val epoch = generation
        relayStatus.value = "Connecting through the approved HTTPS relay..."
        return try {
            val url = relay.start(origin)
            synchronized(this) {
                check(running.value && generation == epoch) { "The session ended while the link was starting." }
                require(URI(url).scheme == "https" && URI(url).host.endsWith(".trycloudflare.com"))
                origin = url; allowedOrigins.add(url)
                controllerLink.value = "$url/#$controllerToken"; guestLink.value = "$url/#$guestToken"
                relayStatus.value = "HTTPS invitation created. Allow up to two minutes for the new address to become reachable. Internet required; Windows Firewall is unchanged."
            }
            url
        } catch (error: Exception) { close(); throw error }
    }

    @Synchronized override fun close() {
        running.value = false
        generation++
        relay.close(); relayStatus.value = ""
        pairing.close()
        server?.stop(0); server = null
        executor?.shutdownNow(); executor = null
        controllerToken = ""; guestToken = ""; authority = ""; origin = ""
        controllerLink.value = ""; guestLink.value = ""
        state.set(PhonePlayback())
        sessions.clear(); rates.clear(); members.value = emptyList()
        voiceTickets.clear(); mediaTickets.clear(); browseItems.clear(); allowedOrigins.clear(); audio.clear()
        synchronized(songs) { songs.clear() }
        shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }; shutdownHook = null
    }
    fun disconnect(id: String) { sessions.remove(id); publishMembers() }
    private fun publishMembers() { members.value = sessions.values.sortedBy { it.created }.map { PhoneMember(it.id, it.name, it.controller, it.seen) } }
    private fun register(song: Song): PhoneTrack {
        val id = trackId(song)
        synchronized(songs) { songs[id] = song }
        val art = song.videoId.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }.orEmpty()
        return PhoneTrack(id, song.title.take(240), song.artist.take(160), art)
    }
    private fun known(id: String): Song = synchronized(songs) { songs[id] } ?: throw PhoneRemoteError(409, "Find that song again.")
    private fun snapshot(): PhoneLibrarySnapshot = runBlocking { withContext(dispatcher) { library() } }
    private fun requireController(who: Session) { if (!who.controller) throw PhoneRemoteError(403, "Only a controller can open the PC library or settings.") }
    private fun apply(who: Session, action: PhoneAction) = runBlocking {
        withContext(dispatcher) {
            if (!running.value || sessions[who.id] !== who) throw PhoneRemoteError(401, "Session ended.")
            if (action.kind == "playBatch" && !who.controller && !guestControl.value) throw PhoneRemoteError(403, "Guest playback control is off.")
            command(action)
        }
    }
    fun publish(current: Song?, playing: Boolean, positionMs: Long, durationMs: Long, volume: Float, queue: List<Pair<Int, Song>>, locked: Boolean) {
        state.set(PhonePlayback(current?.let(::register), playing, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), volume,
            queue.take(300).map { PhoneQueueEntry(it.first, register(it.second)) }, locked))
    }
    private fun limit(key: String, maximum: Int) {
        val now = System.currentTimeMillis()
        if (rates.size >= 1024 && !rates.containsKey(key)) throw PhoneRemoteError(429, "This session is busy. Try again shortly.")
        val result = rates.compute(key) { _, old -> if (old == null || now - old.first > 60_000) now to 1 else old.first to old.second + 1 }!!
        if (rates.size > 512) rates.entries.removeIf { now - it.value.first > 60_000 }
        if (result.second > maximum) throw PhoneRemoteError(429, "Too many requests. Give it a moment.")
    }
    private fun session(exchange: HttpExchange): Session {
        val id = exchange.requestHeaders.getFirst("Cookie").orEmpty().split(';').map(String::trim)
            .firstOrNull { it.startsWith("bitchord_phone=") }?.substringAfter('=')?.trim('"') ?: throw PhoneRemoteError(401, "Scan a fresh invitation from the PC.")
        val found = sessions[id] ?: throw PhoneRemoteError(401, "This device was disconnected. Ask the host to invite you again.")
        val now = System.currentTimeMillis()
        if (now - found.created > 8 * 60 * 60 * 1000) { disconnect(id); throw PhoneRemoteError(401, "This session expired. Scan a new invitation.") }
        found.seen = now
        limit("device:$id", 180)
        publishMembers()
        return found
    }
    private fun readBody(exchange: HttpExchange): JsonObject {
        if (!exchange.requestHeaders.getFirst("Content-Type").orEmpty().startsWith("application/json")) throw PhoneRemoteError(415, "Use a JSON request.")
        val bytes = exchange.requestBody.readNBytes(4097)
        if (bytes.size > 4096) throw PhoneRemoteError(413, "Request too large.")
        return try { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
        catch (_: Exception) { throw PhoneRemoteError(400, "Invalid request.") }
    }
    private fun send(exchange: HttpExchange, status: Int, body: ByteArray, type: String) {
        exchange.responseHeaders.set("Content-Type", type)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https://i.ytimg.com https://*.googleusercontent.com https://i.scdn.co https://*.mzstatic.com; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    private fun reply(exchange: HttpExchange, body: JsonElement, status: Int = 200) = send(exchange, status, body.toString().toByteArray(), "application/json; charset=utf-8")
    private fun okay(exchange: HttpExchange) = reply(exchange, buildJsonObject { put("ok", true) })
    private fun handle(exchange: HttpExchange) {
        try {
            if (!running.value || exchange.requestHeaders.getFirst("Host") != authority) throw PhoneRemoteError(403, "Use the address shown on the PC.")
            if (exchange.requestMethod == "GET" && exchange.requestURI.rawPath == "/api/voice") {
                val key = exchange.requestURI.rawQuery.orEmpty().removePrefix("ticket=")
                val clip = voiceTickets[key] ?: throw PhoneRemoteError(401, "DJ introduction expired.")
                if (!sessions.containsKey(clip.session) || System.currentTimeMillis() > clip.expires) throw PhoneRemoteError(401, "DJ introduction expired.")
                send(exchange, 200, clip.bytes, "audio/wav"); return
            }
            val peer = exchange.remoteAddress.address
            if (!peer.isSiteLocalAddress && !peer.isLoopbackAddress) throw PhoneRemoteError(403, "Local network access only.")
            limit("ip:${peer.hostAddress}", if (peer.isLoopbackAddress && origin.startsWith("https:")) 2400 else 360)
            val path = exchange.requestURI.rawPath
            if (exchange.requestMethod in setOf("GET", "HEAD") && path == "/api/audio") { serveAudio(exchange); return }
            if (exchange.requestMethod == "GET" && path in setOf("/", "/remote.js", "/remote.css", "/manifest.webmanifest", "/app-icon.png", "/favicon.ico")) {
                val file = when (path) { "/" -> "index.html"; else -> path.removePrefix("/") }
                val bytes = javaClass.getResourceAsStream("/phone/$file")?.use { it.readBytes() } ?: throw PhoneRemoteError(404, "Remote page is missing.")
                send(exchange, 200, bytes, when (file) { "index.html" -> "text/html; charset=utf-8"; "remote.js" -> "text/javascript; charset=utf-8"; "manifest.webmanifest" -> "application/manifest+json"; "app-icon.png" -> "image/png"; "favicon.ico" -> "image/x-icon"; else -> "text/css; charset=utf-8" })
                return
            }
            if (exchange.requestMethod == "POST") {
                if (exchange.requestHeaders.getFirst("Origin") !in allowedOrigins) throw PhoneRemoteError(403, "Use the phone player page to control playback.")
                if (path == "/api/join") { join(exchange, readBody(exchange)); return }
                val who = session(exchange)
                val body = readBody(exchange)
                when (path) {
                    "/api/search" -> {
                        limit("search:${who.id}", 15)
                        val query = body["query"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                        if (query.length !in 2..100) throw PhoneRemoteError(400, "Search with 2 to 100 characters.")
                        val results = runBlocking { withTimeout(12_000) { search(query) } }.take(20).map(::register)
                        reply(exchange, json.encodeToJsonElement(results))
                    }
                    "/api/command" -> perform(exchange, who, body)
                    "/api/prepare" -> {
                        limit("prepare:${who.id}", 20)
                        val id = body["id"]?.jsonPrimitive?.contentOrNull.orEmpty(); val song = known(id)
                        val quality = body["quality"]?.jsonPrimitive?.intOrNull?.takeIf { it in setOf(128, 256) } ?: 256
                        val source = runBlocking { withTimeout(35_000) { audio.source(song, quality) } }
                        val now = System.currentTimeMillis(); mediaTickets.entries.removeIf { now > it.value.expires }
                        if (mediaTickets.size >= 256) throw PhoneRemoteError(429, "Too many prepared tracks. Try again later.")
                        val key = token(); mediaTickets[key] = MediaTicket(who.id, id, quality, now + 30 * 60 * 1000)
                        reply(exchange, buildJsonObject { put("url", "$origin/api/audio?ticket=$key"); put("mime", source.mime); put("bytes", source.length) })
                    }
                    "/api/browse" -> {
                        if (body["id"]?.jsonPrimitive?.contentOrNull.orEmpty().startsWith("local:")) requireController(who)
                        browse(exchange, body)
                    }
                    "/api/catalogSearch" -> { catalogSearch(exchange, who, body) }
                    "/api/radio" -> {
                        val song = known(body["id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        require(Regex("[A-Za-z0-9_-]{11}").matches(song.videoId)) { "Radio is unavailable for this source." }
                        val found = runBlocking { withTimeout(15_000) { DesktopSearchClient.radio(song.videoId).getOrThrow() } }
                        reply(exchange, json.encodeToJsonElement(found.take(30).map(::register)))
                    }
                    "/api/lyrics" -> {
                        val song = known(body["id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        val found = runBlocking { withTimeout(18_000) { DesktopLyricsClient.lookup(song).getOrThrow() } }
                        reply(exchange, buildJsonObject { put("source", found.source); put("lines", buildJsonArray { found.lines.take(600).forEach { line -> add(buildJsonObject { put("time", line.timeMs); put("text", line.text) }) } }) })
                    }
                    "/api/dj" -> {
                        requireController(who); limit("dj:${who.id}", 6)
                        val request = body["request"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().take(300)
                        require(request.isNotEmpty()) { "Tell DJ what you want to hear." }
                        val history = snapshot().recent
                        val current = body["current"]?.jsonPrimitive?.contentOrNull?.let { synchronized(songs) { songs[it] } }
                        val (tracks, spoken) = runBlocking { withTimeout(100_000) {
                            val plan = DesktopTalkingDj.plan(request, history, current)
                            val picked = DesktopTalkingDj.songs(plan, history)
                            picked to DesktopTalkingDj.setIntroduction(plan, picked.first())
                        } }
                        if (body["target"]?.jsonPrimitive?.contentOrNull == "pc") apply(who, PhoneAction("djSession", songs = tracks, text = spoken))
                        reply(exchange, buildJsonObject { put("voice", if (body["target"]?.jsonPrimitive?.contentOrNull != "pc" && body["voice"]?.jsonPrimitive?.booleanOrNull != false) {
                            runCatching {
                                val now = System.currentTimeMillis(); voiceTickets.entries.removeIf { now > it.value.expires }
                                check(voiceTickets.size < 8)
                                val clip = runBlocking { withTimeout(45_000) { DesktopDjVoice.render(spoken) } }
                                val key = token(); voiceTickets[key] = VoiceTicket(who.id, clip, now + 120_000)
                                "$origin/api/voice?ticket=$key"
                            }.getOrDefault("")
                        } else ""); put("spoken", spoken); put("tracks", json.encodeToJsonElement(tracks.map(::register))) })
                    }
                    "/api/leave" -> { disconnect(who.id); exchange.responseHeaders.set("Set-Cookie", "bitchord_phone=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"); okay(exchange) }
                    else -> throw PhoneRemoteError(404, "Not found.")
                }
            } else if (exchange.requestMethod == "GET" && path == "/api/library") {
                val who = session(exchange); requireController(who); serveLibrary(exchange)
            } else if (exchange.requestMethod == "GET" && path == "/api/state") {
                val who = session(exchange)
                reply(exchange, buildJsonObject {
                    put("playback", json.encodeToJsonElement(state.get()))
                    put("canControl", who.controller || guestControl.value)
                    put("role", if (who.controller) "Controller" else "Guest")
                    put("name", who.name)
                    put("members", buildJsonArray { members.value.forEach { add(buildJsonObject { put("name", it.name); put("controller", it.controller) }) } })
                })
            } else throw PhoneRemoteError(404, "Not found.")
        } catch (error: Exception) {
            val status = (error as? PhoneRemoteError)?.status ?: if (error is TimeoutCancellationException) 504 else 400
            runCatching { reply(exchange, buildJsonObject { put("error", when {
                error is PhoneRemoteError -> error.message
                error is TimeoutCancellationException -> "Music search timed out. Try again."
                error is IllegalArgumentException || error is IllegalStateException -> error.message?.take(160) ?: "Request could not be completed."
                else -> "Request could not be completed. Try again."
            }) }, status) }
        } finally { exchange.close() }
    }
    private fun join(exchange: HttpExchange, body: JsonObject) {
        val joiningGeneration = generation
        limit("join:${exchange.remoteAddress.address.hostAddress}", if (exchange.remoteAddress.address.isLoopbackAddress && origin.startsWith("https:")) 32 else 8)
        val supplied = body["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val controller = controllerToken.isNotEmpty() && constantEquals(supplied, controllerToken)
        if (!controller && (guestToken.isEmpty() || !constantEquals(supplied, guestToken))) throw PhoneRemoteError(401, "This invitation is invalid. Scan a fresh QR code from the PC.")
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.created > 8 * 60 * 60 * 1000 }
        val name = body["name"]?.jsonPrimitive?.contentOrNull.orEmpty().filterNot(Char::isISOControl).trim().take(32).ifBlank { if (controller) "My phone" else "Guest" }
        val id = token()
        synchronized(sessions) {
            if (sessions.size >= 16) throw PhoneRemoteError(409, "This session already has 16 devices. Ask the host to disconnect one.")
            if (!running.value || joiningGeneration != generation) throw PhoneRemoteError(401, "Session ended.")
            sessions[id] = Session(id, name, controller, now, now)
        }
        exchange.responseHeaders.set("Set-Cookie", "bitchord_phone=$id; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800${if (exchange.requestHeaders.getFirst("Origin").orEmpty().startsWith("https://")) "; Secure" else ""}")
        publishMembers(); okay(exchange)
    }
    private fun perform(exchange: HttpExchange, who: Session, body: JsonObject) {
        limit("commands:${who.id}", 40)
        val kind = body["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (kind in setOf("setting", "favorite", "playBatch", "playlistCreate", "playlistAdd", "playlistRemove", "playlistDelete", "playlistRename")) {
            if (!(kind == "playBatch" && guestControl.value)) requireController(who)
            val key = body["key"]?.jsonPrimitive?.contentOrNull.orEmpty().take(100)
            val option = body["option"] as? JsonPrimitive
            val one = body["id"]?.jsonPrimitive?.contentOrNull?.let(::known)
            val picked = (body["ids"] as? JsonArray).orEmpty().take(200).map { known(it.jsonPrimitive.content) }
            val text = body["text"]?.jsonPrimitive?.contentOrNull.orEmpty().filterNot(Char::isISOControl).take(100)
            if (kind == "setting") {
                val setting = snapshot().settings.firstOrNull { it.key == key } ?: throw PhoneRemoteError(400, "That PC setting is not available on the phone.")
                when (setting.type) {
                    "boolean" -> require(option?.booleanOrNull != null) { "Choose on or off." }
                    "number" -> require(option?.doubleOrNull?.let { it.isFinite() && it in setting.min..setting.max } == true) { "That value is outside the setting's range." }
                    "select" -> require(option?.contentOrNull in setting.options) { "Choose an available option." }
                    else -> throw PhoneRemoteError(400, "Unsupported setting.")
                }
            }
            apply(who, PhoneAction(kind, song = one, songs = picked, key = key, text = text, option = option))
            okay(exchange); return
        }
        if (kind !in setOf("play", "pause", "next", "previous", "volume", "seek", "playSong", "queue", "playQueue", "remove", "move")) throw PhoneRemoteError(400, "Unknown control.")
        if (!who.controller && !guestControl.value && kind != "queue") throw PhoneRemoteError(403, "Guests can add songs. The host controls playback.")
        val id = body["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val song = if (kind in setOf("playSong", "queue")) synchronized(songs) { songs[id] }
            ?: throw PhoneRemoteError(409, "Search for that track again.") else null
        val value = if (kind in setOf("volume", "seek")) body["value"]?.jsonPrimitive?.doubleOrNull
            ?: throw PhoneRemoteError(400, "Missing control value.") else 0.0
        if (!value.isFinite() || (kind == "volume" && value !in 0.0..1.0) || (kind == "seek" && value !in 0.0..86_400_000.0)) throw PhoneRemoteError(400, "Invalid control value.")
        val index = body["index"]?.jsonPrimitive?.intOrNull ?: -1
        val target = body["target"]?.jsonPrimitive?.intOrNull ?: -1
        if (kind in setOf("playQueue", "remove", "move")) {
            fun matches(at: Int, expected: String) = state.get().queue.any { it.index == at && it.track.id == expected }
            if (!matches(index, id) || (kind == "move" && !matches(target, body["targetId"]?.jsonPrimitive?.contentOrNull.orEmpty()))) {
                throw PhoneRemoteError(409, "The queue changed. Refresh and try again.")
            }
        }
        val action = PhoneAction(kind, song, index, target, id, body["targetId"]?.jsonPrimitive?.contentOrNull.orEmpty(), value)
        runBlocking {
            withContext(dispatcher) {
                if (!running.value || sessions[who.id] !== who) throw PhoneRemoteError(401, "Session ended.")
                if (!who.controller && !guestControl.value && kind != "queue") throw PhoneRemoteError(403, "Guest controls were turned off.")
                if (state.get().locked) throw PhoneRemoteError(409, "Leave Listen Together on the PC before using phone controls.")
                command(action)
            }
        }
        okay(exchange)
    }

    private fun collection(item: BrowseItem): JsonObject {
        if (browseItems.size > 500) browseItems.clear()
        val id = "collection:" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(item.browseId.toByteArray())).take(24)
        browseItems[id] = item.browseId
        return buildJsonObject { put("id", id); put("title", item.title.take(240)); put("artist", item.subtitle.take(160)); put("kind", "collection"); put("type", item.type.name) }
    }
    private fun serveLibrary(exchange: HttpExchange) {
        val data = snapshot()
        reply(exchange, buildJsonObject {
            put("recent", json.encodeToJsonElement(data.recent.take(100).map(::register)))
            put("favorites", json.encodeToJsonElement(data.favorites.take(200).map(::register)))
            put("downloads", json.encodeToJsonElement(data.downloads.take(100).map(::register)))
            put("local", json.encodeToJsonElement(data.local.take(100).map(::register)))
            put("playlists", buildJsonArray { data.playlists.take(100).forEach { list -> add(buildJsonObject { put("id", "local:" + list.id); put("title", list.title.take(100)); put("count", list.songs.size) }) } })
            put("home", buildJsonArray { data.home.take(12).forEach { shelf -> add(buildJsonObject {
                put("title", shelf.title.take(100)); put("items", buildJsonArray { shelf.items.take(18).forEach { item ->
                    if (item.videoId != null) add(buildJsonObject { put("kind", "track"); put("track", json.encodeToJsonElement(register(Song(videoId = requireNotNull(item.videoId), title = item.title, artist = item.subtitle, thumbnailUrl = item.thumbnailUrl)))) })
                    else if (item.browseId != null) add(collection(BrowseItem(requireNotNull(item.browseId), item.title, item.subtitle, item.thumbnailUrl, com.music.bitchord.data.model.BrowseType.PLAYLIST)))
                } })
            }) } })
            put("settings", json.encodeToJsonElement(data.settings))
            put("outputs", buildJsonArray {
                add(buildJsonObject { put("id", ""); put("name", "PC system default") })
                DesktopAudioDevices.available().forEach { device -> add(buildJsonObject { put("id", device.id); put("name", device.name) }) }
            })
            put("selectedOutput", DesktopAudioDevices.selected.value)
            put("mixPresets", buildJsonArray { DesktopMixProfile.presets.forEach { add(it.name) } })
            put("capabilities", buildJsonObject {
                put("phoneAudio", true); put("pcPlayback", true); put("pcMustStayOpen", true)
                put("nativeAccountSignIn", false); put("nativeWindowsFeaturesOnPhone", false)
            })
        })
    }
    private fun catalogSearch(exchange: HttpExchange, who: Session, body: JsonObject) {
        limit("search:${who.id}", 15)
        val query = body["query"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        require(query.length in 2..100) { "Search with 2 to 100 characters." }
        val filter = runCatching { SearchFilter.valueOf(body["filter"]?.jsonPrimitive?.contentOrNull ?: "ALL") }.getOrDefault(SearchFilter.ALL)
        val found = runBlocking { withTimeout(15_000) { DesktopSearchClient.search(query, filter).getOrThrow() } }
        reply(exchange, buildJsonArray { found.take(30).forEach { result -> when (result) {
            is SearchResult.Track -> add(buildJsonObject { put("kind", "track"); put("track", json.encodeToJsonElement(register(result.song))) })
            is SearchResult.TopTrack -> add(buildJsonObject { put("kind", "track"); put("track", json.encodeToJsonElement(register(result.song))) })
            is SearchResult.Browse -> add(collection(result.item))
        } } })
    }
    private fun browse(exchange: HttpExchange, body: JsonObject) {
        val id = body["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tracks: List<Song>
        val title: String
        if (id.startsWith("local:")) {
            val list = snapshot().playlists.firstOrNull { "local:" + it.id == id } ?: throw PhoneRemoteError(404, "Playlist not found.")
            tracks = list.songs; title = list.title
        } else {
            val original = browseItems[id] ?: throw PhoneRemoteError(409, "Find that album, artist, or playlist again.")
            val found = runBlocking { withTimeout(18_000) { DesktopSearchClient.browse(original).getOrThrow() } }
            if (found.songs.isEmpty() && original.startsWith("UC")) {
                val artist = runBlocking { withTimeout(18_000) { DesktopSearchClient.artistPage(original).getOrThrow() } }
                tracks = artist.songs; title = artist.name ?: found.title
            } else { tracks = found.songs; title = found.title }
        }
        reply(exchange, buildJsonObject { put("title", title); put("id", id); put("tracks", json.encodeToJsonElement(tracks.take(200).map(::register))) })
    }
    private fun serveAudio(exchange: HttpExchange) {
        val params = exchange.requestURI.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2); if (pair.size == 2) pair[0] to URLDecoder.decode(pair[1], "UTF-8") else null
        }.toMap()
        val ticket = mediaTickets[params["ticket"]] ?: throw PhoneRemoteError(401, "Prepare this song again.")
        val who = sessions[ticket.session]
        if (who == null || System.currentTimeMillis() > ticket.expires || System.currentTimeMillis() - who.created > 8 * 60 * 60 * 1000) throw PhoneRemoteError(401, "The audio invitation expired.")
        if (!audio.streams.tryAcquire()) throw PhoneRemoteError(429, "Four phone streams are already active. Try again shortly.")
        try {
            val source = runBlocking { withTimeout(35_000) { audio.source(known(ticket.song), ticket.quality) } }
            val range = PhoneByteRange.parse(exchange.requestHeaders.getFirst("Range"), source.length)
            exchange.responseHeaders.set("Content-Type", source.mime)
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("Cache-Control", "private, no-store")
            exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            if (range.partial) exchange.responseHeaders.set("Content-Range", "bytes ${range.start}-${range.end}/${source.length}")
            if (params["download"] == "1") exchange.responseHeaders.set("Content-Disposition", "attachment; filename=BitChord-${ticket.song.take(12)}.${if (source.mime == "audio/mp4") "m4a" else "audio"}")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.set("Content-Length", range.count.toString()); exchange.sendResponseHeaders(if (range.partial) 206 else 200, -1)
            } else {
                exchange.sendResponseHeaders(if (range.partial) 206 else 200, range.count)
                exchange.responseBody.use { output -> source.write(range, output) { running.value && sessions.containsKey(ticket.session) } }
            }
        } finally { audio.streams.release() }
    }
}
