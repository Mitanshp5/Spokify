package com.mitansh.sidea.data.history

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningSessionTest {

    @Test
    fun `counts contiguous playback progress`() {
        val session = ListeningSession()
        session.startTrack(0)
        session.onPlayingPosition(1_000)
        session.onPlayingPosition(2_000)

        assertEquals(2_000L, session.accumulatedMs)
    }

    @Test
    fun `does not count time spent paused`() {
        val session = ListeningSession()
        session.startTrack(0)
        session.onPlayingPosition(1_000)
        session.onPaused()
        session.onPlayingPosition(60_000)

        assertEquals(1_000L, session.accumulatedMs)
    }

    @Test
    fun `does not count a seek jump as listening`() {
        val session = ListeningSession()
        session.startTrack(0)
        session.onPlayingPosition(1_000)
        session.onSeek(120_000)
        session.onPlayingPosition(121_000)

        assertEquals(2_000L, session.accumulatedMs)
    }

    @Test
    fun `does not count a stalled gap longer than the sampling window`() {
        val session = ListeningSession()
        session.startTrack(0)
        session.onPlayingPosition(9_000)

        assertEquals(0L, session.accumulatedMs)
    }

    @Test
    fun `starts a new track at zero`() {
        val session = ListeningSession()
        session.startTrack(0)
        session.onPlayingPosition(5_000)
        session.startTrack(0)

        assertEquals(0L, session.accumulatedMs)
    }

    @Test
    fun `ignores a backwards position report`() {
        val session = ListeningSession()
        session.startTrack(1_000)
        session.onPlayingPosition(500)

        assertEquals(0L, session.accumulatedMs)
    }
}
