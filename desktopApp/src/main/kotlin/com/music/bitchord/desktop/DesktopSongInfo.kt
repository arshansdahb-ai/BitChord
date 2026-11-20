package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoRepository
import com.music.bitchord.data.compactCount
import com.music.bitchord.data.model.Song

/** The 1.8.1 metadata lookup, shared with Android and requested only on opening. */
@Composable
internal fun DesktopSongInfoDialog(song: Song, onDismiss: () -> Unit) {
    var info by remember(song.videoId) { mutableStateOf<SongInfo?>(null) }
    LaunchedEffect(song.videoId) { info = SongInfoRepository.load(song) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song info") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoField("Title", song.title)
                InfoField("Artist", song.artist)
                val loaded = info
                if (loaded == null) {
                    CircularProgressIndicator()
                    Text("Loading release details and statistics…")
                } else {
                    InfoField("Album", loaded.album)
                    InfoField("Release date", loaded.releaseDate ?: loaded.releaseYear)
                    InfoField("Views", loaded.views?.let(::compactCount))
                    InfoField("Likes", loaded.likes?.let(::compactCount))
                    InfoField("Comments", loaded.comments?.let(::compactCount))
                    if (loaded.failed) Text("Some details are unavailable. You can close and reopen to retry.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoField(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
