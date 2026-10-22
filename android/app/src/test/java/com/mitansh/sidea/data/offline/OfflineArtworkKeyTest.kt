package com.mitansh.sidea.data.offline

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineArtworkKeyTest {
    @Test fun preservesExistingSavedCoverFilenamesIncludingLeadingZeroBytes() {
        for (url in listOf("", "abc", "https://lh3.googleusercontent.com/cover=w1200-h1200-l90-rj", "https://example.com/曲?signature=keep%2Bthis")) {
            val previous = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(previous, offlineArtworkKey(url))
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", offlineArtworkKey("abc"))
    }
}
