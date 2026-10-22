package com.mitansh.sidea.playback.feedback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFeedbackPolicyTest {

    @Test
    fun `explicit forward change under fifteen seconds is negative feedback`() {
        assertTrue(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.USER_CHANGE, 1_000, JumpDirection.FORWARD))
        assertTrue(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.USER_CHANGE, 14_999, JumpDirection.FORWARD))
    }

    @Test
    fun `qualified listening before an explicit change is not negative feedback`() {
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.USER_CHANGE, 15_000, JumpDirection.FORWARD))
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.USER_CHANGE, 300_000, JumpDirection.FORWARD))
    }

    @Test
    fun `natural auto transition repeat and other changes never count`() {
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.AUTO, 1_000, JumpDirection.FORWARD))
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.REPEAT, 1_000, JumpDirection.FORWARD))
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.OTHER, 1_000, JumpDirection.FORWARD))
    }

    @Test
    fun `no real listening yet is not treated as a skip`() {
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.USER_CHANGE, 0, JumpDirection.FORWARD))
    }

    @Test
    fun `media3 transition reasons map to the exact policy the listener uses`() {
        assertTrue(PlaybackFeedbackPolicy.changeFor(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) == TrackChange.USER_CHANGE)
        assertTrue(PlaybackFeedbackPolicy.changeFor(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) == TrackChange.AUTO)
        assertTrue(PlaybackFeedbackPolicy.changeFor(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) == TrackChange.REPEAT)
        assertTrue(PlaybackFeedbackPolicy.changeFor(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) == TrackChange.OTHER)
    }

    @Test
    fun `going back to an earlier song is not a skip`() {
        assertTrue(PlaybackFeedbackPolicy.directionFor(5, 2) == JumpDirection.BACKWARD)
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.USER_CHANGE, 1_000, JumpDirection.BACKWARD))
    }

    @Test
    fun `a backward move never counts even when the previous song was barely heard`() {
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.USER_CHANGE, 200, JumpDirection.BACKWARD))
        assertFalse(PlaybackFeedbackPolicy.isNegativeFeedback(TrackChange.USER_CHANGE, 200, JumpDirection.NONE))
    }

    @Test
    fun `queue direction is derived from the item index`() {
        assertTrue(PlaybackFeedbackPolicy.directionFor(0, 1) == JumpDirection.FORWARD)
        assertTrue(PlaybackFeedbackPolicy.directionFor(1, 0) == JumpDirection.BACKWARD)
        assertTrue(PlaybackFeedbackPolicy.directionFor(3, 3) == JumpDirection.NONE)
    }
}
