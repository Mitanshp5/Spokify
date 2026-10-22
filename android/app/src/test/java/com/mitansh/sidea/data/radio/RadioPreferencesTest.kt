package com.mitansh.sidea.data.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPreferencesTest {

    /** The preferences over a plain map, which is what the rules are actually about. */
    private class MapStorage : RadioPreferences.Storage {
        private val values = mutableMapOf<String, String>()
        override fun getBoolean(key: String, fallback: Boolean) = values[key]?.toBoolean() ?: fallback
        override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override fun getString(key: String) = values[key].orEmpty()
        override fun putString(key: String, value: String) { values[key] = value }
        override fun clear() { values.clear() }
    }

    @Test
    fun `autoplay is on by default`() {
        assertTrue(RadioPreferences(MapStorage()).autoplayEnabled)
    }

    @Test
    fun `autoplay can be turned off and on again`() {
        val preferences = RadioPreferences(MapStorage())

        preferences.autoplayEnabled = false
        assertFalse(preferences.autoplayEnabled)

        preferences.autoplayEnabled = true
        assertTrue(preferences.autoplayEnabled)
    }

    @Test
    fun `a hidden song is not suggested again`() {
        val preferences = RadioPreferences(MapStorage())

        preferences.hide("track1")

        assertTrue(preferences.isHidden("track1"))
        assertFalse(preferences.isHidden("track2"))
    }

    @Test
    fun `unhiding brings a song back`() {
        val preferences = RadioPreferences(MapStorage())
        preferences.hide("track1")

        preferences.unhide("track1")

        assertFalse(preferences.isHidden("track1"))
    }

    @Test
    fun `hiding the same song twice does not duplicate it`() {
        val preferences = RadioPreferences(MapStorage())

        preferences.hide("track1")
        preferences.hide("track1")

        assertEquals(1, preferences.hiddenTracks().size)
    }

    @Test
    fun `a blank id is ignored`() {
        val preferences = RadioPreferences(MapStorage())

        preferences.hide("  ")

        assertTrue(preferences.hiddenTracks().isEmpty())
    }

    @Test
    fun `several hidden songs are all kept`() {
        val preferences = RadioPreferences(MapStorage())

        preferences.hide("track1")
        preferences.hide("track2")

        assertEquals(setOf("track1", "track2"), preferences.hiddenTracks())
    }

    @Test
    fun `resetting forgets every preference`() {
        val preferences = RadioPreferences(MapStorage())
        preferences.autoplayEnabled = false
        preferences.hide("track1")

        preferences.reset()

        assertTrue(preferences.autoplayEnabled)
        assertTrue(preferences.hiddenTracks().isEmpty())
    }
}
