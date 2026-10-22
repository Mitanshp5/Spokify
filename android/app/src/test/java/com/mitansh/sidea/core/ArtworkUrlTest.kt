package com.mitansh.sidea.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkUrlTest {
    @Test fun requestsAlbumArtworkAtFullPlayerResolution() {
        assertEquals("https://lh3.googleusercontent.com/cover=w1200-h1200-l90-rj", ArtworkUrl.forDisplay("https://lh3.googleusercontent.com/cover=w60-h60-l90-rj"))
    }
    @Test fun aSignedImageUrlIsPreservedExactly() {
        val signed = "https://lh3.googleusercontent.com/cover=w60-h60?signature=keep"
        assertEquals(signed, ArtworkUrl.forDisplay(signed))
    }
    @Test fun doesNotInventSizesForOtherImageProviders() {
        val url = "https://i.ytimg.com/vi/recording/hqdefault.jpg"
        assertEquals(url, ArtworkUrl.forDisplay(url))
        assertEquals("https://example.org/cover=w60-h60", ArtworkUrl.forDisplay("https://example.org/cover=w60-h60"))
    }
    @Test fun missingArtworkUsesThePlaceholder() {
        assertNull(ArtworkUrl.forDisplay(null))
        assertNull(ArtworkUrl.forDisplay(""))
    }
}
