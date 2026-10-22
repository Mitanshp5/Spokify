package com.mitansh.sidea.updates

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class AppUpdate(val manifest: UpdateManifest, val apkUrl: String, val releaseUrl: String, val notes: String)

internal class GitHubUpdateSource(private val repository: String) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES)
        .followSslRedirects(false).build()

    suspend fun latest(): AppUpdate? {
        val raw = request("https://api.github.com/repos/$repository/releases/latest") { response ->
            if (response.code == 404) null else {
                checkResponse(response)
                response.readBounded(512 * 1024)
            }
        } ?: return null
        val json = JsonParser.`object`().from(raw)
        require(!json.getBoolean("draft") && !json.getBoolean("prerelease")) { "This release is not ready yet." }
        val assets = json.getArray("assets")?.filterIsInstance<JsonObject>().orEmpty()
        fun asset(name: String): JsonObject = assets.singleOrNull { it.getString("name") == name }
            ?: throw IOException("This release is missing $name. Try again later.")
        val metadataUrl = asset("spokify-update.json").getString("browser_download_url").orEmpty()
        val apkAsset = asset("spokify.apk")
        val apkUrl = apkAsset.getString("browser_download_url").orEmpty()
        validateAssetUrl(repository, metadataUrl)
        validateAssetUrl(repository, apkUrl)
        val manifest = UpdateManifest.parse(request(metadataUrl) { response ->
            checkResponse(response)
            response.readBounded(32 * 1024)
        })
        require(apkAsset.getLong("size") == manifest.sizeBytes) { "The update size does not match its release." }
        val githubDigest = apkAsset.getString("digest")
        require(githubDigest.isNullOrBlank() || githubDigest == "sha256:${manifest.sha256}") { "The release checksum does not match." }
        return AppUpdate(manifest, apkUrl, "https://github.com/$repository/releases/latest", json.getString("body").orEmpty().take(8_000))
    }

    suspend fun download(update: AppUpdate, destination: File, onProgress: (Long) -> Unit) {
        validateAssetUrl(repository, update.apkUrl)
        request(update.apkUrl, timeoutSeconds = 180) { response ->
            checkResponse(response)
            val length = response.body.contentLength()
            require(length < 0 || length == update.manifest.sizeBytes) { "Unexpected update download size." }
            val input = response.body.byteStream()
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                var received = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    require(received <= update.manifest.sizeBytes) { "The download exceeds the expected update size." }
                    output.write(buffer, 0, count)
                    onProgress(received)
                }
                require(received == update.manifest.sizeBytes) { "The update download was interrupted. Try again." }
            }
        }
    }

    // The blocking reader is an IO child. Cancellation cancels the HTTP call,
    // interrupting both connection waits and a response body read immediately.
    private suspend fun <T> request(url: String, timeoutSeconds: Long = 20, read: (Response) -> T): T = coroutineScope {
        val call = client.newCall(Request.Builder().url(url)
            .header("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/octet-stream")
            .header("User-Agent", "Spokify-Android-Updater").build())
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        val operation = async(Dispatchers.IO) { call.execute().use(read) }
        try { operation.await() } finally { call.cancel() }
    }

    private fun checkResponse(response: Response) {
        if (!response.isSuccessful) throw IOException(when (response.code) {
            403, 429 -> "GitHub's request limit was reached. Try again later."
            else -> "Couldn't reach the update server (HTTP ${response.code}). Try again."
        })
    }

    private fun Response.readBounded(limit: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val input = body.byteStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "The update server response is too large." }
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
}

internal fun validateAssetUrl(repository: String, raw: String) {
    val url = raw.toHttpUrl()
    require(url.isHttps && url.host == "github.com" && url.username.isEmpty() && url.password.isEmpty()
        && url.encodedPath.startsWith("/$repository/releases/download/") && url.query == null && url.fragment == null) {
        "The update download is outside the configured release repository."
    }
}
