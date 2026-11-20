package com.music.bitchord.desktop

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

internal data class DjTurn(val role: String, val text: String)
internal data class DjPlan(val action: String, val spoken: String, val query: String = "", val seedVideoId: String? = null)

internal object DesktopTalkingDj {
    const val MODEL = "qwen3:0.6b"
    val active = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("Ready when you are")
    val turns = MutableStateFlow<List<DjTurn>>(emptyList())
    val voiceEnabled = MutableStateFlow(DesktopPersistence().boolean("dj_spoken", true))
    val preview = MutableStateFlow<List<Song>>(emptyList())
    var lastIntroducedId: String? = null
    private var setTrackCount = 0
    private var vibeIndex = 0
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private var ownedServer: Process? = null

    private suspend fun ensureServer() = withContext(Dispatchers.IO) {
        val ping = HttpRequest.newBuilder(URI("http://127.0.0.1:11434/api/tags")).timeout(Duration.ofSeconds(2)).GET().build()
        if (runCatching { client.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 }.getOrDefault(false)) return@withContext
        val executable = Path.of(System.getenv("LOCALAPPDATA"), "Programs", "Ollama", "ollama.exe")
        check(Files.isRegularFile(executable)) { "Ollama is not installed. Install Ollama and the $MODEL model to use the local DJ." }
        if (ownedServer?.isAlive != true) {
            val builder = ProcessBuilder(executable.toString(), "serve").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["OLLAMA_HOST"] = "127.0.0.1:11434"
            builder.environment()["OLLAMA_NUM_PARALLEL"] = "1"
            ownedServer = builder.start()
            Runtime.getRuntime().addShutdownHook(Thread { ownedServer?.destroy() })
        }
        repeat(20) {
            delay(250)
            if (runCatching { client.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 }.getOrDefault(false)) return@withContext
        }
        error("The local DJ service is starting slowly. Please try again.")
    }

