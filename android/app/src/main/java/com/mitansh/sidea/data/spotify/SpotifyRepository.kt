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
fun interface SpotifyApiTransport { suspend fun get(url: String): SpotifyApiPage }

class SpotifyRepository(private val transport: SpotifyApiTransport = PublicSpotifyTransport()) {
    suspend fun publicPlaylist(input: String): SpotifyPlaylistPreview {
        val id = playlistId(input)
        val html = try { transport.get("https://open.spotify.com/embed/playlist/$id").body }
        catch (e: CancellationException) { throw e }
        catch (e: SpotifyImportException) { throw e }
        catch (e: Exception) { throw SpotifyImportException(SpotifyFailure.NETWORK, "Spotify request failed", e) }
        return try { SpotifyPlaylist.parseEmbed(html).copy(id = id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw SpotifyImportException(SpotifyFailure.PROVIDER_CHANGED, "Spotify embed could not be parsed", e) }
    }

    private fun playlistId(input: String): String = SpotifyPlaylist.parseId(input)
        ?: input.trim().takeIf { Regex("[A-Za-z0-9]{22}").matches(it) }
        ?: throw SpotifyImportException(SpotifyFailure.INVALID_INPUT, "Not a Spotify playlist link")
}

private class PublicSpotifyTransport : SpotifyApiTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    override suspend fun get(url: String): SpotifyApiPage = withContext(Dispatchers.IO) {
        val parsed = runCatching { URI(url) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host != "open.spotify.com" || parsed.userInfo != null || parsed.port !in listOf(-1, 443) || !Regex("/embed/playlist/[A-Za-z0-9]{22}").matches(parsed.path)) throw SpotifyImportException(SpotifyFailure.INVALID_INPUT, "Spotify URL not allowed")
        val request = Request.Builder().url(url).header("Accept", "text/html")
            .build()
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
