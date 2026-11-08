package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track

sealed interface HomeShortcutItem {
    data class Playlist(val card: PlaylistCard) : HomeShortcutItem
    data class Song(val track: Track) : HomeShortcutItem
}

internal fun buildHomeDynamicShortcuts(
    playlists: List<PlaylistCard>,
    recentlyPlayed: List<Track>,
    targetCount: Int = if (playlists.size >= 4) 6 else 4,
): List<HomeShortcutItem> {
    val items = buildList {
        playlists.take(4).forEach { add(HomeShortcutItem.Playlist(it)) }
        val needed = targetCount - size
        if (needed > 0) {
            val existingSongUids = filterIsInstance<HomeShortcutItem.Song>().mapTo(hashSetOf()) { it.track.uid }
            val candidates = recentlyPlayed.filter { it.uid !in existingSongUids }
            addAll(candidates.take(needed).map { HomeShortcutItem.Song(it) })
        }
    }
    val fullRowsCount = (items.size / 2) * 2
    return items.take(fullRowsCount)
}

internal fun resolveActiveHomeShortcutKey(
    activeGroupName: String?,
    activeTrackUid: String?,
    dynamicShortcuts: List<HomeShortcutItem>,
): String? {
    if (activeGroupName == "On Repeat") return "on-repeat"
    if (activeGroupName == "Liked Songs") return "liked-songs"

    val activePlaylist = dynamicShortcuts.filterIsInstance<HomeShortcutItem.Playlist>()
        .firstOrNull { it.card.name == activeGroupName }
    if (activePlaylist != null) return "playlist:${activePlaylist.card.id}"

    val activeSong = dynamicShortcuts.filterIsInstance<HomeShortcutItem.Song>()
        .firstOrNull { it.track.uid == activeTrackUid }
    if (activeSong != null) return "track:${activeSong.track.uid}"

    return null
}
