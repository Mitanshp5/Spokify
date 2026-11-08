package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.YouTubeUrlParser
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Live check: does any search path surface a recording of the song that serves the whole file?
 */
class AlternativeRecordingLiveTest {
    @Before fun initialize() { YouTubeCatalog.initialize() }

    @Test fun `finds an alternative recording of the same song`() = runBlocking<Unit> {
        assumeTrue("Set SIDEA_ALT_LIVE=1", System.getenv("SIDEA_ALT_LIVE") == "1")
        val target = "Kf5pXDhx5Vc"
        val queries = listOf(
            "End of Beginning", "Djo End of Beginning", "Djo End of Beginning Djo",
            "End of Beginning Djo Topic", "Djo - Topic End of Beginning",
        )
        for (query in queries) {
            try {
                val extractor = ServiceList.YouTube.getSearchExtractor(query)
                extractor.fetchPage()
                val ids = extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { item -> YouTubeUrlParser.videoId(item.url) }
                println("ALT generic query='$query' count=${ids.size} hasTarget=${ids.contains(target)}")
                println("ALT   ids=${ids.take(25)}")
            } catch (error: Exception) {
                println("ALT generic query='$query' error=${error::class.java.simpleName}: ${error.message?.take(90)}")
            }
        }
    }
}
