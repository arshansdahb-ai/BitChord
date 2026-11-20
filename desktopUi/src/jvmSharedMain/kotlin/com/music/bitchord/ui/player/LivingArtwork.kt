package com.music.bitchord.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** A subtle, bounded camera drift for a still sleeve when no motion clip has rendered. */
@Composable
internal fun Modifier.livingArtwork(playing: Boolean): Modifier {
    val reduceMotion by PlayerSettings.reduceAnimation.collectAsState()
    val animated by PlayerSettings.animatedCanvas.collectAsState()
    if (!playing || reduceMotion || !animated) return this
    val animation = rememberInfiniteTransition(label = "living artwork")
    val drift by animation.animateFloat(0f, 1f,
        infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "cover drift")
    return graphicsLayer {
        scaleX = 1.05f + drift * 0.025f
        scaleY = scaleX
        translationX = (drift - 0.5f) * size.width * 0.025f
        translationY = (0.5f - drift) * size.height * 0.012f
    }
}
