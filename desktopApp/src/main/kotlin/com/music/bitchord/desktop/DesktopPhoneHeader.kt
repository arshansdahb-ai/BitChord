package com.music.bitchord.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** iPhone Music's large title and account control, with native Windows window furniture. */
@Composable
internal fun DesktopPhoneHeader(
    title: String, isPlaying: Boolean, currentTrack: String?, canGoBack: Boolean, onBack: () -> Unit, onAccounts: () -> Unit,
    showClock: Boolean = true,
    search: Boolean, query: String, onQuery: (String) -> Unit, onSearch: () -> Unit,
    onStudio: () -> Unit, onSettings: () -> Unit, onHistory: () -> Unit,
    onDownloads: () -> Unit, onLocal: () -> Unit, onPhoneRemote: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            clock = LocalTime.now()
            delay(15_000)
        }
    }
    val titleBar by DesktopTitleBarSetting.enabled.collectAsState()
    Column(Modifier.fillMaxWidth().desktopWindowGlass(DesktopChromeEdge.BOTTOM)) {
        if (DesktopPlatform.drawsOwnWindowFrame && !titleBar) DesktopTitleBarDragArea(Modifier.fillMaxWidth().height(30.dp)) {
            Box(Modifier.fillMaxSize().padding(start = 10.dp), contentAlignment = Alignment.CenterStart) { DesktopWindowButtons() }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 24.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (showClock) Column(
                modifier = Modifier.widthIn(min = 112.dp, max = 190.dp).padding(end = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    if (isPlaying) currentTrack.orEmpty() else "BITCHORD",
                    color = if (isPlaying) Color.White.copy(alpha = 0.64f) else DesktopSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    clock.format(DateTimeFormatter.ofPattern("h:mm")),
                    color = if (isPlaying) DesktopAccent else Color.White.copy(alpha = 0.82f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    fontSize = 34.sp,
                    lineHeight = 36.sp,
                    letterSpacing = (-1.8).sp,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("Phone player & Jam" to onPhoneRemote, "Mix Studio" to onStudio, "History" to onHistory, "Downloads" to onDownloads,
                        "Local Music" to onLocal, "Settings" to onSettings).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                    }
                }
            }
            Surface(shape = CircleShape, color = DesktopAccent.copy(alpha = 0.14f)) {
                IconButton(onClick = onAccounts) { Icon(Icons.Rounded.Person, "Account", tint = DesktopAccent) }
            }
        }
        if (search) OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Artists, songs, albums and more") }, modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }))
    }
}
