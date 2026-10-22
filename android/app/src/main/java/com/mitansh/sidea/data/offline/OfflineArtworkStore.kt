package com.mitansh.sidea.data.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mitansh.sidea.core.ArtworkUrl
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Small durable album covers, independent of Coil's evictable image cache. */
object OfflineArtworkStore {
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    fun file(context: Context, url: String): File {
        val key = offlineArtworkKey(url)
        return File(context.filesDir, "offline-art/$key.jpg")
    }

    fun save(context: Context, artworkUrl: String?) {
        val url = ArtworkUrl.forDisplay(artworkUrl) ?: return
        val target = file(context, url)
        if (target.isFile) return
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                if (body.contentLength() > 2 * 1024 * 1024) return
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 2 * 1024 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 2 * 1024 * 1024) return
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
                val options = BitmapFactory.Options().apply {
                    inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 768).coerceAtLeast(1)
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, target.name + ".tmp")
                try {
                    temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    if (temporary.length() in 1..512 * 1024 && temporary.renameTo(target)) {
                        revision.update { it + 1 }
                    }
                } finally {
                    temporary.delete()
                    bitmap.recycle()
                }
            }
        }
    }
}
