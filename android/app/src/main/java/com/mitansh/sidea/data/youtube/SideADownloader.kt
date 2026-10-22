package com.mitansh.sidea.data.youtube

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class SideADownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    override fun execute(request: Request): Response = response(client.newCall(request.toHttp()).execute())

    override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall {
        val call = client.newCall(request.toHttp())
        val result = CancellableCall(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.setFinished(); callback.onError(e)
            }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                try { callback.onSuccess(response(response)) } catch (e: Exception) { callback.onError(e) }
                finally { result.setFinished() }
            }
        })
        return result
    }

    private fun Request.toHttp(): HttpRequest {
        val builder = HttpRequest.Builder().url(url()).apply {
            headers().forEach { (name, values) -> values.forEach { addHeader(name, it) } }
            when (httpMethod().uppercase()) {
                "GET" -> get()
                "HEAD" -> head()
                "POST" -> post((dataToSend() ?: ByteArray(0)).toRequestBody())
                "OPTIONS" -> method("OPTIONS", null)
                else -> throw IOException("Unsupported HTTP method: ${httpMethod()}")
            }
        }
        return builder.build()
    }

    private fun response(response: okhttp3.Response): Response = response.use {
        val bytes = it.body.bytes()
        Response(it.code, it.message, it.headers.toMultimap(), String(bytes), bytes, it.request.url.toString())
    }
}
