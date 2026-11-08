package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShortcutsTest {

    private fun sampleTrack(id: String, title: String) =
        Track(id = id, title = title, artist = "Artist", artworkUrl = null)

    private fun samplePlaylist(id: String, name: String) =
        PlaylistCard(id = id, name = name, songCount = 5, artworkUrl = null)

    @Test
    fun dynamicShortcutsCombinePlaylistsAndSongsIntoFullPairs() {
        val playlists = listOf(samplePlaylist("p1", "Mee"))
        val recents = listOf(
            sampleTrack("t1", "Love Story"),
            sampleTrack("t2", "Army Dreamers"),
            sampleTrack("t3", "Song 3"),
        )

        val shortcuts = buildHomeDynamicShortcuts(playlists, recents)

        // Must be an even count (complete pairs)
        assertEquals(0, shortcuts.size % 2)
        assertEquals(4, shortcuts.size)

        // First item is playlist Mee, second item fills the same row with Love Story!
        assertTrue(shortcuts[0] is HomeShortcutItem.Playlist)
        assertEquals("Mee", (shortcuts[0] as HomeShortcutItem.Playlist).card.name)

        assertTrue(shortcuts[1] is HomeShortcutItem.Song)
        assertEquals("Love Story", (shortcuts[1] as HomeShortcutItem.Song).track.title)

        // Second row contains Army Dreamers and Song 3
        assertTrue(shortcuts[2] is HomeShortcutItem.Song)
        assertEquals("Army Dreamers", (shortcuts[2] as HomeShortcutItem.Song).track.title)

        assertTrue(shortcuts[3] is HomeShortcutItem.Song)
        assertEquals("Song 3", (shortcuts[3] as HomeShortcutItem.Song).track.title)
    }

    @Test
    fun dynamicShortcutsNeverProducesOddCount() {
        // Only 1 playlist and 0 songs -> should be 0 dynamic items (or multiple of 2)
        val single = buildHomeDynamicShortcuts(listOf(samplePlaylist("p1", "Solo")), emptyList())
        assertEquals(0, single.size % 2)
    }

    @Test
    fun onRepeatActiveOverridesChildSongShortcut() {
        val playlists = listOf(samplePlaylist("p1", "Mee"))
        val track2 = sampleTrack("t2", "Army Dreamers")
        val recents = listOf(
            sampleTrack("t1", "Love Story"),
            track2,
        )
        val shortcuts = buildHomeDynamicShortcuts(playlists, recents)

        // When listening to On Repeat and Army Dreamers is playing:
        val activeKey = resolveActiveHomeShortcutKey(
            activeGroupName = "On Repeat",
            activeTrackUid = track2.uid,
            dynamicShortcuts = shortcuts,
        )

        // Only "on-repeat" should be active, NOT the song shortcut!
        assertEquals("on-repeat", activeKey)
    }

    @Test
    fun likedSongsActiveOverridesChildSongShortcut() {
        val track = sampleTrack("t1", "Love Story")
        val shortcuts = listOf(HomeShortcutItem.Song(track))

        val activeKey = resolveActiveHomeShortcutKey(
            activeGroupName = "Liked Songs",
            activeTrackUid = track.uid,
            dynamicShortcuts = shortcuts,
        )

        assertEquals("liked-songs", activeKey)
    }

    @Test
    fun playlistActiveOverridesChildSongShortcut() {
        val p1 = samplePlaylist("p1", "Mee")
        val track = sampleTrack("t1", "Love Story")
        val shortcuts = listOf(
            HomeShortcutItem.Playlist(p1),
            HomeShortcutItem.Song(track),
        )

        val activeKey = resolveActiveHomeShortcutKey(
            activeGroupName = "Mee",
            activeTrackUid = track.uid,
            dynamicShortcuts = shortcuts,
        )

        assertEquals("playlist:p1", activeKey)
    }

    @Test
    fun individualSongActiveWhenNoParentCollectionMatches() {
        val p1 = samplePlaylist("p1", "Mee")
        val track = sampleTrack("t1", "Love Story")
        val shortcuts = listOf(
            HomeShortcutItem.Playlist(p1),
            HomeShortcutItem.Song(track),
        )

        // User tapped the song directly or played without a shortcut collection
        val activeKey = resolveActiveHomeShortcutKey(
            activeGroupName = "Your music",
            activeTrackUid = track.uid,
            dynamicShortcuts = shortcuts,
        )

        assertEquals("track:${track.uid}", activeKey)
    }

    @Test
    fun noShortcutActiveWhenUnpinnedPlaylistPlays() {
        val p1 = samplePlaylist("p1", "Mee")
        val track = sampleTrack("t1", "Love Story")
        val shortcuts = listOf(
            HomeShortcutItem.Playlist(p1),
            HomeShortcutItem.Song(track),
        )

        // Playing a playlist that is not in shortcuts
        val activeKey = resolveActiveHomeShortcutKey(
            activeGroupName = "Some Other Playlist",
            activeTrackUid = "other-track-uid",
            dynamicShortcuts = shortcuts,
        )

        assertNull(activeKey)
    }
}
