package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.*

class DesktopTalkingDjTest {
    private fun song(id: String, artist: String) = Song(videoId = id, title = "Track $id", artist = artist, thumbnailUrl = null, fromAutoplay = false)
    @Test fun exactPlaybackCommandsDoNotNeedAChatModel() {
        assertEquals("pause", DesktopTalkingDj.localCommand("Pause the music!", null)?.action)
        assertEquals("next", DesktopTalkingDj.localCommand("skip this song", null)?.action)
        assertEquals("resume", DesktopTalkingDj.localCommand("resume", null)?.action)
        assertNull(DesktopTalkingDj.localCommand("Pick something more upbeat", null))
        assertNull(DesktopTalkingDj.localCommand("I like the next generation of jazz", null))
    }
    @Test fun currentTrackAnswersOnlyUseKnownMetadata() {
        val result = DesktopTalkingDj.localCommand("what's playing?", song("one", "Actual Artist"))!!
        assertEquals("chat", result.action)
        assertTrue(result.spoken.contains("Track one")); assertTrue(result.spoken.contains("Actual Artist"))
        assertTrue(DesktopTalkingDj.localCommand("who is this", null)!!.spoken.contains("Nothing is playing"))
    }
    @Test fun tasteProfileUsesRecentArtistsAndIgnoresEmptyCredits() {
        val history = listOf(song("1", "A"), song("2", "B"), song("3", "A"), song("4", ""))
        assertEquals(listOf("A", "B"), DesktopTalkingDj.artists(history))
        assertTrue(DesktopTalkingDj.artists(emptyList()).isEmpty())
    }
    @Test fun modelCannotChooseApplicationActions() {
        val plan = DesktopTalkingDj.parsePlan("""{"query":"relaxed jazz piano","spoken":"Let's settle into some piano.","action":"run arbitrary command"}""", "jazz", null)
        assertEquals("play", plan.action)
        assertEquals("relaxed jazz piano", plan.query)
    }
    @Test fun modelUrlsAndQuestionsBecomeSafeSearchText() {
        for (query in listOf("https://example.com/script", "What would you like to hear?", "")) {
            val plan = DesktopTalkingDj.parsePlan("""{"query":"$query","spoken":"Here is a mix."}""", "more jazz", "Artist A")
            assertEquals("more jazz Artist A", plan.query)
        }
    }
    @Test fun malformedOrEmptyRepliesAreNotPresentedAsSuccessfulDjChoices() {
        assertFails { DesktopTalkingDj.parsePlan("not json", "jazz", null) }
        assertFails { DesktopTalkingDj.parsePlan("""{"spoken":"An invented track"}""", "jazz", null) }
    }
    @Test fun longOutputIsBoundedForSpeechAndSearch() {
        val plan = DesktopTalkingDj.parsePlan("""{"query":"${"a".repeat(500)}","spoken":"${"b".repeat(1500)}"}""", "jazz", null)
        assertEquals(160, plan.query.length); assertTrue(plan.spoken.length < 100)
        assertFalse(plan.spoken.contains("b".repeat(20)))
    }
    @Test fun personalizedSessionStartsFromActualHistoryInsteadOfPromptExample() {
        val history = listOf(song("abcdefghijk", "Actual Favorite"), song("lmnopqrstuv", "Other Artist"), song("abcdefghij2", "Actual Favorite"))
        val plan = DesktopTalkingDj.personalStart("Start my DJ", history)!!
        assertEquals("abcdefghijk", plan.seedVideoId)
        assertEquals("Actual Favorite music", plan.query)
        assertTrue(plan.spoken.contains("Actual Favorite"))
        assertFalse(plan.spoken.contains("piano"))
        assertNull(DesktopTalkingDj.personalStart("Start my DJ", emptyList()))
        assertNull(DesktopTalkingDj.personalStart("Some jazz please", history))
    }
    @Test fun changingVibeRotatesActualListeningSeedsWithoutLoadingModel() {
        val history = listOf(song("abcdefghijk", "A"), song("lmnopqrstuv", "B"))
        DesktopTalkingDj.personalStart("Start my DJ", history)
        val changed = DesktopTalkingDj.personalStart("Switch up the vibe", history)!!
        assertEquals("lmnopqrstuv", changed.seedVideoId)
        assertTrue(changed.spoken.contains("B"))
    }
    @Test fun commentaryOccursBetweenSetsAndNeverRepeatsOnRecomposition() {
        try {
            val first = song("0", "First")
            DesktopTalkingDj.beginSet(listOf(first))
            assertNull(DesktopTalkingDj.trackCommentary(first))
            for (i in 1..3) {
                val next = song("$i", "Artist $i")
                assertNull(DesktopTalkingDj.trackCommentary(next))
                assertNull(DesktopTalkingDj.trackCommentary(next))
            }
            val fourth = song("4", "Actual Artist")
            val intro = DesktopTalkingDj.trackCommentary(fourth)!!
            assertTrue(intro.contains("Track 4"))
            assertTrue(intro.contains("Actual Artist"))
            assertNull(DesktopTalkingDj.trackCommentary(fourth))
            DesktopTalkingDj.beginSet(listOf(first))
            assertNull(DesktopTalkingDj.trackCommentary(fourth))
        } finally { DesktopTalkingDj.active.value = false; DesktopTalkingDj.preview.value = emptyList(); DesktopTalkingDj.lastIntroducedId = null }
    }
    @Test fun firstTrackIntroductionUsesTheSelectedTrackMetadata() {
        val intro = DesktopTalkingDj.setIntroduction(DjPlan("play", "All right, let's get into it."), song("real", "Real Artist"))
        assertEquals("All right, let's get into it. First up, Track real, by Real Artist.", intro)
    }
    @Test fun threeSuggestionsAreGroundedInAvailableTaste() {
        val suggestions = DesktopTalkingDj.suggestions(listOf(song("a", "Actual Favorite")))
        assertEquals(3, suggestions.size)
        assertTrue(suggestions.first().contains("Actual Favorite"))
        assertEquals(3, DesktopTalkingDj.suggestions(emptyList()).size)
    }
    @Test fun modelCannotInventSpokenArtistOrTrackFacts() {
        val plan = DesktopTalkingDj.parsePlan("""{"query":"mellow jazz","spoken":"Don Toliver invented jazz. First up, No Pole."}""", "mellow jazz for a quiet evening", "Don Toliver")
        assertEquals("mellow jazz", plan.query)
        assertEquals("Got you. Let's ease into something more laid-back.", plan.spoken)
        assertFalse(DesktopTalkingDj.setIntroduction(plan, song("jazz", "Actual Jazz Artist")).contains("Don Toliver"))
    }
}
