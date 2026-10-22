package com.mitansh.sidea.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormatTest {
    @Test fun formatsPlaybackTimeAsMinutesAndSeconds() {
        assertEquals("1:05", formatPlaybackTime(65_999))
        assertEquals("0:00", formatPlaybackTime(-1))
    }
}
