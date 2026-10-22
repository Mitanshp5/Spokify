package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track

internal fun songRowKeys(tracks: List<Track>): List<String> {
    val occurrences = HashMap<String, Int>()
    return tracks.map { track ->
        val occurrence = occurrences.getOrDefault(track.uid, 0)
        occurrences[track.uid] = occurrence + 1
        "${track.uid}:$occurrence"
    }
}
