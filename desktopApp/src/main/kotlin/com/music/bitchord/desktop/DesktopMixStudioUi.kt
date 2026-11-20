package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.TrackAnalysisState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

@Composable
internal fun DesktopMixStudio(
    song: Song?, queue: List<Song>, history: List<Song>, activeProfile: DesktopMixProfile,
    playing: Boolean, onPlayPause: () -> Unit, onNext: () -> Unit,
    playlistId: String?, playlistTitle: String?, playlistSongs: List<Song>,
    quality: String, precision: String, animated: Boolean, fullBleed: Boolean,
    spatial: Boolean, skipSilence: Boolean,
    onApply: (DesktopMixProfile) -> Unit,
    onPlay: (List<Song>) -> Unit, onQueue: (List<Song>) -> Unit,
    onSavePlaylist: (String, List<Song>) -> Unit,
    onQuality: (String) -> Unit, onPrecision: (String) -> Unit,
    onAnimated: (Boolean) -> Unit, onFullBleed: (Boolean) -> Unit,
    onSpatial: (Boolean) -> Unit, onSkipSilence: (Boolean) -> Unit,
    onEqualizer: () -> Unit, onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var profile by remember { mutableStateOf(activeProfile) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val songs = playlistSongs.ifEmpty { queue }
    var measured by remember { mutableStateOf(0) }
    var analyzing by remember { mutableStateOf<Job?>(null) }
    var analysisStatus by remember { mutableStateOf("") }
    val motion by DesktopMixSettings.motion.collectAsState()
    var motionUrl by remember(song?.videoId) { mutableStateOf(motion[song?.videoId].orEmpty()) }
    var pairIndex by remember { mutableStateOf(0) }
    var pairSeconds by remember { mutableStateOf(6f) }
    var pairCurve by remember { mutableStateOf(MixCurve.EQUAL_POWER) }
    var pairEdits by remember { mutableStateOf(0) }
    var mood by remember { mutableStateOf(DjMood.DISCOVERY) }
    var arc by remember { mutableStateOf(DjArc.SMOOTH) }
    var seed by remember { mutableStateOf("") }
    var avoidRecent by remember { mutableStateOf(true) }
    var discovering by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Song>>(emptyList()) }
    var name by remember { mutableStateOf("My DJ mix") }
    LaunchedEffect(songs) { measured = withContext(Dispatchers.IO) { DesktopDj.analyses(songs).size } }
    DisposableEffect(Unit) { onDispose { analyzing?.cancel() } }

    fun apply(value: DesktopMixProfile) { profile = value.sanitized(); onApply(profile); note = "Mix settings applied" }
    fun sequence(input: List<Song>, play: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { DesktopDj.sequence(input, DesktopDj.analyses(input), arc) }
            suggestions = result
            if (play && result.isNotEmpty()) { onApply(profile); onPlay(result); note = "Started ${result.size}-track mix" }
            else note = "Ordered ${result.size} tracks; ${measured} have measured beat data"
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.width(930.dp).heightIn(max = 720.dp)
                .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp), color = Color(0xFF171922),
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Mix Studio", style = MaterialTheme.typography.headlineMedium)
                        Text("Your music. Your transitions.", color = DesktopSecondary)
                    }
                    Row {
                        TextButton(enabled = song != null, onClick = onPlayPause) { Text(if (playing) "Pause" else "Play") }
                        TextButton(enabled = song != null, onClick = onNext) { Text("Next") }
                        TextButton(onClick = onDismiss) { Text("Done") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Mix & playlists", "Discovery", "Artwork & glass", "Sound").forEachIndexed { i, label ->
                        FilterChip(selected = tab == i, onClick = { tab = i; note = null }, label = { Text(label) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))
                Column(Modifier.weight(1f, fill = false).verticalScroll(remember(tab) { ScrollState(0) }), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (tab) {
                        0 -> {
                            StudioHeading("Make every handoff yours", "Neural beat and phrase analysis powers Automix; manual fades give you direct control.")
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesktopMixProfile.presets.forEach { preset ->
                                    FilterChip(selected = profile.name == preset.name, onClick = { apply(preset) }, label = { Text(preset.name) })
                                }
                            }
                            StudioToggle("Beat-aware Automix", "Adapts entry points and tempo when analysis is available", profile.automix) { apply(profile.copy(automix = it)) }
                            StudioToggle("Blend when skipping", "Mix into the prepared next deck instead of cutting", profile.mixOnSkip) { apply(profile.copy(mixOnSkip = it)) }
                            StudioSlider("Fallback / manual fade · ${profile.seconds}s", profile.seconds.toFloat(), 0f..12f, 11) { apply(profile.copy(seconds = it.roundToInt())) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0 to "Adaptive", 8 to "8 bars", 16 to "16 bars").forEach { (bars, label) ->
                                    FilterChip(selected = profile.phraseBars == bars, onClick = { apply(profile.copy(phraseBars = bars)) }, label = { Text(label) })
                                }
                            }
                            StudioLabel("Manual fade curve")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MixCurve.entries.forEach { curve -> FilterChip(selected = profile.curve == curve, onClick = { apply(profile.copy(curve = curve)) }, label = { Text(curve.name.replace('_', ' ').lowercase()) }) }
                            }
                            StudioSlider("Transition effects · ${(profile.fx * 100).roundToInt()}%", profile.fx, 0f..1f) { apply(profile.copy(fx = it)) }
                            StudioHeading(playlistTitle ?: "Current queue", "${songs.size} tracks · $measured analyzed. Ordering uses measured tempo and key when available, and avoids adjacent repeat artists.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = playlistId != null, onClick = {
                                    DesktopMixSettings.savePlaylist(playlistId!!, profile); note = "Saved for ${playlistTitle ?: "this playlist"}"
                                }) { Text("Save playlist profile") }
                                OutlinedButton(enabled = playlistId != null, onClick = { DesktopMixSettings.removePlaylist(playlistId!!); note = "Playlist profile removed" }) { Text("Use global profile") }
                                Button(enabled = songs.isNotEmpty(), onClick = { sequence(songs, true) }) { Text("Sequence & play") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(enabled = songs.isNotEmpty() && analyzing?.isActive != true, onClick = {
                                    analyzing = scope.launch {
                                        val analyzer = DesktopTrackAnalyzer()
                                        try {
                                            songs.distinctBy { it.videoId }.take(12).forEachIndexed { index, track ->
                                                analysisStatus = "Analyzing ${index + 1} / ${minOf(12, songs.size)} · ${track.title}"
                                                val duration = track.durationMillis() / 1000.0
                                                if (duration > 0) {
                                                    analyzer.request(track, null, duration)
                                                    withTimeoutOrNull(90_000) {
                                                        while (!analyzer.isAnalysed(track.videoId) && analyzer.stateFor(track.videoId) != TrackAnalysisState.FAILED) delay(500)
                                                    }
                                                }
                                                measured = withContext(Dispatchers.IO) { DesktopDj.analyses(songs).size }
                                            }
                                            analysisStatus = "Analysis complete · $measured tracks measured"
                                        } finally { analyzer.release() }
                                    }
                                }) { Text("Analyze up to 12 tracks") }
                                if (analyzing?.isActive == true) TextButton(onClick = { analyzing?.cancel(); analysisStatus = "Analysis stopped" }) { Text("Stop") }
                            }
                            if (analysisStatus.isNotBlank()) Text(analysisStatus, color = DesktopSecondary)
                            if (songs.size > 1) {
                                StudioHeading("Transition editor", "Overrides beat matching for this exact pair with your chosen fade. Changes apply at the next handoff.")
                                val index = pairIndex.coerceIn(0, songs.size - 2)
                                Text("${index + 1}. ${songs[index].title}  →  ${songs[index + 1].title}")
                                val saved = remember(index, pairEdits) { DesktopMixSettings.pair(songs[index].videoId, songs[index + 1].videoId) }
                                Text(saved?.let { "Saved: ${it.seconds}s · ${it.curve.name.lowercase()}" } ?: "Using the active mix profile", color = DesktopSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = index > 0, onClick = { pairIndex = index - 1 }) { Text("Previous pair") }
                                    TextButton(enabled = index < songs.size - 2, onClick = { pairIndex = index + 1 }) { Text("Next pair") }
                                }
                                StudioSlider("Pair fade · ${pairSeconds.roundToInt()}s", pairSeconds, 1f..12f, 10) { pairSeconds = it }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MixCurve.entries.forEach { curve -> FilterChip(selected = pairCurve == curve, onClick = { pairCurve = curve }, label = { Text(curve.name.lowercase().replace('_', ' ')) }) }
                                    Button(onClick = { DesktopMixSettings.savePair(songs[index].videoId, songs[index + 1].videoId, DesktopPairMix(pairSeconds.roundToInt(), pairCurve)); pairEdits++; note = "Transition saved" }) { Text("Save") }
                                    TextButton(onClick = { DesktopMixSettings.savePair(songs[index].videoId, songs[index + 1].videoId, null); pairEdits++; note = "Transition reset" }) { Text("Reset") }
                                }
                            }
                        }
                        1 -> {
                            StudioHeading("A set for your mood", "Mood-based discovery plus neural beat analysis for mixing. No conversational AI or cloud model is connected.")
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DjMood.entries.forEach { item -> FilterChip(selected = item == mood, onClick = { mood = item }, label = { Text(item.label) }) }
                            }
                            OutlinedTextField(value = seed, onValueChange = { seed = it.take(120) }, label = { Text("Artist, genre or theme (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            StudioLabel("Energy direction")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DjArc.entries.forEach { item -> FilterChip(selected = arc == item, onClick = { arc = item }, label = { Text(item.name.lowercase().replace('_', ' ')) }) }
                            }
                            StudioToggle("Fresh discoveries", "Exclude songs in your recent history and current queue", avoidRecent) { avoidRecent = it }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !discovering, onClick = {
                                    scope.launch {
                                        discovering = true; note = null
                                        try {
                                            val avoid = if (avoidRecent) (history + queue).map { it.videoId }.toSet() else emptySet()
                                            val found = DesktopDj.discover(mood, seed, avoid)
                                            suggestions = withContext(Dispatchers.IO) { DesktopDj.sequence(found, DesktopDj.analyses(found), arc) }
                                            name = "${mood.label} · ${seed.ifBlank { "DJ mix" }}"
                                            note = if (found.isEmpty()) "No fresh matches. Change the theme or turn off Fresh discoveries." else "${found.size} tracks ready to preview"
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { note = failure.message ?: "Discovery failed. Try again." }
                                        finally { discovering = false }
                                    }
                                }) { Text(if (discovering) "Finding your set…" else "Build my mix") }
                                OutlinedButton(enabled = songs.isNotEmpty(), onClick = { sequence(songs, false) }) { Text("Use playlist / queue") }
                            }
                            if (suggestions.isNotEmpty()) {
                                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Mix name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                LazyColumn(Modifier.height(210.dp).fillMaxWidth()) {
                                    items(suggestions.size, key = { suggestions[it].videoId }) { i ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${i + 1}", modifier = Modifier.width(34.dp), color = DesktopSecondary)
                                            Column(Modifier.weight(1f)) { Text(suggestions[i].title, maxLines = 1); Text(suggestions[i].artist, color = DesktopSecondary, maxLines = 1) }
                                            TextButton(onClick = { suggestions = suggestions.filterIndexed { index, _ -> index != i } }) { Text("Remove") }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onApply(profile); onPlay(suggestions); note = "DJ set started" }) { Text("Play mix") }
                                    OutlinedButton(onClick = { onQueue(suggestions); note = "Added to your queue" }) { Text("Queue next") }
                                    OutlinedButton(enabled = name.isNotBlank(), onClick = { onSavePlaylist(name.trim(), suggestions); note = "Saved locally as $name" }) { Text("Save playlist") }
                                }
                            }
                        }
                        2 -> {
                            StudioHeading("Motion, color and glass", "Automatic album motion art is already supported. Add your own silent video loop for a specific track.")
                            StudioToggle("Animated cover art", "Use available motion art from the configured providers", animated, onAnimated)
                            StudioToggle("Full-bleed player", "Let the artwork fill the player sleeve", fullBleed, onFullBleed)
                            val glass by DesktopMixSettings.liquidGlass.collectAsState()
                            val ambient by DesktopAppearanceSettings.ambientBackdrop.collectAsState()
                            val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
                            StudioToggle("Liquid glass highlights", "Translucent chrome with softly lit edges", glass, DesktopMixSettings::setGlass)
                            val glassTint by DesktopMixSettings.glassTint.collectAsState()
                            StudioSlider("Glass tint · ${(glassTint * 100).roundToInt()}%", glassTint, 0f..1f) { DesktopMixSettings.setGlassTint(it) }
                            StudioToggle("Artwork atmosphere", "Let the current cover color the whole window", ambient, DesktopAppearanceSettings::setAmbientBackdrop)
                            StudioToggle("Reduce motion", "Freeze animated background movement", reduceMotion, DesktopAppearanceSettings::setReduceAnimation)
                            StudioLabel("Window material")
                            val material by DesktopWindowBackdrop.selected.collectAsState()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesktopBackdrop.entries.forEach { backdrop -> FilterChip(selected = material == backdrop, onClick = { DesktopWindowBackdrop.set(backdrop) }, label = { Text(backdrop.label) }) }
                            }
                            if (song != null) {
                                Text("Custom motion art · ${song.title}")
                                OutlinedTextField(value = motionUrl, onValueChange = { motionUrl = it.take(2000) }, label = { Text("Direct HTTPS .mp4, .webm or .m3u8 URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { runCatching { DesktopMixSettings.setMotion(song.videoId, motionUrl) }.onSuccess { note = "Saved. Switch tracks and reopen the player to refresh." }.onFailure { note = it.message } }) { Text("Save motion art") }
                                    TextButton(onClick = { DesktopMixSettings.setMotion(song.videoId, ""); motionUrl = ""; note = "Automatic motion art restored" }) { Text("Restore automatic") }
                                }
                            } else Text("Play a track to assign custom motion artwork.", color = DesktopSecondary)
                        }
                        3 -> {
                            StudioHeading("Hear the source honestly", "A lossless source is required for lossless playback. Output precision preserves processing headroom; it cannot restore missing detail.")
                            StudioLabel("Source quality")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("HIGH" to "High", "LOSSLESS" to "Lossless when available").forEach { (value, label) -> FilterChip(selected = quality == value, onClick = { onQuality(value) }, label = { Text(label) }) }
                            }
                            StudioLabel("Output processing precision")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("PCM_16" to "16-bit PCM", "FLOAT_32" to "32-bit float").forEach { (value, label) -> FilterChip(selected = precision == value, onClick = { onPrecision(value) }, label = { Text(label) }) }
                            }
                            StudioToggle("Level matching", "Slow RMS matching for more consistent volume; not a LUFS measurement", profile.levelMatch) { apply(profile.copy(levelMatch = it)) }
                            StudioSlider("Target level · ${profile.targetDb.roundToInt()} dBFS RMS", profile.targetDb, -24f..-12f, 11) { apply(profile.copy(targetDb = it)) }
                            Text("A stereo-linked peak limiter protects the final mixed output. This does not make the output bit-perfect.", color = DesktopSecondary)
                            StudioToggle("Stereo widening", "A spatial effect for stereo playback; not Dolby Atmos decoding", spatial, onSpatial)
                            StudioToggle("Skip long silences", "Trim long quiet sections between audio", skipSilence, onSkipSilence)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onEqualizer) { Text("Open equalizer") }
                                Button(onClick = { apply(DesktopMixProfile.presets.last()); onQuality("LOSSLESS"); onPrecision("FLOAT_32"); onSpatial(false); onSkipSilence(false); note = "Fidelity preset applied. Source and device capabilities still determine quality." }) { Text("Fidelity preset") }
                            }
                        }
                    }
                }
                note?.let { Text(it, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), color = Color(0xFFAFC9FF), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable private fun StudioLabel(text: String) { Text(text, style = MaterialTheme.typography.labelLarge, color = DesktopSecondary) }
@Composable private fun StudioHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall) }
}
@Composable private fun StudioToggle(title: String, subtitle: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = value, onCheckedChange = change)
    }
}
@Composable private fun StudioSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, change: (Float) -> Unit) {
    Column { StudioLabel(title); Slider(value = value.coerceIn(range), onValueChange = change, valueRange = range, steps = steps) }
}
