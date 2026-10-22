package com.mitansh.sidea.data.library

import com.mitansh.sidea.core.Track

/** Rebuilds a playable [Track] from a persisted row; the artwork URL is optional metadata. */
fun HistoryEntry.toTrack(): Track = Track(
    id = trackId,
    title = title,
    artist = artist,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    source = source,
)

fun FavoriteEntry.toTrack(): Track = Track(
    id = trackId,
    title = title,
    artist = artist,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    source = source,
)

fun Track.toHistoryEntry(playedAtEpochMs: Long, playCount: Int): HistoryEntry = HistoryEntry(
    uid = uid,
    trackId = id,
    source = source,
    title = title,
    artist = artist,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    lastPlayedAtEpochMs = playedAtEpochMs,
    playCount = playCount,
)

fun Track.toFavoriteEntry(savedAtEpochMs: Long): FavoriteEntry = FavoriteEntry(
    uid = uid,
    trackId = id,
    source = source,
    title = title,
    artist = artist,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    savedAtEpochMs = savedAtEpochMs,
)
