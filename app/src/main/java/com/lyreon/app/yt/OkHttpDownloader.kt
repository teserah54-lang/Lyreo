/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/**
 * Jembatan MetrolistExtractor (API fork NewPipeExtractor) ↔ OkHttp.
 * Extractor memanggil [execute]/[executeAsync] untuk setiap request web
 * (search, player API, dsb.).
 *
 * Perbedaan penting dari upstream:
 * - `Response` konstruktor 6 argumen: (code, message, headers,
 *   responseBody String, rawResponseBody ByteArray, latestUrl)
 * - `Downloader` punya metode abstrak tambahan [executeAsync].
 */
class OkHttpDownloader private constructor(
    private val client: OkHttpClient,
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val response = client.newCall(buildOkRequest(request)).execute()
        return response.use { buildExtractorResponse(it) }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun executeAsync(
        request: Request,
        callback: Downloader.AsyncCallback,
    ): CancellableCall {
        val call = client.newCall(buildOkRequest(request))
        val cancellable = CancellableCall(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                cancellable.setFinished()
                callback.onError(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                cancellable.setFinished()
                try {
                    response.use { callback.onSuccess(buildExtractorResponse(it)) }
                } catch (e: Exception) {
                    callback.onError(e)
                }
            }
        })
        return cancellable
    }

    // ------------------------------------------------------------------

    private fun buildOkRequest(request: Request): okhttp3.Request {
        val headers: Map<String, List<String>>? = request.headers()
        val dataToSend: ByteArray? = request.dataToSend()

        val contentType = headers?.entries
            ?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()

        val requestBody = when {
            dataToSend == null -> null
            else -> dataToSend.toRequestBody(contentType?.toMediaTypeOrNull())
        }

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), requestBody)

        headers?.forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        if (builder.build().header("User-Agent") == null) {
            builder.header("User-Agent", LyreonHttp.USER_AGENT)
        }
        if (builder.build().header("Accept-Language") == null) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
        }

        return builder.build()
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun buildExtractorResponse(response: okhttp3.Response): Response {
        val code = response.code
        val latestUrl = response.request.url.toString()

        if (code == 429) {
            throw ReCaptchaException("reCaptcha Challenge requested", latestUrl)
        }

        val bodyBytes = response.body.bytes()
        return Response(
            code,
            response.message,
            response.headers.toMultimap(),
            String(bodyBytes, Charsets.UTF_8),
            bodyBytes,
            latestUrl,
        )
    }

    companion object {
        fun init(client: OkHttpClient): OkHttpDownloader = OkHttpDownloader(client)
    }
}
