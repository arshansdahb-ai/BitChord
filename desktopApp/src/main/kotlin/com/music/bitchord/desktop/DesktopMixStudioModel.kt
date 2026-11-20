package com.music.bitchord.desktop

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.math.abs
import kotlin.math.sqrt

@Serializable
internal enum class MixCurve { EQUAL_POWER, LINEAR, SOFT }

@Serializable
internal data class DesktopMixProfile(
    val name: String = "Smart blend",
    val automix: Boolean = true,
    val seconds: Int = 6,
    val phraseBars: Int = 0,
    val mixOnSkip: Boolean = true,
    val curve: MixCurve = MixCurve.EQUAL_POWER,
    val fx: Float = 0.4f,
    val levelMatch: Boolean = true,
    val targetDb: Float = -18f,
) {
    fun sanitized() = copy(
        name = name.take(60), seconds = seconds.coerceIn(0, 12),
        phraseBars = phraseBars.takeIf { it == 8 || it == 16 } ?: 0,
        fx = if (fx.isFinite()) fx.coerceIn(0f, 1f) else 0f,
        targetDb = if (targetDb.isFinite()) targetDb.coerceIn(-24f, -12f) else -18f,
    )
    companion object {
        val presets = listOf(
            DesktopMixProfile(),
            DesktopMixProfile("Club set", seconds = 10, phraseBars = 16, fx = 0.75f),
            DesktopMixProfile("Smooth radio", automix = false, seconds = 6, curve = MixCurve.SOFT, fx = 0f),
            DesktopMixProfile("Focus flow", seconds = 10, phraseBars = 8, fx = 0f, targetDb = -21f),
            DesktopMixProfile("Album fidelity", automix = false, seconds = 0, mixOnSkip = false, fx = 0f, levelMatch = false),
        )
    }
}

@Serializable
internal data class DesktopPairMix(val seconds: Int = 6, val curve: MixCurve = MixCurve.EQUAL_POWER)

/** Immutable snapshots on StateFlow keep preference I/O off the audio thread. */
internal object DesktopMixSettings {
    private val prefs = DesktopPersistence().preferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val global = MutableStateFlow(read<DesktopMixProfile>("studio_profile")?.sanitized() ?: DesktopMixProfile())
    val current = MutableStateFlow(global.value)
    val playlists = MutableStateFlow(read<Map<String, DesktopMixProfile>>("studio_playlists").orEmpty())
    val pairs = MutableStateFlow(read<Map<String, DesktopPairMix>>("studio_pairs").orEmpty())
    val motion = MutableStateFlow(read<Map<String, String>>("studio_motion").orEmpty())
    val liquidGlass = MutableStateFlow(prefs.getBoolean("studio_liquid_glass", true))
    val glassTint = MutableStateFlow(prefs.getFloat("studio_glass_tint", 0.5f).coerceIn(0f, 1f))

    private inline fun <reified T> read(key: String): T? = runCatching {
        json.decodeFromString<T>(DesktopPreferenceChunks.read(prefs, key) ?: return null)
    }.getOrNull()
    private inline fun <reified T> save(key: String, value: T) =
        DesktopPreferenceChunks.write(prefs, key, json.encodeToString(value))

    fun apply(profile: DesktopMixProfile) {
        current.value = profile.sanitized()
        global.value = current.value
        save("studio_profile", current.value)
    }
    fun savePlaylist(id: String, profile: DesktopMixProfile) {
        playlists.value = (playlists.value + (id to profile.sanitized())).entries.toList().takeLast(100).associate { it.toPair() }
        save("studio_playlists", playlists.value)
    }
    fun removePlaylist(id: String) { playlists.value -= id; save("studio_playlists", playlists.value) }
    fun pair(from: String?, into: String?): DesktopPairMix? = pairs.value["$from|$into"]
    fun savePair(from: String, into: String, mix: DesktopPairMix?) {
        val key = "$from|$into"
        pairs.value = if (mix == null) pairs.value - key else
            (pairs.value + (key to mix.copy(seconds = mix.seconds.coerceIn(1, 12)))).entries.toList().takeLast(300).associate { it.toPair() }
        save("studio_pairs", pairs.value)
    }
    fun setGlass(on: Boolean) { liquidGlass.value = on; prefs.putBoolean("studio_liquid_glass", on) }
    fun setGlassTint(value: Float) {
        glassTint.value = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f
        prefs.putFloat("studio_glass_tint", glassTint.value)
    }
    fun setMotion(id: String, url: String) {
        require(url.isBlank() || validMotionUrl(url)) { "Use a direct HTTPS video URL (.mp4, .webm or .m3u8)." }
        motion.value = if (url.isBlank()) motion.value - id else
            (motion.value + (id to url.trim())).entries.toList().takeLast(100).associate { it.toPair() }
        save("studio_motion", motion.value)
    }
    fun artwork(song: Song): DesktopCanvasArtwork? = motion.value[song.videoId]?.let {
        DesktopCanvasArtwork(it, title = song.title, artist = song.artist, album = song.albumName)
    }
}