    fun setVoice(on: Boolean) { voiceEnabled.value = on; DesktopPersistence().saveBoolean("dj_spoken", on) }
    fun add(role: String, text: String) { turns.value = (turns.value + DjTurn(role, text.take(1500))).takeLast(30) }
    fun artists(history: List<Song>): List<String> = history.take(80).filter { it.artist.isNotBlank() }
        .groupingBy { it.artist }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key }

    fun suggestions(history: List<Song>): List<String> {
        val favorites = artists(history)
        return listOf(favorites.firstOrNull()?.let { "More music like $it" } ?: "Play some feel-good favorites",
            favorites.getOrNull(1)?.let { "A fresh mix with $it" } ?: "Something upbeat for my day", "A laid-back mix to wind down")
    }

    fun beginSet(songs: List<Song>) {
        require(songs.isNotEmpty())
        preview.value = songs
        lastIntroducedId = songs.first().videoId
        setTrackCount = 0
        active.value = true
    }

    fun setIntroduction(plan: DjPlan, first: Song): String =
        "${plan.spoken.trim().trimEnd('.')}. First up, ${first.title}, by ${first.artist}."

    // Speak once per four new tracks. Recomposition, pause/resume and repeat-one do not count.
    fun trackCommentary(song: Song): String? {
        if (lastIntroducedId == song.videoId) return null
        lastIntroducedId = song.videoId
        setTrackCount++
        if (setTrackCount % 4 != 0) return null
        return when ((setTrackCount / 4) % 3) {
            1 -> "Let's keep this going. Here's ${song.artist}, with ${song.title}. Tap Ask DJ whenever you want to change it up."
            2 -> "All right, a little more music for you. This is ${song.title}, by ${song.artist}."
            else -> "Your next stretch starts here. ${song.artist}, ${song.title}. Let's get into it."
        }
    }

    fun localCommand(text: String, current: Song?): DjPlan? {
        val request = text.trim().lowercase().trimEnd('.', '!', '?')
        return when (request) {
            "pause", "pause music", "pause the music", "stop music", "stop the music" -> DjPlan("pause", "Pausing the music.")
            "next", "next song", "skip", "skip this", "skip this song" -> DjPlan("next", "Let's try the next one.")
            "play", "resume", "resume music", "continue", "continue playing" -> DjPlan("resume", "Let's keep the music going.")
            "what's playing", "what is playing", "what am i listening to", "who is this" -> DjPlan("chat",
                current?.let { "You're listening to ${it.title}, by ${it.artist}." } ?: "Nothing is playing yet. Tell me what you're in the mood for.")
            else -> null
        }
    }

    suspend fun plan(request: String, history: List<Song>, current: Song?): DjPlan {
        localCommand(request, current)?.let { return it }
        personalStart(request, history)?.let { return it }
        val favorites = artists(history)
        val context = if (history.isEmpty()) "No recent listening yet. Do not pretend to know their taste."
            else "Recent artists: ${favorites.joinToString().take(250)}. Recent tracks: ${history.take(4).joinToString { it.title }.take(220)}."
        val system = """Translate the latest music request into a short music search phrase. Return only JSON with one string field: query. Honor the requested artist, genre, mood and activity above listening history. If the request names a genre, do not add unrelated artists from history. Use history only for requests such as more like this. Never return a question or commentary. Interpret follow-ups using the conversation."""
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            turns.value.dropLastWhile { it.role == "you" && it.text == request }.takeLast(4).forEach { turn -> add(buildJsonObject { put("role", if (turn.role == "you") "user" else "assistant"); put("content", turn.text.take(200)) }) }
            add(buildJsonObject { put("role", "user"); put("content", "$context\nRequest: ${request.take(300)}") })
        }
        val body = buildJsonObject {
            put("model", MODEL); put("stream", false); put("think", false); put("keep_alive", 0); put("format", "json")
            put("messages", messages)
            putJsonObject("options") { put("num_ctx", 2048); put("num_predict", 128); put("num_thread", 2); put("temperature", 0.4) }
        }
        val response = localPost(body)
        val content = response["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: error("The local model did not return a reply. Please try again.")
        return parsePlan(content, request, favorites.firstOrNull())
    }

    internal fun personalStart(request: String, history: List<Song>): DjPlan? {
        val normalized = request.trim().lowercase()
        val change = normalized in setOf("switch up the vibe", "change the vibe", "change the mood")
        val personal = change || normalized in setOf("start my dj", "surprise me", "more like my recent listening", "play something based on my listening")
        if (!personal || history.isEmpty()) return null
        val favorites = artists(history)
        if (favorites.isEmpty()) return null
        if (change) vibeIndex++ else vibeIndex = 0
        val favorite = favorites[vibeIndex % favorites.size]
        val seed = history.firstOrNull { it.artist == favorite && Regex("[A-Za-z0-9_-]{11}").matches(it.videoId) }
        val intro = if (change) "All right, let's switch it up. A different set, starting around $favorite." else "Hey, it's your DJ. You've been listening to $favorite, so let's start there and see where the music takes us."
        return DjPlan("play", intro, "$favorite music", seed?.videoId)
    }

    internal fun parsePlan(content: String, request: String, favorite: String?): DjPlan {
        val value = json.parseToJsonElement(content.substringAfter("```json").substringBefore("```").trim()).jsonObject
        require(value.containsKey("query")) { "The local DJ returned an empty reply. Please try again." }
        val proposed = value["query"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        // Model output can select music, never arbitrary URLs, executable commands or application actions.
        val query = if (proposed.isBlank() || proposed.contains('?') || proposed.contains("://"))
            listOfNotNull(request.take(90), favorite).joinToString(" ") else proposed.take(160)
        // A small model can fabricate artist/track facts even with strict prompting.
        // It selects search terms only; speech is grounded in requests and selected metadata.
        val words = request.lowercase()
        val spoken = when {
            listOf("upbeat", "energetic", "workout", "party").any { it in words } -> "Got you. Let's lift the energy."
            listOf("calm", "mellow", "quiet", "wind down", "laid-back").any { it in words } -> "Got you. Let's ease into something more laid-back."
            listOf("discover", "something new", "different").any { it in words } -> "All right, let's try a different direction."
            else -> "Got you. I've lined up a fresh set for you."
        }
        return DjPlan("play", spoken, query)
    }

    suspend fun songs(plan: DjPlan, history: List<Song>): List<Song> = withContext(Dispatchers.IO) {
        val radio = plan.seedVideoId?.let { YtMusicRepository.radio(it).getOrNull() }.orEmpty()
        val results = (radio.ifEmpty {
            YtMusicRepository.search(plan.query, SearchFilter.SONGS).getOrThrow()
                .filterIsInstance<SearchResult.Track>().map { it.song }
        }).distinctBy { it.videoId }
        val excluded = (history.take(3) + preview.value.take(3)).map { it.videoId }.toSet()
        val chosen = results.filterNot { it.videoId in excluded }.ifEmpty { results }.take(12)
        check(chosen.isNotEmpty()) { "No playable matches came back. Try another request." }
        DesktopDj.sequence(chosen, DesktopDj.analyses(chosen), DjArc.SMOOTH)
    }

    private suspend fun localPost(body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        ensureServer()
        val url = URI("http://127.0.0.1:11434/api/chat")
        val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            throw IllegalStateException("Start Ollama on this PC, then try again. The DJ uses the local $MODEL model.", failure)
        }
        check(response.statusCode() == 200) { "The local DJ could not load $MODEL. Open Ollama and check that model is available." }
        json.parseToJsonElement(response.body()).jsonObject
    }
}

