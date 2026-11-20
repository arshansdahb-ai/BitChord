package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable internal data class PhoneSetting(
    val key: String, val label: String, val type: String, val value: JsonPrimitive,
    val options: List<String> = emptyList(), val min: Double = 0.0, val max: Double = 1.0, val step: Double = 1.0,
    val section: String = "PC playback",
)
internal data class PhoneLibrarySnapshot(
    val recent: List<Song> = emptyList(), val favorites: List<Song> = emptyList(),
    val downloads: List<Song> = emptyList(), val local: List<Song> = emptyList(),
    val playlists: List<DesktopPlaylist> = emptyList(), val home: List<HomeShelf> = emptyList(),
    val settings: List<PhoneSetting> = emptyList(),
)
