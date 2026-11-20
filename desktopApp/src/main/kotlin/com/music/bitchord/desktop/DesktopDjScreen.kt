package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.*

private val DjGreen = Color(0xFF1ED760)
private val DjMuted = Color(0xFFB3B3B3)

@Composable
internal fun DesktopDjScreen(
    history: List<Song>, current: Song?, playing: Boolean, contentPadding: PaddingValues,
    onPlay: (List<Song>) -> Unit, onPause: () -> Unit, onResume: () -> Unit, onNext: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val turns by DesktopTalkingDj.turns.collectAsState()
    val busy by DesktopTalkingDj.busy.collectAsState()
    val status by DesktopTalkingDj.status.collectAsState()
    val voice by DesktopTalkingDj.voiceEnabled.collectAsState()
    val active by DesktopTalkingDj.active.collectAsState()
    val speaking by DesktopDjVoice.speaking.collectAsState()
    val listening by DesktopDjVoice.listening.collectAsState()
    var text by remember { mutableStateOf("") }
    var requests by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var voiceJob by remember { mutableStateOf<Job?>(null) }
    var micJob by remember { mutableStateOf<Job?>(null) }
    val list = rememberLazyListState()
    val artists = remember(history) { DesktopTalkingDj.artists(history) }
    val prompts = remember(history) { DesktopTalkingDj.suggestions(history) }
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.lastIndex) }
    DisposableEffect(Unit) { onDispose { voiceJob?.cancel(); micJob?.cancel(); DesktopDjVoice.stop() } }

    fun submit(request: String) {
        if (request.isBlank() || DesktopTalkingDj.busy.value) return
        voiceJob?.cancel()
        DesktopDjVoice.stop()
        val clean = request.trim().take(300)
        text = ""
        scope.launch {
            DesktopTalkingDj.busy.value = true
            DesktopTalkingDj.add("you", clean)
            DesktopTalkingDj.status.value = "Picking your music..."
            try {
                val plan = DesktopTalkingDj.plan(clean, history, current)
                var spoken = plan.spoken
                when (plan.action) {
                    "pause" -> onPause()
                    "resume" -> onResume()
                    "next" -> onNext()
                    "play" -> {
                        DesktopTalkingDj.status.value = "Putting your next set together..."
                        val songs = DesktopTalkingDj.songs(plan, history)
                        DesktopTalkingDj.beginSet(songs)
                        spoken = DesktopTalkingDj.setIntroduction(plan, songs.first())
                        onPlay(songs)
                    }
                }
                DesktopTalkingDj.add("dj", spoken)
                DesktopTalkingDj.status.value = "Your DJ is ready"
                if (DesktopTalkingDj.voiceEnabled.value) voiceJob = scope.launch {
                    try { DesktopDjVoice.speak(spoken) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { DesktopTalkingDj.status.value = failure.message ?: "Voice is unavailable. You can keep typing." }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                DesktopTalkingDj.status.value = failure.message ?: "Something went wrong. Try again."
                DesktopTalkingDj.add("dj", DesktopTalkingDj.status.value)
            } finally { DesktopTalkingDj.busy.value = false }
        }
    }

    fun listen() {
        if (listening) { micJob?.cancel(); DesktopDjVoice.stop(); return }
        voiceJob?.cancel()
        DesktopDjVoice.stop()
        micJob = scope.launch {
            try { val heard = DesktopDjVoice.listen(); if (heard.isNotBlank()) submit(heard) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { DesktopTalkingDj.status.value = failure.message ?: "Microphone unavailable. Type your request." }
        }
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = DjGreen, onPrimary = Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(contentPadding).background(Color(0xFF121212)).padding(16.dp), contentAlignment = Alignment.Center) {
            if (!requests) {
                val side = (maxHeight - 16.dp).coerceIn(180.dp, 340.dp)
                val artwork: @Composable () -> Unit = {
                    Image(painterResource("dj/spotify-dj-reference.png"), "DJ artwork", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(side).clip(RoundedCornerShape(8.dp)))
                }
                val controls: @Composable ColumnScope.() -> Unit = {
                    Text("DJ", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(if (active) "Your own personal DJ" else "Music picked for you. Commentary between sets.", color = DjMuted, modifier = Modifier.padding(top = 4.dp))
                    if (artists.isNotEmpty()) Text("Featuring ${artists.take(3).joinToString(", ")}", color = DjMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledIconButton(onClick = { if (!active) submit("Start my DJ") else if (playing) onPause() else onResume() }, enabled = !busy,
                            modifier = Modifier.size(62.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = DjGreen, contentColor = Color.Black)) {
                            Icon(if (active && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (active && playing) "Pause DJ" else "Play DJ", modifier = Modifier.size(36.dp))
                        }
                        OutlinedButton(onClick = { submit("Switch up the vibe") }, enabled = !busy, shape = CircleShape) {
                            Icon(Icons.Rounded.GraphicEq, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Switch up the vibe", color = Color.White)
                        }
                    }
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { requests = true }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282828), contentColor = Color.White)) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = DjGreen, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Ask DJ")
                        }
                        TextButton(onClick = { DesktopTalkingDj.setVoice(!voice); if (voice) { voiceJob?.cancel(); DesktopDjVoice.stop() } }) {
                            Icon(if (voice) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (voice) "Turn DJ voice off" else "Turn DJ voice on", tint = DjMuted)
                        }
                        if (active) TextButton(onClick = { DesktopTalkingDj.active.value = false; voiceJob?.cancel(); DesktopDjVoice.stop() }) { Text("End DJ", color = DjMuted) }
                    }
                    Text(when { busy -> status; speaking -> "DJ is talking..."; else -> if (active && current != null) "${current.title} - ${current.artist}" else "Press play and let DJ take it from here." },
                        color = DjMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
                if (maxWidth >= 620.dp) Row(Modifier.widthIn(max = 950.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    artwork(); Column(Modifier.weight(1f), content = controls)
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    artwork(); Column(Modifier.fillMaxWidth().padding(top = 16.dp), content = controls)
                }
            } else {
                Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { requests = false; micJob?.cancel(); DesktopDjVoice.stop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to DJ", tint = Color.White) }
                        Text("DJ", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Suggestions" else "Conversation", color = DjMuted) }
                    }
                    if (!showHistory) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                            Text("What do you want to hear?", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            prompts.forEach { prompt ->
                                TextButton(onClick = { submit(prompt); showHistory = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 7.dp, horizontal = 0.dp)) {
                                    Text(prompt, color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                    Icon(Icons.AutoMirrored.Rounded.Send, null, tint = DjMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(turns.size) { index ->
                            val turn = turns[index]
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (turn.role == "you") Arrangement.End else Arrangement.Start) {
                                Surface(shape = RoundedCornerShape(18.dp), color = if (turn.role == "you") Color(0xFF2A2A2A) else Color.Transparent, modifier = Modifier.widthIn(max = 590.dp)) {
                                    Text(turn.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = Color.White)
                                }
                            }
                        }
                    }
                    Text(when { listening -> "Listening..."; speaking -> "DJ is talking..."; else -> status }, color = DjMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = text, onValueChange = { text = it.take(300) }, modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                                submit(text); showHistory = true; true
                            } else false
                        }, placeholder = { Text("Ask DJ for music", color = DjMuted) }, shape = RoundedCornerShape(28.dp), maxLines = 2,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit(text); showHistory = true }))
                        FilledIconButton(enabled = !busy, onClick = { listen(); showHistory = true }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = DjGreen, contentColor = Color.Black)) {
                            Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (listening) "Stop listening" else "Talk to DJ")
                        }
                        IconButton(enabled = text.isNotBlank() && !busy && !listening, onClick = { submit(text); showHistory = true }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send request", tint = if (text.isNotBlank() && !busy) DjGreen else DjMuted) }
                    }
                    if (speaking) TextButton(onClick = { voiceJob?.cancel(); DesktopDjVoice.stop() }, modifier = Modifier.align(Alignment.End)) { Text("Stop voice") }
                }
            }
        }
    }
}