internal fun validMotionUrl(raw: String): Boolean = runCatching {
    val uri = URI(raw.trim())
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        listOf(".mp4", ".webm", ".m3u8").any { uri.path.orEmpty().endsWith(it, true) }
}.getOrDefault(false)

internal fun mixGains(curve: MixCurve, raw: Float): Pair<Float, Float> {
    val t = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f
    val p = if (curve == MixCurve.SOFT) t * t * (3f - 2f * t) else t
    return if (curve == MixCurve.LINEAR) (1f - p) to p else sqrt(1f - p) to sqrt(p)
}

internal enum class DjMood(val label: String, val query: String) {
    DISCOVERY("Discovery", "new music discovery"), FOCUS("Focus", "instrumental focus study"),
    CHILL("Chill", "chill downtempo soul"), PARTY("Party", "dance house party"),
    WORKOUT("Workout", "workout electronic energy"), JAZZ("Jazz", "jazz essentials"),
    CLASSICAL("Classical", "classical piano essentials"), SLEEP("Sleep", "ambient sleep calm"),
}
internal enum class DjArc { SMOOTH, BUILD, WIND_DOWN }

/** Unknown musical data stays unknown. Only cached, measured tracks contribute BPM/key evidence. */
internal object DesktopDj {
    fun analyses(songs: List<Song>): Map<String, TrackAnalysis> {
        val store = AnalysisStore { DesktopAnalysisRuntime.analysisHome() }
        return songs.mapNotNull { song -> store.load(song.videoId)?.takeIf { it.isUsable }?.let { song.videoId to it } }.toMap()
    }
    fun sequence(songs: List<Song>, analyses: Map<String, TrackAnalysis>, arc: DjArc): List<Song> {
        val remaining = songs.distinctBy { it.videoId }.toMutableList()
        if (remaining.size < 2) return remaining
        val ordered = mutableListOf(remaining.removeAt(0))
        while (remaining.isNotEmpty()) {
            val previous = ordered.last()
            val a = analyses[previous.videoId]
            val next = remaining.minBy { song ->
                val b = analyses[song.videoId]
                var cost = if (previous.artist.equals(song.artist, true)) 0.6 else 0.0
                if (a != null && b != null) {
                    val tempo = listOf(b.bpm, b.bpm * 2, b.bpm / 2).minOf { abs(it - a.bpm) / a.bpm.coerceAtLeast(1.0) }
                    cost += tempo * 3
                    if (a.key.isNotBlank() && b.key.isNotBlank() && a.key == b.key) cost -= 0.25
                    if (arc == DjArc.BUILD && b.bpm < a.bpm) cost += 0.3
                    if (arc == DjArc.WIND_DOWN && b.bpm > a.bpm) cost += 0.3
                }
                cost
            }
            ordered += next
            remaining.remove(next)
        }
        return ordered
    }
    suspend fun discover(mood: DjMood, seed: String, avoid: Set<String>): List<Song> = withContext(Dispatchers.IO) {
        val cleaned = seed.trim().take(120)
        val queries = listOf("$cleaned ${mood.query}", "$cleaned ${mood.query} songs")
        val songs = mutableListOf<Song>()
        var lastError: Throwable? = null
        for (query in queries) {
            ensureActive()
            YtMusicRepository.search(query.trim(), SearchFilter.SONGS)
                .onSuccess { found -> songs += found.filterIsInstance<SearchResult.Track>().map { it.song } }
                .onFailure { lastError = it }
        }
        val result = songs.distinctBy { it.videoId }.filterNot { it.videoId in avoid }.take(30)
        if (result.isEmpty() && lastError != null) throw IllegalStateException("Discovery could not reach the music service. Try again.", lastError)
        result
    }
}
