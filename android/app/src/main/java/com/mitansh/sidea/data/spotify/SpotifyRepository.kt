package com.mitansh.sidea.data.spotify

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class SpotifyFailure { INVALID_INPUT, NETWORK, RATE_LIMITED, NOT_AVAILABLE, PROVIDER_CHANGED, TOO_LARGE }
class SpotifyImportException(val kind: SpotifyFailure, message: String, cause: Throwable? = null) : IOException(message, cause)
data class SpotifyApiPage(val body: String)
fun interface SpotifyApiTransport {
    suspend fun get(url: String): SpotifyApiPage
    suspend fun playlistPage(id: String, offset: Int, anonymousToken: String): SpotifyApiPage =
        get(SpotifyPublicPages.url(id, offset))
}

class SpotifyRepository(private val transport: SpotifyApiTransport = PublicSpotifyTransport()) {
    suspend fun publicPlaylist(input: String): SpotifyPlaylistPreview {
        val id = playlistId(input)
        val html = try { transport.get("https://open.spotify.com/embed/playlist/$id").body }
        catch (e: CancellationException) { throw e }
        catch (e: SpotifyImportException) { throw e }
        catch (e: Exception) { throw SpotifyImportException(SpotifyFailure.NETWORK, "Spotify request failed", e) }
        val preview = try { SpotifyPlaylist.parseEmbed(html).copy(id = id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw SpotifyImportException(SpotifyFailure.PROVIDER_CHANGED, "Spotify embed could not be parsed", e) }
        val token = SpotifyPublicPages.anonymousToken(html)
            ?: return preview.copy(warning = preview.warning ?: "Only Spotify's public preview could be read; the playlist may contain more songs.")
        val tracks = mutableListOf<SpotifySourceTrack>()
        var title = preview.title
        var total: Int? = null
        var offset = 0
        try {
            while (true) {
                val page = SpotifyPublicPages.parse(transport.playlistPage(id, offset, token).body, id, offset)
                require(total == null || total == page.total) { "Spotify playlist changed while reading. Read it again." }
                total = page.total
                if (offset == 0 && page.tracks.isEmpty() && page.total > 0) {
                    return preview.copy(completeness = SpotifyCompleteness.PARTIAL, declaredCount = page.total,
                        warning = "Only Spotify's public preview (${preview.tracks.size} songs) could be read. Spotify returned no rows for ${page.total} songs; try again later.")
                }
                title = page.title.ifBlank { title }
                tracks += page.tracks.take(SpotifyPublicPages.MAX_TRACKS - tracks.size)
                if (page.next == null) {
                    val complete = tracks.size == total
                    return SpotifyPlaylistPreview(id, title, tracks, if (complete) SpotifyCompleteness.COMPLETE else SpotifyCompleteness.PARTIAL,
                        total, if (complete) null else "Read ${tracks.size} of $total songs. Spotify did not return the remaining rows; read the playlist again.")
                }
                require(page.tracks.isNotEmpty() && page.next == offset + page.tracks.size && page.next > offset) { "Spotify returned a non-advancing playlist page" }
                if (tracks.size >= SpotifyPublicPages.MAX_TRACKS) {
                    return SpotifyPlaylistPreview(id, title, tracks, SpotifyCompleteness.PARTIAL, total,
                        "Read ${tracks.size} of $total songs. The import safety limit is ${SpotifyPublicPages.MAX_TRACKS} songs.")
                }
                offset = page.next
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (total == null) return preview.copy(completeness = SpotifyCompleteness.UNKNOWN,
                warning = "Only Spotify's public preview (${preview.tracks.size} songs) could be read. Full playlist reading is temporarily unavailable; try again later.")
            return SpotifyPlaylistPreview(id, title, tracks, SpotifyCompleteness.PARTIAL, total,
                "Read ${tracks.size} of $total songs. The remaining pages could not be read; try again later.")
        }
    }

    private fun playlistId(input: String): String = SpotifyPlaylist.parseId(input)
        ?: input.trim().takeIf { Regex("[A-Za-z0-9]{22}").matches(it) }
        ?: throw SpotifyImportException(SpotifyFailure.INVALID_INPUT, "Not a Spotify playlist link")
}

private class PublicSpotifyTransport : SpotifyApiTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    override suspend fun get(url: String): SpotifyApiPage {
        val parsed = runCatching { URI(url) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host != "open.spotify.com" || parsed.userInfo != null || parsed.port !in listOf(-1, 443) || !Regex("/embed/playlist/[A-Za-z0-9]{22}").matches(parsed.path)) throw SpotifyImportException(SpotifyFailure.INVALID_INPUT, "Spotify URL not allowed")
        return fetch(url, null)
    }

    override suspend fun playlistPage(id: String, offset: Int, anonymousToken: String): SpotifyApiPage =
        fetch(SpotifyPublicPages.url(id, offset), anonymousToken)

    private suspend fun fetch(url: String, anonymousToken: String?): SpotifyApiPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", if (anonymousToken == null) "text/html" else "application/json")
            .apply { if (anonymousToken != null) header("Authorization", "Bearer $anonymousToken") }.build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> throw SpotifyImportException(SpotifyFailure.NOT_AVAILABLE, "Public playlist preview unavailable")
                    403, 404 -> throw SpotifyImportException(SpotifyFailure.NOT_AVAILABLE, "Spotify playlist unavailable")
                    429 -> throw SpotifyImportException(SpotifyFailure.RATE_LIMITED, "Spotify rate limit reached")
                    in 200..299 -> Unit
                    else -> throw SpotifyImportException(SpotifyFailure.NETWORK, "Spotify returned HTTP ${response.code}")
                }
                val body = response.body
                if (body.contentLength() > MAX_BYTES) throw SpotifyImportException(SpotifyFailure.TOO_LARGE, "Spotify response too large")
                val bytes = body.byteStream().use { stream ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count = 0
                    while (true) { val n = stream.read(buffer); if (n < 0) break; count += n; if (count > MAX_BYTES) throw SpotifyImportException(SpotifyFailure.TOO_LARGE, "Spotify response too large"); output.write(buffer, 0, n) }
                    output.toByteArray()
                }
                SpotifyApiPage(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: SpotifyImportException) { throw e }
        catch (e: IOException) { throw SpotifyImportException(SpotifyFailure.NETWORK, "Spotify request failed", e) }
    }
    companion object { const val MAX_BYTES = 4 * 1024 * 1024 }
}
