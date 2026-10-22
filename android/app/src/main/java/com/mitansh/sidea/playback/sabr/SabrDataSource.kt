// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted SABR transport; see THIRD_PARTY_NOTICES.md for provenance.
package com.mitansh.sidea.playback.sabr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InputStream

@UnstableApi
internal class SabrDataSource(
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val maxRedirects: Int = MAX_REDIRECTS,
) : BaseDataSource(true) {

    class Factory(
        private val httpDataSourceFactory: HttpDataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SabrDataSource(httpDataSourceFactory)
    }

    private var assembler: SabrSegmentAssembler? = null

    private var uri: Uri? = null
    private var spec: SabrStreamSpec? = null
    private var endpointUrl: String = ""
    private var redirectsUsed = 0
    private var requestNumber = 0L
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    private var httpDataSource: HttpDataSource? = null
    private var umpReader: UmpReader? = null
    private var unproductiveRequests = 0
    private var producedReadableBytes = false
    private var pendingOffset = 0
    private var pendingRemaining = 0

    override fun open(dataSpec: DataSpec): Long {
        val parsed = SabrStreamSpec.parse(dataSpec.uri.toString())
            ?: throw SabrProtocolException("unsupported SABR stream descriptor")
        transferInitializing(dataSpec)
        spec = parsed
        assembler = SabrSegmentAssembler(parsed.format.itag)
        uri = dataSpec.uri
        endpointUrl = parsed.endpointUrl
        redirectsUsed = 0
        requestNumber = 0L
        unproductiveRequests = 0
        producedReadableBytes = false
        position = dataSpec.position
        val available = (parsed.contentLength - position).coerceAtLeast(0L)
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            minOf(dataSpec.length, available)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        while (pendingRemaining <= 0) {
            if (!advance()) return C.RESULT_END_OF_INPUT
        }
        val payload = umpReader?.partPayload ?: return C.RESULT_END_OF_INPUT
        val count = minOf(length.toLong(), pendingRemaining.toLong(), bytesRemaining).toInt()
        System.arraycopy(payload, pendingOffset, buffer, offset, count)
        pendingOffset += count
        pendingRemaining -= count
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val wasOpen = opened
        opened = false
        closeResponse()
        assembler = null
        spec = null
        uri = null
        endpointUrl = ""
        requestNumber = 0L
        position = 0L
        bytesRemaining = 0L
        if (wasOpen) transferEnded()
    }

    private fun advance(): Boolean {
        val current = spec ?: return false
        while (true) {
            if (bytesRemaining <= 0L || position >= current.contentLength) return false
            val reader = umpReader ?: run {
                openResponse(
                    current,
                    sabrPlayerTimeMsFor(
                        contentLength = current.contentLength,
                        durationMs = current.durationMs,
                        position = position,
                        unproductiveAttempts = unproductiveRequests
                    )
                )
                umpReader
            } ?: return false

            val hasPart = try {
                reader.next()
            } catch (error: IOException) {
                closeResponse()
                throw error
            }
            if (!hasPart) {
                closeResponse()
                if (position >= current.contentLength) return false
                if (!producedReadableBytes) {
                    unproductiveRequests++
                    if (unproductiveRequests > MAX_UNPRODUCTIVE_REQUESTS) {
                        throw SabrProtocolException("SABR stopped delivering data before end of stream")
                    }
                } else {
                    unproductiveRequests = 0
                }
                continue
            }

            when (reader.partType) {
                SabrPart.MEDIA_HEADER -> onMediaHeader(reader)
                SabrPart.MEDIA -> if (onMedia(reader)) return true
                SabrPart.SABR_REDIRECT -> onRedirect(reader)
                SabrPart.SABR_ERROR ->
                    throw SabrProtocolException("SABR server reported a stream error")
                SabrPart.RELOAD_PLAYER_RESPONSE ->
                    throw SabrProtocolException("SABR requires a fresh player response")
                else -> Unit
            }
        }
    }

    private fun onMediaHeader(reader: UmpReader) {
        assembler?.onMediaHeader(SabrMessages.parseMediaHeader(reader.partPayload, reader.partLength))
    }

    private fun onMedia(reader: UmpReader): Boolean {
        val window = assembler?.onMedia(
            headerId = SabrMessages.mediaHeaderId(reader.partPayload, reader.partLength),
            payloadLength = reader.partLength - SabrSegmentAssembler.MEDIA_PAYLOAD_OFFSET,
            position = position
        ) ?: return false
        pendingOffset = window.offset
        pendingRemaining = window.length
        producedReadableBytes = true
        return true
    }

    private fun onRedirect(reader: UmpReader) {
        val target = SabrMessages.parseRedirectUrl(reader.partPayload, reader.partLength)
        if (target == null || !SabrEndpoint.isAllowed(target)) {
            throw SabrProtocolException("SABR redirect rejected")
        }
        if (redirectsUsed >= maxRedirects) {
            throw SabrProtocolException("SABR redirect budget exhausted")
        }
        redirectsUsed++
        endpointUrl = target
        closeResponse()
    }

    private fun openResponse(current: SabrStreamSpec, playerTimeMs: Long) {
        startRequest(current, endpointUrl, playerTimeMs)
    }

    private fun startRequest(current: SabrStreamSpec, endpoint: String, playerTimeMs: Long) {
        val body = SabrMessages.playbackRequest(
            ustreamerConfig = current.ustreamerConfig,
            playerTimeMs = playerTimeMs,
            audioFormat = current.format,
            initializedFormats = initializedFormats(current),
            clientName = current.clientName,
            clientVersion = current.clientVersion
        )
        val request = DataSpec.Builder()
            .setUri(sabrRequestEndpoint(endpoint, requestNumber))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(body)
            .setHttpRequestHeaders(
                buildMap {
                    put("Content-Type", "application/x-protobuf")
                    put("Accept", "*/*")
                    put("Accept-Encoding", "identity")
                    current.userAgent.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                }
            )
            .build()
        val source = httpDataSourceFactory.createDataSource()
        httpDataSource = source
        requestNumber++
        source.open(request)
        val resolvedUri = source.uri?.toString().orEmpty()
        if (!resolvedUri.startsWith("https://") || !SabrEndpoint.isAllowed(resolvedUri)) {
            source.close()
            httpDataSource = null
            throw SabrProtocolException("SABR HTTP redirect rejected")
        }
        umpReader = UmpReader(HttpDataSourceInputStream(source))
        assembler?.reset()
        producedReadableBytes = false
        pendingOffset = 0
        pendingRemaining = 0
    }

    private fun initializedFormats(current: SabrStreamSpec): List<SabrFormatId> {
        return if (position > 0L) listOf(current.format) else emptyList()
    }

    private fun closeResponse() {
        umpReader = null
        pendingOffset = 0
        pendingRemaining = 0
        val source = httpDataSource
        httpDataSource = null
        if (source != null) {
            try {
                source.close()
            } catch (_: IOException) { }
        }
    }

    private class HttpDataSourceInputStream(private val source: HttpDataSource) : InputStream() {
        private val single = ByteArray(1)

        override fun read(): Int {
            val count = read(single, 0, 1)
            return if (count == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = source.read(buffer, offset, length)
            return if (count == C.RESULT_END_OF_INPUT) -1 else count
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val MAX_UNPRODUCTIVE_REQUESTS = 2
    }
}

internal fun sabrRequestEndpoint(endpoint: String, requestNumber: Long): String = endpoint.toHttpUrl()
    .newBuilder()
    .setQueryParameter("rn", requestNumber.toString())
    .build()
    .toString()
