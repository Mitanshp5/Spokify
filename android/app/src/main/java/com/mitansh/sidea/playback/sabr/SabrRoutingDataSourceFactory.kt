// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted SABR transport; see THIRD_PARTY_NOTICES.md for provenance.
package com.mitansh.sidea.playback.sabr

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

@UnstableApi
internal class SabrRoutingDataSourceFactory(context: Context) : DataSource.Factory {
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(false)
    private val regularFactory = DefaultDataSource.Factory(context, httpFactory)
    private val sabrFactory = SabrDataSource.Factory(httpFactory)

    override fun createDataSource(): DataSource = RoutingDataSource(regularFactory, sabrFactory)

    private class RoutingDataSource(
        private val regularFactory: DataSource.Factory,
        private val sabrFactory: DataSource.Factory,
    ) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            active?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val factory = if (SabrStreamSpec.isSabrUri(dataSpec.uri.toString())) sabrFactory else regularFactory
            return factory.createDataSource().also { source ->
                active = source
                listeners.forEach(source::addTransferListener)
            }.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            (active ?: throw IOException("Data source is not open")).read(buffer, offset, length)

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

        override fun close() {
            val source = active
            active = null
            source?.close()
        }
    }
}
