/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Mengunduh **manifest HLS** menjadi satu file audio yang utuh.
 *
 * ## Kenapa perlu
 *
 * Klien YouTube yang masih terbuka untuk pengguna anonim (tanpa poToken) sering
 * hanya memberi `hlsManifestUrl`, bukan URL file. Pemutaran sudah didukung
 * (`media3-exoplayer-hls`), tetapi mesin unduhan Lyreon menulis satu file dari
 * satu response HTTP — tanpa kelas ini, lagu yang hanya punya HLS tidak bisa
 * diunduh sama sekali.
 *
 * ## Cara kerja
 *
 * 1. Ambil playlist. Bila ia master (`#EXT-X-STREAM-INF`), pilih satu varian:
 *    rendisi audio murni bila ada, kalau tidak varian ber-bandwidth terendah yang
 *    masih layak (HLS YouTube biasanya muxed video+audio, jadi varian termurah =
 *    paling hemat kuota).
 * 2. Ambil media playlist varian itu → kumpulkan `#EXT-X-MAP` (init segment fMP4)
 *    dan seluruh URI segmen.
 * 3. Tulis init + semua segmen **berurutan** ke satu file. Untuk fMP4 hasilnya
 *    adalah fragmented-MP4 yang valid (satu `ftyp`/`moov` lalu rangkaian
 *    `moof`+`mdat`); untuk MPEG-TS hasilnya adalah aliran TS yang memang bisa
 *    disambung apa adanya.
 * 4. Verifikasi bait pertama file (kotak `ftyp` atau sync byte `0x47`). Bila
 *    bukan keduanya, file dihapus dan unduhan dinyatakan gagal — lebih baik
 *    gagal terang-terangan daripada menyimpan berkas rusak.
 *
 * Tidak ada muxing/transcoding (tidak butuh FFmpeg): yang dilakukan hanya
 * mengambil segmen yang sudah ter-encode dan menyatukannya kembali.
 */
internal object HlsFlatDownloader {

    /** Hasil unduhan: jumlah bait + wadah yang terdeteksi. */
    data class Result(val bytes: Long, val container: Container)

    enum class Container(val suffix: String) {
        /** Fragmented MP4 (CMAF) — khas HLS YouTube dari klien web/safari. */
        FMP4("mp4"),

        /** MPEG-TS. */
        TS("ts"),

        /** Tidak dikenali (file tetap disimpan sebagai `.bin` dan dianggap gagal). */
        UNKNOWN("bin"),
    }

    /** Bandwidth minimum varian yang dipilih bila tidak ada rendisi audio murni. */
    private const val MIN_VARIANT_BANDWIDTH = 400_000

    suspend fun downloadFlat(
        client: OkHttpClient,
        manifestUrl: String,
        target: File,
        isCancelled: suspend () -> Boolean,
        onProgress: suspend (doneBytes: Long, estimatedTotal: Long, segmentsDone: Int, segmentsTotal: Int) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val master = fetchText(client, manifestUrl)

        // 1) master playlist → pilih varian
        val mediaUrl = pickVariantUrl(master, manifestUrl) ?: manifestUrl
        val media = if (mediaUrl == manifestUrl) master else fetchText(client, mediaUrl)

        // 2) kumpulkan init segment + segmen
        val initUrl = parseInitSegment(media, mediaUrl)
        val segments = parseSegments(media, mediaUrl)
        if (segments.isEmpty()) {
            throw IOException("Manifest HLS tidak memuat segmen apa pun")
        }

        // 3) tulis berurutan
        var written = 0L
        var firstSegmentSize = 0L
        target.outputStream().buffered().use { out ->
            if (initUrl != null) {
                val bytes = fetchBytes(client, initUrl)
                out.write(bytes)
                written += bytes.size
            }
            segments.forEachIndexed { index, url ->
                if (isCancelled()) throw IOException("Dibatalkan")
                val bytes = fetchBytes(client, url)
                out.write(bytes)
                written += bytes.size
                if (index == 0) firstSegmentSize = bytes.size.toLong()
                // Estimasi total: ukuran segmen pertama × jumlah segmen. Cukup
                // akurat untuk bar progres (segmen HLS seragam ukurannya).
                val estimated = if (firstSegmentSize > 0) firstSegmentSize * segments.size else written
                onProgress(written, estimated, index + 1, segments.size)
            }
            out.flush()
        }

        // 4) verifikasi wadah
        val container = sniff(target)
        if (container == Container.UNKNOWN) {
            target.delete()
            throw IOException("Segmen HLS tidak membentuk file audio yang dikenali (bukan fMP4/TS)")
        }
        Result(written, container)
    }

