package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/** Small shared visual signals for the wallpaper and beat-reactive edge light. */
internal object DesktopArtVisuals {
    private val _level = MutableStateFlow(0f)
    private val _accent = MutableStateFlow(Color(0xFFFF4F87))
    val level = _level.asStateFlow()
    val accent = _accent.asStateFlow()

    /** Called by the audio mixer once per output block; no image or UI work runs on that thread. */
    fun publishLevel(value: Float) {
        _level.value = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }

    /** The mesh is already reduced to 32×32, so deriving its tint costs one small pixel pass. */
    fun publishAccent(mesh: ImageBitmap) {
        val pixels = IntArray(mesh.width * mesh.height)
        mesh.readPixels(pixels, 0, mesh.width, mesh.height)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weightSum = 0.0
        pixels.forEach { pixel ->
            val r = (pixel ushr 16 and 0xff) / 255.0
            val g = (pixel ushr 8 and 0xff) / 255.0
            val b = (pixel and 0xff) / 255.0
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            val saturation = if (high == 0.0) 0.0 else (high - low) / high
            val brightness = (r + g + b) / 3.0
            val weight = saturation * (0.35 + brightness).coerceIn(0.35, 1.2)
            if (weight > 0.12) {
                red += r * weight
                green += g * weight
                blue += b * weight
                weightSum += weight
            }
        }
        if (weightSum > 0.0) {
            val accent = Color(
                red = (red / weightSum).toFloat().coerceIn(0.12f, 0.92f),
                green = (green / weightSum).toFloat().coerceIn(0.12f, 0.92f),
                blue = (blue / weightSum).toFloat().coerceIn(0.12f, 0.92f),
                alpha = 1f,
            )
            // Keep the title and icons legible while preserving the artwork's hue.
            _accent.value = Color(
                red = (accent.red * 0.72f + 0.28f).coerceAtMost(1f),
                green = (accent.green * 0.72f + 0.28f).coerceAtMost(1f),
                blue = (accent.blue * 0.72f + 0.28f).coerceAtMost(1f),
            )
        }
    }
}