internal object DesktopDjVoice {
    init { Runtime.getRuntime().addShutdownHook(Thread { stop() }) }
    val speaking = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    private val speechLock = Mutex()
    private val listenLock = Mutex()
    @Volatile private var activeProcess: Process? = null
    private val stopped = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private fun terminate(process: Process) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
    }
    fun stop() { activeProcess?.let { stopped.add(it.pid()); terminate(it) } }
    private fun neuralVoiceDirectory(): Path? {
        val resources = System.getProperty("compose.application.resources.dir") ?: return null
        return Path.of(resources, "piper").takeIf {
            Files.isRegularFile(it.resolve("piper.exe")) && Files.isRegularFile(it.resolve("en_US-joe-medium.onnx"))
        }
    }
    private fun executable(): Path {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/voice/BitChordVoice.exe")) { "Windows speech helper is missing from this build." }.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        val dir = DesktopCustomPaths.root.resolve("voice").also { Files.createDirectories(it) }
        return dir.resolve("BitChordVoice-$hash.exe").also { target ->
            if (!Files.exists(target) || !Files.readAllBytes(target).contentEquals(bytes)) Files.write(target, bytes)
        }
    }
    private suspend fun run(vararg args: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(executable().toString()) + args).redirectErrorStream(true).start()
        activeProcess = process
        try {
            val start = System.nanoTime()
            while (!process.waitFor(75, TimeUnit.MILLISECONDS)) {
                ensureActive()
                check((System.nanoTime() - start) < 40_000_000_000L) { "Windows speech took too long. Please try again." }
            }
            if (stopped.remove(process.pid())) throw CancellationException("Speech stopped")
            val result = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim().removePrefix("\uFEFF") }
            check(process.exitValue() == 0) { result.ifBlank { "Windows speech is unavailable. You can still type." } }
            result.take(1000)
        } finally { if (process.isAlive) terminate(process); if (activeProcess === process) activeProcess = null; stopped.remove(process.pid()) }
    }
    suspend fun speak(text: String) = speechLock.withLock {
        val file = withContext(Dispatchers.IO) {
            Files.createTempFile(DesktopCustomPaths.root.resolve("voice").also { Files.createDirectories(it) }, "intro-", ".txt")
                .also { Files.writeString(it, text.take(1000)) }
        }
        speaking.value = true
        try {
            val voice = neuralVoiceDirectory()
            if (voice != null) run("neural-speak", voice.toString(), file.toString()) else run("speak", file.toString())
        } finally {
            speaking.value = false
            withContext(NonCancellable + Dispatchers.IO) {
                Files.deleteIfExists(file)
                Files.deleteIfExists(file.resolveSibling(file.fileName.toString().removeSuffix(".txt") + ".wav"))
            }
        }
    }
    suspend fun render(text: String): ByteArray = speechLock.withLock {
        withContext(Dispatchers.IO) {
            val dir = DesktopCustomPaths.root.resolve("voice").also { Files.createDirectories(it) }
            val input = Files.createTempFile(dir, "phone-intro-", ".txt")
            val output = input.resolveSibling(input.fileName.toString() + ".wav")
            try {
                Files.writeString(input, text.take(600))
                val voice = neuralVoiceDirectory()
                if (voice != null) run("neural-render", voice.toString(), input.toString(), output.toString())
                else run("render", input.toString(), output.toString())
                check(Files.size(output) in 44..4_000_000) { "DJ voice was unavailable." }
                Files.readAllBytes(output)
            } finally { Files.deleteIfExists(input); Files.deleteIfExists(output) }
        }
    }
    suspend fun listen(): String = listenLock.withLock {
        listening.value = true
        try { run("listen") } finally { listening.value = false }
    }
}
