package com.mitansh.sidea.data.library

/** A song is saved when it belongs to any playlist, including Liked Songs. */
data class PlaylistMembership(val playlistId: String, val uid: String)
