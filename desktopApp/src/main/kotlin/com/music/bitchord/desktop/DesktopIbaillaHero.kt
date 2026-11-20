package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Desktop listening stage based on the reference's history rail and central cover flow. */
@Composable
internal fun DesktopIbaillaHero(
    songs: List<Song>,
    currentSong: Song?,
    playing: Boolean,
    onPlay: (Song) -> Unit,
) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = LocalTime.now()
            today = LocalDate.now()
            delay(15_000)
        }
    }
    val items = remember(songs, currentSong) {
        (listOfNotNull(currentSong) + songs).distinctBy(Song::videoId).take(9)
    }
    val selected = items.indexOfFirst { it.videoId == currentSong?.videoId }.coerceAtLeast(0)
    val stage = items.drop((selected - 2).coerceAtLeast(0)).take(5)
    Column(Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 405.dp).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(230.dp).fillMaxHeight()) {
                Text("CONTINUE PLAYING", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.35.sp)
                Spacer(Modifier.height(12.dp))
                items.take(5).forEachIndexed { index, song ->
                    val active = song.videoId == currentSong?.videoId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                            .background(if (active) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onPlay(song) }.padding(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(song, Modifier.size(42.dp), 9.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(song.title, color = Color.White, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (active && playing) Text("♫", color = Color(0xFFFFB180), fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")).uppercase(), color = Color(0xFFFFB180),
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Text(now.format(DateTimeFormatter.ofPattern("h:mm")), color = Color(0xFFF2D1BC),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.ExtraLight,
                    fontSize = 54.sp, lineHeight = 58.sp, letterSpacing = (-3).sp)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().height(176.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    stage.forEach { song ->
                        val isCurrent = song.videoId == currentSong?.videoId
                        val distance = kotlin.math.abs(items.indexOf(song) - selected)
                        val size = when { isCurrent -> 166.dp; distance == 1 -> 128.dp; else -> 98.dp }
                        Box(Modifier.padding(horizontal = 4.dp).size(size).offset(y = if (isCurrent) 0.dp else 10.dp)
                            .clip(RoundedCornerShape(if (isCurrent) 20.dp else 15.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF37323A), Color(0xFF17151A))))
                            .clickable { onPlay(song) }) {
                            Cover(song, Modifier.fillMaxSize(), if (isCurrent) 20.dp else 15.dp)
                            Box(Modifier.fillMaxWidth().height(64.dp).align(Alignment.BottomCenter).background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
                            Column(Modifier.align(Alignment.BottomStart).padding(9.dp)) {
                                Text(song.title, color = Color.White, style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (currentSong != null) {
                    Spacer(Modifier.height(9.dp))
                    Text(if (playing) "NOW PLAYING" else "READY TO PLAY", color = Color.White.copy(alpha = 0.5f),
                        fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp)
                }
            }
        }
    }
}

@Composable
private fun Cover(song: Song, modifier: Modifier, radius: androidx.compose.ui.unit.Dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Brush.linearGradient(listOf(Color(0xFF37323A), Color(0xFF17151A))))) {
        if (!song.thumbnailUrl.isNullOrBlank()) AsyncImage(model = song.thumbnailUrl, contentDescription = "${song.title} cover", modifier = Modifier.fillMaxSize())
        else Text(song.title.take(1), Modifier.align(Alignment.Center), color = Color.White, fontSize = 32.sp)
    }
}
