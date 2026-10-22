package com.mitansh.sidea.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

class YouTubeCatalogSearchTest {
    @Before fun initialize() { NewPipe.init(SideADownloader()) }

    @Test fun `catalog constructs a real music search rather than the broken unfiltered overload`() {
        val extractor = YouTubeCatalog().searchExtractor("Daft Punk Get Lucky")
        assertEquals("music_songs", extractor.linkHandler.contentFilters.single().name)
        assertTrue(extractor.linkHandler.url.startsWith("https://music.youtube.com/"))
    }

    @Test fun `music search preserves a unicode query`() {
        val extractor = YouTubeCatalog().searchExtractor("Björk Jóga")
        assertEquals("Björk Jóga", extractor.searchString)
    }
}
