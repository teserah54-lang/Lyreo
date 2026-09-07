/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt.innertube

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uji URL stream SEBELUM diserahkan ke ExoPlayer.
 *
 * Porting `YTPlayerUtils.validateStatus` + `describeStreamUrl` milik
 * [FrancescoGrazioso/Meld](https://github.com/FrancescoGrazioso/Meld) (September 2026),
 * disederhanakan untuk Lyreon yang anonim (tanpa cookie → tidak ada header `Cookie`).
 *
 * ## Kenapa "ada URL" belum berarti "bisa diputar"
 *
 * Diagnostik Lyreon sebelumnya menyebut klien `USABLE` begitu response membawa
 * `adaptiveFormats[].url`. Itu menyesatkan: pengukuran Meld (tiga videoId, di-binari
 * sampai byte-nya) menunjukkan URL dari `IOS`/`IPADOS`/`ANDROID_VR` lawas hanyalah
 * **pratinjau ~1 MiB** — googlevideo melayani awalan byte tetap lalu menjawab 403 untuk
 * semua offset sesudahnya:
 *
 * ```
 * videoId       itag  byte terakhir terbaca   = detik audio
 * Rr1Cdli5nE8   251   1.040.807                ~61 dari 268
 * phLb_SoPBlA   251   1.049.091                ~61 dari 274
 * UbX5Yns8fHk   251   1.019.638                ~67 dari 159
 * ```
 *
 * Bukan batas laju, bukan jumlah request, bukan kedaluwarsa: URL baru yang meminta
 * `bytes=524288-1048575` sebagai request PERTAMA-nya pun langsung 403. Inilah sumber
 * laporan "lagu mati setelah 30–90 detik" — ExoPlayer membaca ke depan, jadi 403 terjadi
 * sebelum jeda terdengar.
 *
 * Karena itu probe **harus menjangkau melewati jendela pratinjau**: bila `contentLength`
 * diketahui, yang diminta adalah byte TERAKHIR file (`bytes=clen-1-clen-1`). URL yang
 * sanggup melayani byte terakhirnya pasti bukan pratinjau terpotong, dan pemeriksaannya
 * tidak bergantung pada di mana batas cap tertentu jatuh. Bila `contentLength` tidak ada,
 * baru dipakai chunk pertama yang memang akan diminta ExoPlayer.
 *
 * ## Kenapa harus pakai `Range` (bukan HEAD polos)
 *
 * HEAD tanpa `Range` dijawab **403 untuk setiap art track YouTube Music** (unggahan
 * `- Topic`, hampir semua yang diputar aplikasi musik) padahal GET ber-`Range` dijawab
 * 206 — pengukuran Meld:
 *
 * ```
 * videoId       klien   HEAD(tanpaRange)  HEAD(Range0-512k)  GET(Range0-512k)
 * Rr1Cdli5nE8   IPADOS  403               206                206
 * phLb_SoPBlA   IPADOS  403               206                206
 * dQw4w9WgXcQ   IPADOS  200               206                206
 * ```
 *
 * Video biasa menjawab 200, jadi probe tanpa Range "terlihat benar" saat uji santai lalu
 * gagal sistematis di produksi.
 */
internal object StreamUrlValidator {

    private const val TAG = "StreamUrlValidator"

    /**
     * Ukuran chunk media pertama yang diminta ExoPlayer — nilai yang sama dipakai Meld
     * (`VALIDATION_CHUNK_LENGTH`, disinkronkan dengan `MusicService.CHUNK_LENGTH` mereka).
     *
     * Lyreon tidak memasang chunk length khusus, jadi `DefaultLoadControl` memulai dengan
     * buffer seorde nilai ini; angka 512 KiB dipertahankan supaya probe dan request nyata
     * tetap sepadan. Hanya dipakai bila `contentLength` tidak diketahui — selama ukuran
     * file ada, probe selalu menyasar byte terakhir (lihat KDoc kelas).
     */
    const val VALIDATION_CHUNK_LENGTH = 512 * 1024L

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    /** Hasil satu probe. */
    data class Verdict(
        val accepted: Boolean,
        val code: Int,
        val range: String,
        /** True bila gagal jaringan (timeout/reset) → diterima secara optimistis. */
        val io: Boolean = false,
        /** True bila diterima karena metode HEAD ditolak (405) — ExoPlayer akan GET. */
        val headRefused: Boolean = false,
    ) {
        /** Ringkasan satu token untuk baris cascade/diagnostik. */
        fun short(): String = when {
            io -> "io?"
            headRefused -> "405?"
            else -> code.toString()
        }
    }

    /**
     * Aturan penerimaan (sama dengan Meld):
     *  - 2xx (200/206) → valid
     *  - 405 → valid (HEAD ditolak mentah-mentah; ExoPlayer akan GET)
     *  - 403/410 → TIDAK valid, lanjut ke klien berikutnya
     *  - IOException (timeout/reset) → valid optimistis; ExoPlayer punya retry sendiri,
     *    membuang klien di sini hanya membuat kita turun tangga tanpa alasan
     *  - kode HTTP lain (4xx/5xx) → tidak valid
     *
     * @param contentLength panjang file sebenarnya; bila ada, probe menyasar byte terakhir.
     * @param label konteks untuk log (klien, itag, …).
     */
    fun validate(
        url: String,
        contentLength: Long? = null,
        label: String = "",
        userAgent: String? = null,
    ): Verdict {
        val effectiveLength = contentLength?.takeIf { it > 0 } ?: contentLengthFromUrl(url)
        val range = if (effectiveLength != null && effectiveLength > 0) {
            "bytes=${effectiveLength - 1}-${effectiveLength - 1}"
        } else {
            "bytes=0-${VALIDATION_CHUNK_LENGTH - 1}"
        }
        return try {
            val builder = Request.Builder().url(url).head().header("Range", range)
            // Samakan UA dengan request ExoPlayer (interceptor `streamClient` memasang UA
            // milik spec klien yang mencetak URL). Probe yang memakai UA berbeda bisa
            // ditolak CDN padahal pemutarannya sah — false negative yang mahal.
            val effectiveUa = userAgent?.takeIf { it.isNotBlank() } ?: userAgentForUrl(url)
            effectiveUa?.let { builder.header("User-Agent", it) }
            val request = builder.build()
            probeClient.newCall(request).execute().use { response ->
                val code = response.code
                val accepted = response.isSuccessful || code == 405
                val verdict = Verdict(
                    accepted = accepted,
                    code = code,
                    range = range,
                    headRefused = accepted && !response.isSuccessful,
                )
                when {
                    !accepted -> Log.w(
                        TAG,
                        "URL stream DITOLAK: code=$code range=$range $label ${describe(url)}",
                    )
                    verdict.headRefused -> Log.w(
                        TAG,
                        "URL stream diterima pada non-2xx code=$code (HEAD ditolak) $label",
                    )
                    else -> Log.d(TAG, "validasi URL stream: code=$code range=$range $label diterima")
                }
                verdict
            }
        } catch (e: IOException) {
            // Timeout/reset saat probe HEAD. URL-nya sendiri mungkin baik-baik saja —
            // biarkan ExoPlayer mencoba GET daripada membakar satu klien fallback.
            Log.w(TAG, "probe HEAD gagal (IO: ${e.javaClass.simpleName}) — diterima optimistis · $label")
            Verdict(accepted = true, code = 0, range = range, io = true)
        } catch (e: Exception) {
            Log.e(TAG, "validasi URL stream gagal: ${e.javaClass.simpleName}: ${e.message}")
            Verdict(accepted = false, code = -1, range = range)
        }
    }

    /**
     * Probe manifest HLS (m3u8) — TANPA header `Range`.
     *
     * Berbeda dari [validate]: manifest diminta ExoPlayer (`HlsMediaSource`) dengan GET
     * polos, dan aturan "HEAD tanpa Range dijawab 403 untuk art track" berlaku pada media
     * googlevideo, bukan pada manifest. Mengirim Range ke manifest justru bisa ditolak CDN
     * yang tidak mendukung partial response untuk teks, sehingga hasilnya menyesatkan.
     */
    fun validateManifest(url: String, label: String = "", userAgent: String? = null): Verdict = try {
        val builder = Request.Builder().url(url).head()
        userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        val request = builder.build()
        probeClient.newCall(request).execute().use { response ->
            val code = response.code
            val accepted = response.isSuccessful || code == 405
            Verdict(
                accepted = accepted,
                code = code,
                range = "-",
                headRefused = accepted && !response.isSuccessful,
            ).also {
                if (!accepted) Log.w(TAG, "manifest HLS DITOLAK: code=$code $label ${describe(url)}")
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "probe manifest gagal (IO: ${e.javaClass.simpleName}) — diterima optimistis · $label")
        Verdict(accepted = true, code = 0, range = "-", io = true)
    } catch (e: Exception) {
        Log.e(TAG, "validasi manifest gagal: ${e.javaClass.simpleName}: ${e.message}")
        Verdict(accepted = false, code = -1, range = "-")
    }

    /**
     * User-Agent yang akan dipasang `streamClient` untuk URL ini — diturunkan dari
     * parameter `c=` (nama klien) dan `cver=` (versi) yang ditempelkan YouTube di URL
     * googlevideo. Memakai ini membuat probe identik dengan request pemutaran, termasuk
     * untuk URL yang datang dari extractor NewPipe (bukan dari tangga klien kita).
     */
    fun userAgentForUrl(url: String): String? = try {
        val uri = Uri.parse(url)
        PlayerClientLadder.streamUserAgentFor(uri.getQueryParameter("c"), uri.getQueryParameter("cver"))
    } catch (e: Exception) {
        null
    }

    /**
     * Panjang file dari parameter `clen` di URL googlevideo — dipakai bila response
     * player tidak menyertakan `contentLength` (beberapa klien hanya memberi
     * `approxDurationMs`).
     */
    fun contentLengthFromUrl(url: String): Long? =
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()

    /**
     * Deskripsi URL googlevideo yang **sudah disensor** untuk log/diagnostik.
     *
     * Logcat dari laporan 403 sering tidak memuat URL sama sekali, jadi setiap kesimpulan
     * harus ditebak dari mime/bitrate. Parameter inilah yang benar-benar menentukan CDN
     * mau melayani stream atau tidak. Materi tanda tangan (`sig`, `signature`, `sparams`)
     * tidak pernah dicetak; `pot`/`n` direduksi jadi keberadaan + panjang.
     */
    fun describe(url: String): String = try {
        val uri = Uri.parse(url)
        val expire = uri.getQueryParameter("expire")?.toLongOrNull()
        val nowSec = System.currentTimeMillis() / 1000
        buildString {
            append("host=").append(uri.host ?: "?")
            append(" itag=").append(uri.getQueryParameter("itag") ?: "-")
            append(" mime=").append(uri.getQueryParameter("mime") ?: "-")
            append(" c=").append(uri.getQueryParameter("c") ?: "-")
            append(" cver=").append(uri.getQueryParameter("cver") ?: "-")
            append(" expire=").append(expire ?: "-")
            if (expire != null) append("(sisa ").append(expire - nowSec).append("s)")
            append(" hasPot=").append(uri.getQueryParameter("pot") != null)
            append(" nLen=").append(uri.getQueryParameter("n")?.length ?: -1)
            append(" cpn=").append(uri.getQueryParameter("cpn") ?: "-")
            append(" lmt=").append(uri.getQueryParameter("lmt") ?: "-")
            append(" sabr=").append(uri.getQueryParameter("sabr") ?: "-")
            append(" clen=").append(uri.getQueryParameter("clen") ?: "-")
        }
    } catch (e: Exception) {
        "url tak bisa diurai (${e.javaClass.simpleName})"
    }
}
