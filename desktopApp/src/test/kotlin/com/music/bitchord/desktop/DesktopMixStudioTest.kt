package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

class DesktopMixStudioTest {
    private fun track(id: String, artist: String) = Song(videoId = id, title = id, artist = artist, thumbnailUrl = null, fromAutoplay = false)
    private fun analysis(id: String, bpm: Double, key: String = "C major") =
        TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = id, bpm = bpm, key = key)

    @Test fun fadesPreserveEndpointsAndStayFinite() {
        MixCurve.entries.forEach { curve ->
            assertEquals(1f to 0f, mixGains(curve, -1f))
            assertEquals(0f to 1f, mixGains(curve, 2f))
            assertEquals(1f to 0f, mixGains(curve, Float.NaN))
            for (step in 0..100) {
                val (out, into) = mixGains(curve, step / 100f)
                assertTrue(out.isFinite() && into.isFinite() && out >= 0 && into >= 0)
            }
        }
    }
    @Test fun equalPowerAndSoftFadesKeepUnitPower() {
        listOf(MixCurve.EQUAL_POWER, MixCurve.SOFT).forEach { curve ->
            for (step in 0..100) {
                val (out, into) = mixGains(curve, step / 100f)
                assertEquals(1f, out * out + into * into, 0.00001f)
            }
        }
        assertEquals(0.5f to 0.5f, mixGains(MixCurve.LINEAR, 0.5f))
    }
    @Test fun profilesRejectNonfiniteAndOutOfRangeValues() {
        val safe = DesktopMixProfile(seconds = 500, phraseBars = 7, fx = Float.NaN, targetDb = Float.POSITIVE_INFINITY).sanitized()
        assertEquals(12, safe.seconds); assertEquals(0, safe.phraseBars)
        assertEquals(0f, safe.fx); assertEquals(-18f, safe.targetDb)
        assertEquals(0, DesktopMixProfile(seconds = -5).sanitized().seconds)
    }
    @Test fun profileAndTransitionRoundTripWithoutLosingControls() {
        DesktopMixProfile.presets.forEach { assertEquals(it, Json.decodeFromString<DesktopMixProfile>(Json.encodeToString(it))) }
        val mix = DesktopPairMix(11, MixCurve.SOFT)
        assertEquals(mix, Json.decodeFromString<DesktopPairMix>(Json.encodeToString(mix)))
    }
    @Test fun motionLinksRequireHttpsAndVideoPaths() {
        assertTrue(validMotionUrl("https://cdn.example/cover.mp4?token=demo"))
        assertTrue(validMotionUrl("https://cdn.example/cover.M3U8"))
        listOf("file:///C:/secrets.mp4", "http://example.com/art.mp4", "javascript:alert(1)",
            "https://user:password@example.com/a.mp4", "https://example.com/page", "not a URL").forEach { assertFalse(validMotionUrl(it), it) }
    }
    @Test fun djKeepsEveryDistinctTrackAndAlternatesArtistsWithoutInventingAnalysis() {
        val a = track("a", "Artist A"); val b = track("b", "Artist A"); val c = track("c", "Artist B")
        val result = DesktopDj.sequence(listOf(a, b, c, a), emptyMap(), DjArc.SMOOTH)
        assertEquals(listOf(a, c, b), result)
        assertEquals(emptyList(), DesktopDj.sequence(emptyList(), emptyMap(), DjArc.BUILD))
    }
    @Test fun djPrefersCompatibleMeasuredTempoOverLargeJump() {
        val a = track("a", "A"); val b = track("b", "B"); val c = track("c", "C")
        val measured = mapOf("a" to analysis("a", 120.0), "b" to analysis("b", 86.0), "c" to analysis("c", 122.0))
        assertEquals(listOf(a, c, b), DesktopDj.sequence(listOf(a, b, c), measured, DjArc.SMOOTH))
    }
    @Test fun djRecognizesHalfTimeTempoAsCompatible() {
        val a = track("a", "A"); val b = track("b", "B"); val c = track("c", "C")
        val measured = mapOf("a" to analysis("a", 140.0), "b" to analysis("b", 110.0), "c" to analysis("c", 70.0))
        assertEquals(c, DesktopDj.sequence(listOf(a, b, c), measured, DjArc.SMOOTH)[1])
    }
    @Test fun energyDirectionsChooseOppositeTempoDirectionsWhenOtherwiseEqual() {
        val a = track("a", "A"); val low = track("low", "B"); val high = track("high", "C")
        val measured = mapOf("a" to analysis("a", 120.0), "low" to analysis("low", 118.0), "high" to analysis("high", 122.0))
        assertEquals(high, DesktopDj.sequence(listOf(a, low, high), measured, DjArc.BUILD)[1])
        assertEquals(low, DesktopDj.sequence(listOf(a, low, high), measured, DjArc.WIND_DOWN)[1])
    }
    @Test fun finalBusLimiterStopsClippingAndKeepsStereoImage() {
        val pcm = floatArrayOf(4f, 2f, -4f, -2f)
        DesktopLevelMatcher().process(pcm, pcm.size, 2, 48000, false, -18f)
        assertTrue(pcm.all { abs(it) <= 0.9851f })
        assertEquals(2f, pcm[0] / pcm[1], 0.0001f)
        assertEquals(2f, pcm[2] / pcm[3], 0.0001f)
    }
    @Test fun silenceStaysSilentAndInvalidSamplesAreSanitized() {
        val matcher = DesktopLevelMatcher()
        val silence = FloatArray(960)
        repeat(30) { matcher.process(silence, silence.size, 2, 48000, true, -12f) }
        assertTrue(silence.all { it == 0f })
        val invalid = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 0.1f, -0.1f)
        matcher.process(invalid, invalid.size, 2, 48000, true, Float.NaN)
        assertTrue(invalid.all { it.isFinite() })
    }
    @Test fun levelMatchingConvergesWithoutAbruptGainJumps() {
        val matcher = DesktopLevelMatcher()
        var last = 0.8f
        repeat(1200) {
            val pcm = FloatArray(960) { i -> if (i % 4 < 2) 0.8f else -0.8f }
            matcher.process(pcm, pcm.size, 2, 48000, true, -18f)
            assertTrue(abs(abs(pcm[0]) - last) < 0.03f)
            last = abs(pcm.last())
        }
        assertEquals(0.126f, last, 0.005f)
    }
    @Test fun processingRespectsTheValidSampleCount() {
        val pcm = floatArrayOf(2f, 2f, 123f, 123f)
        DesktopLevelMatcher().process(pcm, 2, 2, 48000, false, -18f)
        assertEquals(123f, pcm[2]); assertEquals(123f, pcm[3])
    }
}
