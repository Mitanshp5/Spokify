package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class PlayerDismissPolicyTest {
    @Test fun longDownwardPullDismissesWithoutFling() {
        assertTrue(PlayerDismissPolicy.shouldDismiss(350f, 2400f, 2.625f, 0f))
    }

    @Test fun shortPullReturnsToPlayer() {
        assertFalse(PlayerDismissPolicy.shouldDismiss(90f, 2400f, 2.625f, 0f))
    }

    @Test fun fastDownwardFlickStillRequiresIntentionalMovement() {
        assertTrue(PlayerDismissPolicy.shouldDismiss(90f, 2400f, 2.625f, 3000f))
        assertFalse(PlayerDismissPolicy.shouldDismiss(10f, 2400f, 2.625f, 3000f))
    }

    @Test fun upwardFlingDoesNotDismissShortPull() {
        assertFalse(PlayerDismissPolicy.shouldDismiss(100f, 2400f, 2.625f, -3000f))
    }

    @Test fun smallScreenThresholdUsesAvailableHeight() {
        assertTrue(PlayerDismissPolicy.shouldDismiss(235f, 1280f, 2f, 0f))
        assertFalse(PlayerDismissPolicy.shouldDismiss(200f, 1280f, 2f, 0f))
    }
}
