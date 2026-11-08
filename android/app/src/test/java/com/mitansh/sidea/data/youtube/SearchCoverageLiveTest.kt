package com.mitansh.sidea.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList

/**
 * Live check: which recordings of the song can the app's own search find?
 */
class SearchCoverageLiveTest {
    @Before fun initialize() { YouTubeCatalog.initialize() }

    @Test fun `lists the recordings the app can find`() = runBlocking<Unit> {
        assumeTrue("Set SIDEA_COVERAGE_LIVE=1", System.getenv("SIDEA_COVERAGE_LIVE") == "1")
        val catalog = YouTubeCatalog()
        for (query in listOf("End of Beginning", "Djo End of Beginning", "Djo")) {
            val tracks = catalog.search(query)
            println("COVERAGE query='$query' count=${tracks.size}")
            for (track in tracks) {
                println("COVERAGE   id=${track.id} title='${track.title}' artist='${track.artist}'")
            }
        }
        // The generic video search, without the music filter, may expose the other upload.
        val extractor = ServiceList.YouTube.getSearchExtractor("Djo End of Beginning")
        extractor.fetchPage()
        println("COVERAGE generic results=${extractor.initialPage.items.size}")
        for (item in extractor.initialPage.items) {
            val id = (item as? org.schabi.newpipe.extractor.stream.StreamInfoItem)?.url
            println("COVERAGE   generic ${item.infoType} ${item.name} :: ${id}")
        }
    }
}
