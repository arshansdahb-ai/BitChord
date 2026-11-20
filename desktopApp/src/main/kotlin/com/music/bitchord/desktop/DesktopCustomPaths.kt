package com.music.bitchord.desktop

import java.nio.file.Path

/** This personal build keeps its files and preferences separate from official BitChord. */
internal object DesktopCustomPaths {
    const val preferencesNode = "com.music.bitchord.desktop.custom"
    val root: Path by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        Path.of(base, "BitChordCustom")
    }
}