    // ------------------------------------------------------------------
    // Parsing playlist
    // ------------------------------------------------------------------

    /**
     * Dari master playlist ambil URL media playlist satu varian.
     *
     * Urutan preferensi: rendisi `#EXT-X-MEDIA:TYPE=AUDIO` yang punya `URI`
     * (audio murni — paling hemat dan paling tepat untuk app audio), lalu varian
     * `#EXT-X-STREAM-INF` dengan bandwidth terendah yang masih ≥
     * [MIN_VARIANT_BANDWIDTH], lalu varian termurah apa adanya.
     */
    internal fun pickVariantUrl(playlist: String, baseUrl: String): String? {
        val lines = playlist.lines()

        // rendisi audio murni
        lines.forEach { line ->
            if (!line.startsWith("#EXT-X-MEDIA:")) return@forEach
            if (!line.contains("TYPE=AUDIO")) return@forEach
            val uri = attributeValue(line, "URI") ?: return@forEach
            return resolve(baseUrl, uri)
        }

        data class Variant(val bandwidth: Long, val url: String)
        val variants = ArrayList<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (next.isEmpty() || next.startsWith("#")) continue
            val bandwidth = attributeValue(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
            variants += Variant(bandwidth, resolve(baseUrl, next))
        }
        if (variants.isEmpty()) return null
        val decent = variants.filter { it.bandwidth >= MIN_VARIANT_BANDWIDTH }
        return (decent.minByOrNull { it.bandwidth } ?: variants.minByOrNull { it.bandwidth })?.url
    }

    /** `#EXT-X-MAP:URI="…"` — init segment fMP4 (wajib ditulis sebelum segmen). */
    internal fun parseInitSegment(playlist: String, baseUrl: String): String? =
        playlist.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MAP:") }
            ?.let { attributeValue(it, "URI") }
            ?.let { resolve(baseUrl, it) }

    /** URI segmen: semua baris bukan komentar, bukan tag, bukan kosong. */
    internal fun parseSegments(playlist: String, baseUrl: String): List<String> =
        playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolve(baseUrl, it) }
            .toList()

    /** Nilai atribut ber-tanda kutip (`URI="…"`) atau polos (`BANDWIDTH=123`). */
    private fun attributeValue(line: String, key: String): String? {
        val idx = line.indexOf("$key=")
        if (idx < 0) return null
        var value = line.substring(idx + key.length + 1)
        value = value.substringBefore(',')
        if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        return value.takeIf { it.isNotBlank() }
    }

    /** Selesaikan URI relatif terhadap URL playlist. */
    private fun resolve(baseUrl: String, spec: String): String =
        if (spec.startsWith("http://") || spec.startsWith("https://")) {
            spec
        } else {
            runCatching { java.net.URL(java.net.URL(baseUrl), spec).toString() }
                .getOrDefault(spec)
        }

    // ------------------------------------------------------------------
    // Jaringan & verifikasi
    // ------------------------------------------------------------------

    private fun fetchText(client: OkHttpClient, url: String): String =
        fetchBytes(client, url).toString(Charsets.UTF_8)

    private fun fetchBytes(client: OkHttpClient, url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} saat mengambil $url")
            return resp.body?.bytes() ?: throw IOException("Body kosong dari $url")
        }
    }

    /**
     * Deteksi wadah dari bait pertama: fragmented MP4 punya kotak `ftyp` pada
     * offset 4, MPEG-TS punya sync byte `0x47` tiap 188 bait.
     */
    internal fun sniff(file: File): Container {
        val head = ByteArray(192)
        val read = runCatching {
            file.inputStream().use { it.read(head) }
        }.getOrDefault(-1)
        if (read < 8) return Container.UNKNOWN
        if (head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) {
            return Container.FMP4
        }
        if (head[0] == 0x47.toByte() && (read < 189 || head[188] == 0x47.toByte())) {
            return Container.TS
        }
        return Container.UNKNOWN
    }
}
