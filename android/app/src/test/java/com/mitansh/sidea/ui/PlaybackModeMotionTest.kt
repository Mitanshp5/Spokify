package com.mitansh.sidea.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModeMotionTest {
    @Test
    fun selectionChangeAnimatesIconGeometry() {
        assertTrue(PlaybackModeMotion.shouldAnimate(
            PlaybackModeKey(false, null), PlaybackModeKey(true, null), true))
    }

    @Test
    fun deselectionAlsoAnimates() {
        assertTrue(PlaybackModeMotion.shouldAnimate(
            PlaybackModeKey(true, null), PlaybackModeKey(false, null), true))
    }

    @Test
    fun firstCompositionNeverAnimatesEvenWhenAlreadySelected() {
        for (mode in listOf(PlaybackModeKey(false, null), PlaybackModeKey(true, null), PlaybackModeKey(true, "1"))) {
            assertFalse(PlaybackModeMotion.shouldAnimate(null, mode, true))
        }
    }

    @Test
    fun unrelatedRecompositionDoesNotReplayPulse() {
        val mode = PlaybackModeKey(true, "1")
        assertFalse(PlaybackModeMotion.shouldAnimate(mode, mode.copy(), true))
    }

    @Test
    fun repeatBadgeChangesPulseWithoutSelectionChange() {
        val all = PlaybackModeKey(true, null)
        val one = PlaybackModeKey(true, "1")
        assertTrue(PlaybackModeMotion.shouldAnimate(all, one, true))
        assertTrue(PlaybackModeMotion.shouldAnimate(one, all, true))
    }

    @Test
    fun reducedMotionSuppressesChangesAndDoesNotReplayWhenReenabled() {
        val off = PlaybackModeKey(false, null)
        val one = PlaybackModeKey(true, "1")
        assertFalse(PlaybackModeMotion.shouldAnimate(off, one, false))
        assertFalse(PlaybackModeMotion.shouldAnimate(one, off, false))
        assertFalse(PlaybackModeMotion.shouldAnimate(one, one, true))
    }

    @Test
    fun rapidChangesUseLatestCommittedModeRatherThanLastCompletedAnimation() {
        var previous: PlaybackModeKey? = null
        val modes = listOf(PlaybackModeKey(false, null), PlaybackModeKey(true, null),
            PlaybackModeKey(false, null), PlaybackModeKey(true, "1"))
        modes.forEachIndexed { index, next ->
            assertEquals(index > 0, PlaybackModeMotion.shouldAnimate(previous, next, true))
            // Same order as the composable: update before the cancellable animation.
            previous = next
        }
        assertFalse(PlaybackModeMotion.shouldAnimate(previous, modes.last(), true))
    }

    @Test
    fun pulseHasVisiblePeakAndIdentityRestGeometry() {
        assertEquals(16f, PlaybackModeMotion.rotation(1f), .001f)
        assertEquals(1.12f, PlaybackModeMotion.scale(1f), .001f)
        assertEquals(0f, PlaybackModeMotion.rotation(0f), 0f)
        assertEquals(1f, PlaybackModeMotion.scale(0f), 0f)
        assertEquals(300, PlaybackModeMotion.riseMs + PlaybackModeMotion.settleMs)
    }
}
