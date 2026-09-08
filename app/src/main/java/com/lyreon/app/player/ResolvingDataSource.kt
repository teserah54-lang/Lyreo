/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lyreon.app.yt.LyreonHttp
import com.lyreon.app.yt.YouTubeRepository
import java.io.IOException

/**
 * DataSource yang menyelesaikan skema `lyreon://audio/{videoId}` menjadi:
 *  1) file lokal (jika sudah diunduh), atau
 *  2) URL stream audio YouTube (dengan cache ±6 jam).
 *
 * Dipanggil di thread loader ExoPlayer — pola yang sama dengan InnerTune/Metrolist,
 * sehingga auto-advance antrean & prefetch bekerja tanpa UI memediasi.
 */
class ResolvingDataSource(
    private val youtube: YouTubeRepository,
    private val downloadFileLookup: (String) -> String?,
    private val upstream: DataSource,
) : BaseDataSource(true) {

    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        var fallbackUri: Uri? = null
        var resolvableVideoId: String? = null
        val resolvedSpec: DataSpec = if (uri.scheme == LYREON_SCHEME) {
            val videoId = uri.lastPathSegment
                ?: throw IOException("URI lyreon tidak valid: $uri")
            resolvableVideoId = videoId

            val local = downloadFileLookup(videoId)
            val target = when {
                !local.isNullOrBlank() && java.io.File(local).exists() ->
                    Uri.fromFile(java.io.File(local))
                else -> try {
                    val resolved = youtube.resolveCachedBlocking(videoId)
                    // Loudness format terpilih → bahan normalisasi volume per lagu.
                    resolved.loudnessDb?.let { db ->
                        com.lyreon.app.player.audio.LoudnessStore.record(videoId, db)
                    }
                    // Hasilnya manifest HLS? ProgressiveMediaSource tidak bisa
                    // memutarnya — beri tahu PlayerManager supaya MediaItem ditukar
                    // ke URL m3u8 + MIME yang benar (HlsMediaSource).
                    if (resolved.isManifest) {
                        throw HlsRequiredException(videoId, resolved.url, resolved.mimeType)
                    }
                    resolved.fallbackUrl
                        ?.takeUnless { it.isBlank() || it == resolved.url }
                        ?.let { fallbackUri = Uri.parse(it) }
                    Uri.parse(resolved.url)
                } catch (e: HlsRequiredException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Tidak bisa menyelesaikan stream $videoId: ${e.message}", e)
                }
            }
            dataSpec.withUri(target)
        } else {
            dataSpec
        }

        val length = try {
            upstream.open(resolvedSpec)
        } catch (first: Exception) {
            val backup = fallbackUri
            if (backup != null) {
                // STREAM CADANGAN: URL utama tak tersedia (403/diblokir) —
                // langsung coba kandidat ke-2 dari keluarga format lain
                // (m4a ↔ webm/opus) sebelum menyerah.
                runCatching { upstream.close() }
                try {
                    upstream.open(resolvedSpec.withUri(backup))
                } catch (second: Exception) {
                    resolvableVideoId?.let { youtube.invalidate(it) }
                    throw second
                }
            } else {
                // Jika URL expired (403), invalidasi cache sekali lalu lempar —
                // PlayerManager akan retry dengan URL baru.
                resolvableVideoId?.let { youtube.invalidate(it) }
                throw first
            }
        }
        opened = true
        transferStarted(resolvedSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val context: Context,
        private val youtube: YouTubeRepository,
        private val downloadFileLookup: (String) -> String?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val okHttpFactory = OkHttpDataSource.Factory(LyreonHttp.streamClient)
            val defaultFactory = DefaultDataSource.Factory(context, okHttpFactory)
            return ResolvingDataSource(youtube, downloadFileLookup, defaultFactory.createDataSource())
        }
    }

    companion object {
        const val LYREON_SCHEME = "lyreon"
        fun uriOf(videoId: String): Uri = Uri.parse("$LYREON_SCHEME://audio/$videoId")
    }
}

/**
 * Sinyal "stream ini manifest HLS, bukan file progresif".
 *
 * Dilempar dari [ResolvingDataSource] (thread loader ExoPlayer) dan ditangkap
 * `PlayerManager`, yang lalu menukar `MediaItem` lagu itu ke [manifestUrl] dengan
 * `mimeType` [manifestMime] supaya `DefaultMediaSourceFactory` membangun
 * `HlsMediaSource` (memerlukan `media3-exoplayer-hls`).
 *
 * Kenapa lewat error, bukan langsung memasang URL m3u8 di `MediaItem`? Karena
 * Lyreon memakai resolusi malas (`lyreon://audio/{id}`) agar antrean terbentuk
 * seketika tanpa menunggu jaringan; tipe MediaSource harus diketahui saat
 * `MediaItem` dibuat, dan itu hanya bisa dipastikan setelah resolusi.
 */
class HlsRequiredException(
    val videoId: String,
    val manifestUrl: String,
    val manifestMime: String,
) : IOException("Stream $videoId hanya tersedia sebagai manifest HLS")
