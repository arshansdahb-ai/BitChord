package com.music.bitchord.desktop

import com.music.bitchord.playback.PeakLimiter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Slow RMS level matching, not a LUFS meter. A linked limiter protects the final mixed bus. */
internal class DesktopLevelMatcher {
    private val limiter = PeakLimiter()
    private var rate = 0
    private var gain = 1f
    private var rmsSquared = 0.0
    private var initialized = false

    fun process(samples: FloatArray, count: Int, channels: Int, sampleRate: Int, enabled: Boolean, targetDb: Float) {
        if (channels <= 0 || sampleRate <= 0 || count <= 0) return
        if (rate != sampleRate) { rate = sampleRate; limiter.configure(rate); initialized = false; gain = 1f }
        val end = minOf(count, samples.size)
        val frames = end / channels
        if (frames == 0) return
        var square = 0.0
        for (i in 0 until end) {
            if (!samples[i].isFinite()) samples[i] = 0f
            square += samples[i].toDouble() * samples[i]
        }
        val blockPower = square / end
        // A tiny signal drives the artwork edge light; keep its update on the audio path cheap.
        DesktopArtVisuals.publishLevel(sqrt(blockPower).toFloat())
        val elapsed = frames.toDouble() / sampleRate
        if (!initialized) { rmsSquared = blockPower; initialized = true }
        else rmsSquared += (blockPower - rmsSquared) * (1.0 - exp(-elapsed / 2.0))
        val safeTarget = if (targetDb.isFinite()) targetDb.coerceIn(-24f, -12f) else -18f
        val target = 10.0.pow(safeTarget / 20.0)
        // Silence is never boosted. A quiet master may get up to +6 dB; loud ones may lose up to 18 dB.
        val wanted = if (enabled && rmsSquared > 0.00001) (target / sqrt(rmsSquared)).coerceIn(0.126, 2.0).toFloat() else 1f
        val step = ((wanted - gain) * (1.0 - exp(-elapsed / if (wanted < gain) 0.4 else 3.0))).toFloat() / frames
        var index = 0
        repeat(frames) {
            gain += step
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(samples[index + c] * gain))
            val safeGain = gain * limiter.gainFor(peak)
            for (c in 0 until channels) samples[index + c] *= safeGain
            index += channels
        }
    }
}
