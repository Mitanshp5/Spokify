package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import org.junit.Assert.*
import org.junit.Test

class SongRowKeysTest {
    @Test fun insertionAndReorderingPreserveOtherSongsWhileDuplicatesHaveUniqueKeys() {
        val a = Track("a", "A", "Artist")
        val b = Track("b", "B", "Artist")
        val c = Track("c", "C", "Artist")
        val original = songRowKeys(listOf(a, b, a))
        val changed = songRowKeys(listOf(c, b, a, a))
        assertEquals(original[1], changed[1])
        assertEquals(original[0], changed[2])
        assertEquals(original[2], changed[3])
        assertEquals(changed.size, changed.toSet().size)
        assertNotEquals(songRowKeys(listOf(a)).single(), songRowKeys(listOf(a.copy(source = "other"))).single())
    }
}
