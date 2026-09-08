/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt.innertube

import android.util.Log
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.settings.AudioQuality
import com.lyreon.app.yt.LyreonHttp
import com.lyreon.app.yt.ResolvedAudio
import com.lyreon.app.yt.innertube.InnertubeRequest.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback ekstraksi gaya InnerTube — POST langsung ke `youtubei/v1` Google
 * (tanpa NewPipe/Metrolist) untuk stream/player, pencarian, dan playlist.
 *
 * Dipakai sebagai cadangan ("fallback utama") ketika MetrolistExtractor gagal
 * atau mengembalikan stream kosong. Semua akses jaringan on-device langsung ke
 * Google (`youtubei/v1` + CDN `googlevideo.com`) — tanpa server perantara.
 */
/**
 * Kegagalan resolusi stream yang membawa **alasan**, bukan cuma pesan generik.
 *
 * Dibawa naik sampai `PlayerManager` supaya pesan ke pengguna bisa membedakan
 * "YouTube sedang menutup akses anonim (SABR/poToken)" dari "video ini memang
 * diblokir/dihapus" — dua hal yang membutuhkan tindakan berbeda. Lyreon anonim
 * (tanpa cookie), jadi pesan tidak pernah menyuruh pengguna login.
 */
class StreamUnavailableException(
    message: String,
    /** Setidaknya satu klien membalas data SABR tanpa URL. */
    val sabrOnly: Boolean,
    /** Hanya `hlsManifestUrl` yang tersedia. */
    val hlsOnly: Boolean,
    /** Alasan `playabilityStatus` terakhir, mis. "LOGIN_REQUIRED: …". */
    val playability: String,
    /** Ringkasan `klien=VERDICT` dari seluruh percobaan. */
    val attempts: List<String>,
    /** Semua format yang ada terkunci DRM (khas klien TV anonim). */
    val drmOnly: Boolean = false,
    /**
     * Ada klien yang memberi URL, tetapi CDN menolaknya saat di-probe
     * ([StreamUrlValidator]) — 403/410, atau URL itu cuma pratinjau ~1 MiB.
     * Pembeda penting: ini bukan "YouTube tidak memberi stream" melainkan
     * "stream yang diberi tidak bisa dibaca sampai habis".
     */
    val cdnRejected: Boolean = false,
) : IOException(message)

/** Satu percobaan klien dalam laporan [InnertubeFallback.probeBlocking]. */
data class ProbeAttempt(
    val client: String,
    val verdict: String,
    val elapsedMs: Long,
    val detail: String,
)

/**
 * Laporan diagnostik resolusi satu video — dibaca dari Settings → "Tes koneksi".
 * Inilah jawaban untuk "bagian mana persisnya yang perlu di-patch": terlihat
 * klien mana yang masih memberi URL, mana yang sudah SABR-only.
 */
data class LadderProbe(
    val videoId: String,
    val usableClient: String?,
    val audioUrlFound: Boolean,
    /** Klien yang memberi `hlsManifestUrl` (bisa diputar — Lyreon mendukung HLS). */
    val manifestClient: String?,
    val sabrOnly: Boolean,
    val hlsOnly: Boolean,
    val drmOnly: Boolean,
    /**
     * Ada klien yang memberi URL, tetapi CDN menolaknya saat di-probe (403/410) — atau
     * URL itu cuma pratinjau ~1 MiB yang mati di tengah lagu.
     */
    val cdnRejected: Boolean = false,
    /**
     * Klien yang memberi URL namun URL-nya TIDAK lolos validasi. Inilah jawaban untuk
     * laporan "extractor bilang ada 4 stream, tapi pemutar 403": URL-nya ada, isinya
     * tidak bisa dibaca.
     */
    val urlOnlyClient: String? = null,
    /** Dari mana `visitorData` diambil (`sw.js` / `guide` / `response` / `-`). */
    val visitorOrigin: String = "-",
    val totalMs: Long,
    val attempts: List<ProbeAttempt>,
) {
    /** Sukses = ada URL audio yang TERVALIDASI ATAU manifest HLS yang lolos probe. */
    val success: Boolean get() = audioUrlFound || manifestClient != null
}

class InnertubeFallback {

    companion object {
        private const val TAG = "InnertubeFallback"

        /** MIME manifest HLS — dipakai `DefaultMediaSourceFactory` memilih `HlsMediaSource`. */
        const val HLS_MIME = "application/x-mpegurl"

        /** Jumlah respons terblokir beruntun yang memicu penyegaran visitorData. */
        private const val BLOCKED_STREAK_REFRESH = 3
    }

    private val http = LyreonHttp.extractClient
    private val ua = LyreonHttp.USER_AGENT
    private val streamCache = ConcurrentHashMap<String, ResolvedAudio>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Hapus stream yang sudah kadaluwarsa/403 dari cache (dipanggil saat error). */
    fun invalidate(videoId: String) {
        streamCache.remove(videoId)
    }

    /**
     * Buang SEMUA cache stream — dipakai tombol "Tes koneksi" dan pemulihan
     * manual, supaya percobaan berikutnya benar-benar memukul YouTube lagi.
     */
    fun invalidateAll() {
        streamCache.clear()
    }

    /** Pastikan konfigurasi InnerTube sudah di-scrape (idempoten, thread-safe). */
    private fun ensureReady() {
        InnertubeConfig.ensure(http, ua)
        InnertubeConfig.ensureVisitorData(http, ua)
    }

    // ------------------------------------------------------------------
    // Resolusi stream / player — lewat tangga klien (PlayerClientLadder)
    // ------------------------------------------------------------------

    /**
     * Blocking — aman dipanggil dari thread loader ExoPlayer / service unduhan.
     *
     * @param allowManifest false untuk unduhan (`DownloadManager` hanya bisa
     *   menulis file progresif), sehingga manifest HLS tidak pernah dipilih.
     */
    fun resolveAudioBlocking(
        videoId: String,
        quality: AudioQuality,
        allowManifest: Boolean = true,
    ): ResolvedAudio {
        ensureReady()
        val cached = streamCache[videoId]
        if (cached != null && cached.expiresAtMs - 60_000L > System.currentTimeMillis() &&
            (allowManifest || !cached.isManifest)
        ) {
            return cached
        }
        val lock = locks.getOrPut(videoId) { Any() }
        synchronized(lock) {
            streamCache[videoId]?.let { if (allowManifest || !it.isManifest) return it }
            val resolved = fetchPlayer(videoId, quality, allowManifest)
            // Manifest tidak di-cache untuk jalur unduhan supaya permintaan
            // pemutaran berikutnya tetap bisa memakainya dari resolve ulang.
            if (allowManifest || !resolved.isManifest) streamCache[videoId] = resolved
            return resolved
        }
    }

    /**
     * Turun sepanjang tangga klien sampai ada yang memberi audio yang **terbukti** bisa
     * diputar.
     *
     * Dua fase, mengikuti Meld (`YTPlayerUtils.playerResponseForPlayback`) plus satu
     * perluasan Lyreon:
     *
     *  1. **URL langsung** — urutan `STREAM_FALLBACK_CLIENTS` Meld. Setiap URL di-probe
     *     dulu ([StreamUrlValidator]) sebelum diterima; yang ditolak CDN (403/410, atau
     *     ternyata cuma pratinjau ~1 MiB) dicatat `REJECTED_BY_CDN` lalu kita lanjut ke
     *     klien berikutnya. Inilah yang membuat diagnosa lama — extractor melapor
     *     `streams=4` sementara pemutar mati dengan `ERROR_CODE_IO_BAD_HTTP_STATUS` —
     *     tidak lagi lolos sebagai "berhasil".
     *  2. **Manifest HLS** (perluasan Lyreon, bukan dari Meld) — bila tidak ada satu pun
     *     URL langsung yang lolos validasi, klien sumber manifest dicoba. Manifest HLS
     *     tidak menuntut poToken GVS, jadi jalur ini masih hidup ketika seluruh URL
     *     langsung ditutup; Lyreon memutarnya lewat `media3-exoplayer-hls`.
     *
     * Bila keduanya gagal tetapi ada URL langsung yang ditolak CDN, URL itu tetap
     * dikembalikan sebagai **cadangan terakhir** — aturan Meld: pratinjau 1 MiB lebih baik
     * daripada tidak ada stream sama sekali — dengan baris diagnostik yang jujur.
     *
     * Melempar [StreamUnavailableException] bila tidak ada apa pun yang bisa dipakai,
     * dengan alasan yang bisa ditindaklanjuti (SABR-only / DRM / CDN / playability).
     */
    private fun fetchPlayer(
        videoId: String,
        quality: AudioQuality,
        allowManifest: Boolean = true,
    ): ResolvedAudio {
        var visitor = InnertubeConfig.visitor()
        val webVersion = InnertubeConfig.webClientVersion()
        val sts = InnertubeConfig.signatureTimestamp()

        // Snapshot urutan sekali: cooldown bisa berubah di tengah loop dan kita ingin satu
        // lintasan yang konsisten. `forPlayback = true` menyisakan klien yang bisa jalan
        // anonim saja (tanpa login, tanpa poToken) — persis `loginRequired`-skip di Meld.
        val ladder = PlayerClientLadder.ordered(forPlayback = true)
        val hlsLadder = PlayerClientLadder.hlsSpecs().filter { h -> ladder.none { it.key == h.key } }
        val attempts = ArrayList<String>(ladder.size + hlsLadder.size)
        var sawSabr = false
        var sawHls = false
        var sawDrm = false
        var sawCdnReject = false
        var playability = ""
        // Respons ditolak beruntun (LOGIN_REQUIRED bot gate / UNPLAYABLE / transport error)
        // sering berarti visitorData basi atau tidak cocok dengan sesi yang memeriksa.
        // Setelah tiga kali, ambil visitorData baru — satu permintaan ringan yang bisa
        // menyelamatkan seluruh sisa tangga.
        var blockedStreak = 0
        /** Kandidat manifest HLS (klien → url), dikumpulkan di fase 1 maupun fase 2. */
        val hlsCandidates = ArrayList<Pair<String, String>>()
        /** URL langsung terbaik yang ditolak CDN — cadangan terakhir. */
        var lastResort: ResolvedAudio? = null
        var lastResortClient: String? = null

        if (visitor == null) {
            PlayerClientLadder.push(
                "visitorData TIDAK ADA — VISIONOS & ANDROID_VR 1.65.10 rawan menjawab LOGIN_REQUIRED",
            )
            Log.w(TAG, "resolve $videoId tanpa visitorData — tangga klien rawan bot-gate")
        }

        // ---------------- Fase 1: URL langsung, terurut & tervalidasi ---------
        for (spec in ladder) {
            val startedAt = System.currentTimeMillis()
            val attempt = attemptClient(spec, videoId, visitor, webVersion, sts)
            // Respons yang lolos bisa membawa visitorData sendiri — panen supaya sisa
            // tangga tidak lagi berjalan tanpa identitas sesi.
            if (visitor == null && InnertubeConfig.adoptVisitor(attempt.root)) {
                visitor = InnertubeConfig.visitor()
                PlayerClientLadder.push("visitorData diadopsi dari responseContext '${spec.key}'")
            }

            var verdict = attempt.verdict
            var detail = attempt.detail
            var picked: ResolvedAudio? = null
            val streamingData = attempt.root?.optJSONObject("streamingData")
            val hlsUrl = PlayerClientLadder.hlsManifest(attempt.root)
            if (hlsUrl != null) {
                sawHls = true
                if (allowManifest && hlsCandidates.none { it.second == hlsUrl }) {
                    hlsCandidates += spec.key to hlsUrl
                }
            }

            when (verdict) {
                ClientVerdict.USABLE -> {
                    val progressive = streamingData?.let { pickAudio(it, quality) }
                    if (progressive == null) {
                        verdict = ClientVerdict.NO_STREAMING_DATA
                        detail = "tanpa format audio yang bisa dipakai"
                    } else {
                        // VALIDASI: HEAD ber-Range ke byte terakhir file. Menolak URL
                        // pratinjau ~1 MiB yang akan mematikan lagu di detik ke-60.
                        val probe = StreamUrlValidator.validate(
                            url = progressive.url,
                            contentLength = progressive.contentLength,
                            label = "klien=${spec.key} bitrate=${progressive.bitrateKbps}kbps",
                            userAgent = spec.userAgent,
                        )
                        if (probe.accepted) {
                            picked = progressive.copy(videoId = videoId, clientKey = spec.key)
                            detail = "URL valid · HTTP ${probe.short()} ${probe.range}"
                        } else {
                            sawCdnReject = true
                            verdict = ClientVerdict.REJECTED_BY_CDN
                            detail = "CDN ${probe.code} pada ${probe.range} · " +
                                StreamUrlValidator.describe(progressive.url)
                            lastResort = progressive.copy(videoId = videoId, clientKey = spec.key)
                            lastResortClient = spec.key
                        }
                    }
                }
                ClientVerdict.HLS_ONLY -> {
                    // Jangan langsung pulang: klien berikutnya mungkin memberi URL audio
                    // langsung yang lebih hemat kuota daripada HLS muxed.
                    detail = if (hlsUrl != null) "manifest HLS ada" else detail
                }
                ClientVerdict.SABR_ONLY -> sawSabr = true
                ClientVerdict.DRM_ONLY -> sawDrm = true
                ClientVerdict.PLAYABILITY_BLOCKED -> playability = detail
                else -> Unit
            }

            val elapsed = System.currentTimeMillis() - startedAt
            attempts += "${spec.key}=${verdict.name}"
            PlayerClientLadder.note(spec.key, verdict, elapsed, detail)

            blockedStreak = when (verdict) {
                ClientVerdict.PLAYABILITY_BLOCKED, ClientVerdict.TRANSPORT_ERROR,
                ClientVerdict.INVALID_RESPONSE -> blockedStreak + 1
                else -> 0
            }
            if (blockedStreak == BLOCKED_STREAK_REFRESH) {
                InnertubeConfig.refreshVisitor(http, ua)
                visitor = InnertubeConfig.visitor()
                PlayerClientLadder.push(
                    "$blockedStreak klien ditolak beruntun → visitorData disegarkan " +
                        "(sumber: ${InnertubeConfig.visitorOrigin()})",
                )
                Log.w(TAG, "$blockedStreak respons terblokir beruntun untuk $videoId — visitorData disegarkan")
            }

            if (picked != null) {
                PlayerClientLadder.push("audio OK via '${spec.key}' (${elapsed}ms) · $detail")
                Log.i(TAG, "resolve $videoId OK via klien '${spec.key}' dalam ${elapsed}ms")
                return picked
            }
        }

        // ---------------- Fase 2: manifest HLS --------------------------------
        if (allowManifest) {
            for (spec in hlsLadder) {
                val startedAt = System.currentTimeMillis()
                val attempt = attemptClient(spec, videoId, visitor, webVersion, sts)
                val elapsed = System.currentTimeMillis() - startedAt
                val hlsUrl = PlayerClientLadder.hlsManifest(attempt.root)
                if (hlsUrl != null) {
                    sawHls = true
                    if (hlsCandidates.none { it.second == hlsUrl }) hlsCandidates += spec.key to hlsUrl
                }
                val detail = if (hlsUrl != null) "manifest HLS ada" else attempt.detail
                attempts += "${spec.key}=${attempt.verdict.name}(hls)"
                PlayerClientLadder.note(spec.key, attempt.verdict, elapsed, detail)
                if (attempt.verdict == ClientVerdict.SABR_ONLY) sawSabr = true
                if (attempt.verdict == ClientVerdict.DRM_ONLY) sawDrm = true
                if (attempt.verdict == ClientVerdict.PLAYABILITY_BLOCKED) playability = detail
            }
            for ((clientKey, url) in hlsCandidates) {
                val probe = StreamUrlValidator.validateManifest(
                    url = url,
                    label = "klien=$clientKey",
                    userAgent = PlayerClientLadder.specOf(clientKey)?.userAgent,
                )
                if (probe.accepted) {
                    PlayerClientLadder.push(
                        "audio OK via '$clientKey' · manifest HLS (HTTP ${probe.short()}) — jalur kedua",
                    )
                    Log.i(TAG, "resolve $videoId lewat manifest HLS dari klien '$clientKey'")
                    return manifestAudio(videoId, url, null).copy(clientKey = clientKey)
                }
                sawCdnReject = true
                attempts += "$clientKey=MANIFEST_REJECTED(${probe.code})"
            }
        }

        // ---------------- Cadangan terakhir (aturan Meld) ---------------------
        // Tidak ada URL langsung yang lolos validasi dan tidak ada manifest yang hidup.
        // Pratinjau ~1 MiB tetap dikembalikan: pengguna mendengar awal lagu alih-alih
        // error total, dan `ResolvingDataSource` masih punya retry + invalidasi cache.
        lastResort?.let { resort ->
            PlayerClientLadder.push(
                "memakai URL '$lastResortClient' TANPA validasi (semua jalur lain gagal) — " +
                    "lagu bisa berhenti di tengah",
            )
            Log.w(
                TAG,
                "resolve $videoId memakai URL yang DITOLAK CDN dari '$lastResortClient' " +
                    "sebagai cadangan terakhir",
            )
            return resort.copy(validated = false)
        }

        val reason = buildString {
            append("InnerTube: tidak ada klien anonim yang memberi stream audio untuk $videoId")
            if (sawCdnReject) append(" · URL ditolak CDN (403/pratinjau)")
            if (sawSabr) append(" · SABR-only terdeteksi")
            if (sawDrm) append(" · format DRM")
            if (sawHls) append(" · hanya HLS")
            if (playability.isNotBlank()) append(" · $playability")
            if (InnertubeConfig.visitor() == null) append(" · TANPA visitorData")
        }
        Log.w(TAG, "$reason (percobaan: ${attempts.joinToString()})")
        throw StreamUnavailableException(
            message = reason,
            sabrOnly = sawSabr,
            hlsOnly = sawHls,
            playability = playability,
            attempts = attempts,
            drmOnly = sawDrm,
            cdnRejected = sawCdnReject,
        )
    }

    /**
     * Manifest HLS sebagai [ResolvedAudio]. `media3-exoplayer-hls` memutarnya;
     * `PlayerManager` menukar `MediaItem` ke URL ini + mimeType m3u8.
     *
     * @param progressiveFallback URL audio langsung (bila ada) sebagai cadangan
     *   saat manifest kedaluwarsa — dipakai `ResolvingDataSource` seperti
     *   kandidat format kedua.
     */
    private fun manifestAudio(
        videoId: String,
        hlsUrl: String,
        progressiveFallback: String?,
    ): ResolvedAudio = ResolvedAudio(
        videoId = videoId,
        url = hlsUrl,
        mimeType = HLS_MIME,
        suffix = "m3u8",
        bitrateKbps = 0,
        expiresAtMs = parseExpire(hlsUrl),
        fallbackUrl = progressiveFallback?.takeUnless { it.isBlank() || it == hlsUrl },
        isManifest = true,
    )

    private class ClientAttempt(
        val verdict: ClientVerdict,
        val root: JSONObject?,
        val detail: String,
    )

    /**
     * Kirim satu request player, dengan **satu** percobaan ulang ke host cadangan.
     *
     * Host cadangan hanya dicoba untuk kegagalan TRANSPORT (HTTP 4xx/5xx, body kosong,
     * body bukan JSON). Respons yang menolak lewat `playabilityStatus` TIDAK diulang ke
     * host lain: itu keputusan server atas klien+video, bukan atas host — mengulangnya
     * hanya menggandakan latensi per lagu.
     */
    private fun attemptClient(
        spec: PlayerClientSpec,
        videoId: String,
        visitor: String?,
        webVersion: String,
        sts: Int?,
    ): ClientAttempt {
        val first = executePlayer(
            InnertubeRequest.playerFromSpec(spec, videoId, visitor, webVersion, sts),
            videoId,
        )
        if (first.verdict != ClientVerdict.TRANSPORT_ERROR) return first
        val alt = InnertubeRequest.playerFromSpecAltHost(spec, videoId, visitor, webVersion, sts)
            ?: return first
        val second = executePlayer(alt, videoId)
        if (second.verdict != ClientVerdict.TRANSPORT_ERROR) return second
        return ClientAttempt(
            ClientVerdict.TRANSPORT_ERROR,
            null,
            "${spec.host}: ${first.detail} / ${spec.altHost}: ${second.detail}",
        )
    }

    private fun executePlayer(request: okhttp3.Request, videoId: String): ClientAttempt {
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ClientAttempt(ClientVerdict.TRANSPORT_ERROR, null, "HTTP ${resp.code}")
                } else {
                    val body = resp.body?.string().orEmpty()
                    val root = if (body.isBlank()) null else runCatching { JSONObject(body) }.getOrNull()
                    when {
                        root == null -> ClientAttempt(
                            ClientVerdict.TRANSPORT_ERROR,
                            null,
                            if (body.isBlank()) "body kosong" else "body bukan JSON",
                        )
                        else -> {
                            val verdict = PlayerClientLadder.inspect(root, videoId)
                            val detail = if (verdict == ClientVerdict.PLAYABILITY_BLOCKED) {
                                PlayerClientLadder.playabilityReason(root)
                            } else {
                                ""
                            }
                            ClientAttempt(verdict, root, detail)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ClientAttempt(ClientVerdict.TRANSPORT_ERROR, null, e.javaClass.simpleName)
        }
    }

    /**
     * Jalankan seluruh tangga klien untuk satu video dan laporkan hasilnya —
     * dipakai tombol "Tes koneksi" di Settings. Tidak memakai cache supaya
     * hasilnya selalu kondisi jaringan saat ini.
     */
    fun probeBlocking(videoId: String, quality: AudioQuality): LadderProbe {
        ensureReady()
        val startedAt = System.currentTimeMillis()
        var visitor = InnertubeConfig.visitor()
        val webVersion = InnertubeConfig.webClientVersion()
        val sts = InnertubeConfig.signatureTimestamp()

        // Diagnostik menjalankan SELURUH klien (termasuk yang butuh poToken/login dan yang
        // sudah mati) supaya pergeseran kebijakan YouTube terlihat, plus klien pembanding
        // (ANDROID_VR 1.61.48) yang membuktikan gate terjadi per VERSI klien.
        val ladder = PlayerClientLadder.ordered(forPlayback = false)
        val attempts = ArrayList<ProbeAttempt>(ladder.size)
        /** Klien yang memberi URL — belum tentu URL itu bisa dibaca sampai habis. */
        var urlClient: String? = null
        /** Klien yang URL-nya LOLOS probe CDN (inilah yang benar-benar bisa diputar). */
        var validatedClient: String? = null
        /** Klien yang manifest HLS-nya lolos probe. */
        var manifestClient: String? = null
        var audioFound = false
        var sawSabr = false
        var sawHls = false
        var sawDrm = false
        var sawCdnReject = false
        val validatedManifests = HashSet<String>()

        for (spec in ladder) {
            val clientStartedAt = System.currentTimeMillis()
            val attempt = attemptClient(spec, videoId, visitor, webVersion, sts)
            if (visitor == null && InnertubeConfig.adoptVisitor(attempt.root)) {
                visitor = InnertubeConfig.visitor()
            }
            val elapsed = System.currentTimeMillis() - clientStartedAt
            val hlsUrl = PlayerClientLadder.hlsManifest(attempt.root)
            var verdict = attempt.verdict
            var detail = attempt.detail

            if (hlsUrl != null) sawHls = true

            when (verdict) {
                ClientVerdict.SABR_ONLY -> sawSabr = true
                ClientVerdict.DRM_ONLY -> {
                    sawDrm = true
                    detail = "semua format ber-DRM"
                }
                ClientVerdict.HLS_ONLY -> detail = "hanya manifest HLS"
                ClientVerdict.USABLE -> {
                    urlClient = urlClient ?: spec.key
                    val streamingData = attempt.root?.optJSONObject("streamingData")
                    val picked = streamingData?.let { pickAudio(it, quality) }
                    if (picked == null) {
                        verdict = ClientVerdict.NO_STREAMING_DATA
                        detail = "URL ada tapi tanpa format audio yang bisa dipakai"
                    } else {
                        // Pembeda yang dulu tidak ada: URL diverifikasi dengan probe byte
                        // terakhir, jadi "USABLE" tidak lagi berarti sekadar "ada URL".
                        val probe = StreamUrlValidator.validate(
                            url = picked.url,
                            contentLength = picked.contentLength,
                            label = "tes-koneksi klien=${spec.key}",
                            userAgent = spec.userAgent,
                        )
                        if (probe.accepted) {
                            audioFound = true
                            validatedClient = validatedClient ?: spec.key
                            detail = "URL VALID · HTTP ${probe.short()} ${probe.range} · " +
                                StreamUrlValidator.describe(picked.url)
                        } else {
                            sawCdnReject = true
                            verdict = ClientVerdict.REJECTED_BY_CDN
                            detail = "URL ada tapi CDN ${probe.code} pada ${probe.range} · " +
                                StreamUrlValidator.describe(picked.url)
                        }
                    }
                }
                else -> Unit
            }

            // Manifest HLS juga diuji (tanpa Range) supaya laporan tidak mengklaim jalur
            // HLS hidup padahal manifest-nya 403.
            if (hlsUrl != null && (verdict == ClientVerdict.HLS_ONLY || verdict == ClientVerdict.USABLE ||
                    verdict == ClientVerdict.DRM_ONLY || verdict == ClientVerdict.REJECTED_BY_CDN)
            ) {
                val already = validatedManifests.contains(hlsUrl)
                val probe = if (already) {
                    null
                } else {
                    StreamUrlValidator.validateManifest(hlsUrl, "tes-koneksi klien=${spec.key}", spec.userAgent)
                }
                val accepted = already || probe?.accepted == true
                if (accepted) {
                    validatedManifests += hlsUrl
                    manifestClient = manifestClient ?: spec.key
                } else if (probe != null) {
                    sawCdnReject = true
                }
                detail += if (accepted) " · HLS valid" else " · HLS ditolak ${probe?.code}"
            }

            attempts += ProbeAttempt(spec.key, verdict.name, elapsed, detail)
            PlayerClientLadder.note(spec.key, verdict, elapsed, detail)
        }

        return LadderProbe(
            videoId = videoId,
            usableClient = validatedClient ?: urlClient,
            audioUrlFound = audioFound,
            manifestClient = manifestClient,
            sabrOnly = sawSabr,
            hlsOnly = sawHls,
            drmOnly = sawDrm,
            cdnRejected = sawCdnReject,
            urlOnlyClient = urlClient?.takeIf { validatedClient == null },
            visitorOrigin = InnertubeConfig.visitorOrigin(),
            totalMs = System.currentTimeMillis() - startedAt,
            attempts = attempts,
        )
    }

    private fun pickAudio(sd: JSONObject, quality: AudioQuality): ResolvedAudio? {
        val formats = sd.optJSONArray("adaptiveFormats") ?: sd.optJSONArray("formats") ?: return null
        val baseJs = InnertubeConfig.baseJsUrl()?.let { fetchBaseJs(it) }

        data class Candidate(
            val url: String,
            val mime: String,
            val suffix: String,
            val bitrate: Int,
            /** Panjang file sebenarnya — dipakai probe byte terakhir ([StreamUrlValidator]). */
            val contentLength: Long,
            /** Loudness referensi YouTube untuk format ini (dB), bila ada. */
            val loudnessDb: Float?,
        )

        val candidates = ArrayList<Candidate>(formats.length())
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType").orEmpty()
            if (!mime.startsWith("audio/")) continue
            // Format ber-DRM tidak bisa diputar tanpa lisensi Widevine.
            if (PlayerClientLadder.isDrmLocked(f)) continue
            // Lewati audio hasil dubbing otomatis — bukan audio asli lagunya.
            // (Meld memfilter ini lewat `Format.isOriginal`.)
            val audioTrack = f.optJSONObject("audioTrack")
            if (audioTrack != null && audioTrack.optBoolean("audioIsAutoDubbed", false)) continue
            var url = f.optString("url").orEmpty()
            if (url.isBlank()) {
                val cipher = f.optString("signatureCipher").ifBlank { f.optString("cipher") }
                url = resolveCipher(cipher, baseJs) ?: continue
            }
            if (url.isBlank()) continue
            val bitrate = f.optInt("bitrate", 0)
            // `contentLength` bisa datang sebagai angka atau string; `optString`
            // menangani keduanya. Bila tak ada, probe memakai `clen=` di URL.
            val contentLength = f.optString("contentLength").toLongOrNull() ?: 0L
            // YouTube menaruh loudness referensi di format audio; `loudnessDb` lebih
            // dulu, `perceptualLoudnessDb` sebagai cadangan (urutan yang sama dengan
            // yang dipakai Meld/Metrolist).
            val loudnessDb = ((f.opt("loudnessDb") ?: f.opt("perceptualLoudnessDb")) as? Number)?.toFloat()
            candidates.add(
                Candidate(
                    url = url,
                    mime = mime,
                    suffix = suffixOf(mime),
                    bitrate = bitrate,
                    contentLength = contentLength,
                    loudnessDb = loudnessDb,
                ),
            )
        }
        if (candidates.isEmpty()) return null

        fun bitrateOf(c: Candidate) = if (c.bitrate > 0) c.bitrate else -1
        val chosen = when (quality) {
            AudioQuality.HIGH -> candidates.filter { bitrateOf(it) > 0 }
                .maxWithOrNull(compareBy<Candidate> { bitrateOf(it) }.thenBy { if (it.suffix == "m4a") 1 else 0 })
            AudioQuality.DATA_SAVER -> candidates.filter { bitrateOf(it) > 0 }
                .minWithOrNull(compareBy<Candidate> { bitrateOf(it) }.thenBy { if (it.suffix == "m4a") 0 else 1 })
            AudioQuality.BALANCED -> candidates.filter { bitrateOf(it) > 0 }
                .minWithOrNull(compareBy<Candidate> { kotlin.math.abs(it.bitrate - 128_000) }
                    .thenBy { if (it.suffix == "m4a") -1 else 0 })
        } ?: candidates.first()

        val bitrateKbps = (chosen.bitrate.takeIf { it > 0 } ?: 128_000) / 1000
        val fallbackUrl = candidates
            .filter { it !== chosen && it.suffix != chosen.suffix }
            .minByOrNull { kotlin.math.abs(it.bitrate - chosen.bitrate) }
            ?.url
            ?.takeUnless { it.isBlank() || it == chosen.url }

        return ResolvedAudio(
            videoId = "", // diisi pemanggil bila perlu
            url = chosen.url,
            mimeType = chosen.mime,
            suffix = chosen.suffix,
            bitrateKbps = bitrateKbps,
            expiresAtMs = parseExpire(chosen.url),
            fallbackUrl = fallbackUrl,
            contentLength = chosen.contentLength,
            loudnessDb = chosen.loudnessDb,
        )
    }

    /** Menyelesaikan `signatureCipher`/`cipher` menjadi URL final. */
    private fun resolveCipher(cipher: String, baseJs: String?): String? {
        if (cipher.isBlank()) return null
        val params = parseQuery(cipher)
        val url = params["url"] ?: return null
        var s = params["s"] ?: return url
        val sp = params["sp"] ?: "sig"
        if (baseJs != null) {
            s = SignatureDecipher.decipherS(baseJs, s) ?: return null
        }
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$sp=${java.net.URLEncoder.encode(s, "UTF-8")}"
    }

    private fun suffixOf(mime: String): String = when {
        mime.contains("opus") || mime.contains("webm") -> "webm"
        mime.contains("m4a") || mime.contains("mp4") || mime.contains("aac") -> "m4a"
        mime.contains("mp3") -> "mp3"
        else -> "m4a"
    }

    private fun parseExpire(url: String): Long {
        val exp = Regex("[?&]expire=(\\d{9,})").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (exp != null && exp > 0) exp * 1000L
        else System.currentTimeMillis() + 5L * 60L * 60L * 1000L
    }

    private fun parseQuery(qs: String): Map<String, String> {
        val map = HashMap<String, String>()
        val body = qs.substringAfter("?", qs)
        body.split("&").forEach {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) map[kv[0]] = java.net.URLDecoder.decode(kv[1], "UTF-8")
        }
        return map
    }

    // "" = sentinel gagal (ConcurrentHashMap tak boleh menyimpan null).
    private val baseJsCache = ConcurrentHashMap<String, String>()
    private fun fetchBaseJs(url: String): String? =
        baseJsCache.getOrPut(url) {
            runCatching {
                http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", ua).get().build())
                    .execute().use { it.body?.string() }
            }.getOrNull().orEmpty()
        }.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------
    // Rekomendasi / radio (endpoint /next — watch next)
    // ------------------------------------------------------------------

    /** Lagu terkait ("watch next") untuk radio — fallback bila Metrolist gagal. */
    suspend fun related(videoId: String, limit: Int = 25): List<LyreonTrack> =
        withContext(Dispatchers.IO) {
            ensureReady()
            runCatching {
                val req = InnertubeRequest.next(
                    Client.WEB,
                    InnertubeConfig.webClientVersion(),
                    videoId,
                    InnertubeConfig.visitor(),
                )
                val root = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    JSONObject(resp.body?.string().orEmpty())
                }
                parseRelated(root, limit)
            }.getOrDefault(emptyList())
        }

    private fun parseRelated(root: JSONObject, limit: Int): List<LyreonTrack> {
        val out = ArrayList<LyreonTrack>(limit)
        val results = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnWatchNextResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONArray("results") ?: return out

        fun textOf(node: JSONObject?, key: String): String {
            if (node == null) return ""
            val runs = node.optJSONArray("runs") ?: return node.optString("simpleText")
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString()
        }

        for (i in 0 until results.length()) {
            val vr = results.optJSONObject(i)?.optJSONObject("compactVideoRenderer") ?: continue
            val vid = vr.optString("videoId").orEmpty()
            if (vid.isBlank() || vid.length !in 6..20) continue
            val title = textOf(vr.optJSONObject("title"), "text").ifBlank { continue }
            val thumb = vr.optJSONObject("thumbnail")?.optJSONObject("thumbnails")
                ?.optJSONArray("thumbnails")
                ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
                .orEmpty()
            val length = textOf(vr.optJSONObject("lengthText"), "simpleText")
            val duration = parseDuration(length)
            val artist = vr.optJSONObject("longBylineText")?.let { textOf(it, "runs") }.orEmpty()
            out += LyreonTrack(vid, title, artist, durationSec = duration, thumbnailUrl = thumb)
            if (out.size >= limit) return out
        }
        return out
    }

    // ------------------------------------------------------------------
    // Pencarian
    // ------------------------------------------------------------------

    suspend fun search(query: String, filter: com.lyreon.app.data.model.SearchFilter, limit: Int = 30): List<LyreonTrack> =
        withContext(Dispatchers.IO) {
            ensureReady()
            runCatching {
                val params = searchParams(filter)
                val req = InnertubeRequest.search(Client.WEB, InnertubeConfig.webClientVersion(), query, params, InnertubeConfig.visitor())
                val root = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    JSONObject(resp.body?.string().orEmpty())
                }
                parseSearch(root, limit)
            }.getOrDefault(emptyList())
        }

    /**
     * Parameter filter pencarian InnerTube.
     *
     * Nilai disalin byte-per-byte dari `YouTube.kt` Metrolist
     * (`SearchFilter.FILTER_*`) — sebelumnya ALBUMS dan PLAYLISTS memakai
     * parameter VIDEO sehingga kedua filter itu mengembalikan video, bukan
     * album/playlist. Base64 ini adalah protobuf filter, jadi tidak bisa
     * "diperkirakan": salah satu byte = hasil salah jenis.
     */
    private fun searchParams(filter: com.lyreon.app.data.model.SearchFilter): String? = when (filter) {
        com.lyreon.app.data.model.SearchFilter.SONGS -> "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        com.lyreon.app.data.model.SearchFilter.VIDEOS -> "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        com.lyreon.app.data.model.SearchFilter.ALBUMS -> "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        com.lyreon.app.data.model.SearchFilter.ARTISTS -> "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        com.lyreon.app.data.model.SearchFilter.PLAYLISTS -> "EgeKAQQoAEABagoQAxAEEAoQCRAF"
        com.lyreon.app.data.model.SearchFilter.PODCASTS -> "EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D"
        com.lyreon.app.data.model.SearchFilter.ALL -> null
    }

    private fun parseSearch(root: JSONObject, limit: Int): List<LyreonTrack> {
        val out = ArrayList<LyreonTrack>(limit)
        val contents = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return out

        fun textOf(node: JSONObject?, key: String): String {
            if (node == null) return ""
            val runs = node.optJSONArray("runs") ?: return node.optString("simpleText")
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString()
        }

        for (s in 0 until contents.length()) {
            val itemSection = contents.optJSONObject(s)
                ?.optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: continue
            for (c in 0 until itemSection.length()) {
                val vr = itemSection.optJSONObject(c)?.optJSONObject("videoRenderer") ?: continue
                val videoId = vr.optString("videoId").orEmpty()
                if (videoId.isBlank() || videoId.length !in 6..20) continue
                val title = textOf(vr.optJSONObject("title"), "text").ifBlank { continue }
                val thumb = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
                    .orEmpty()
                val duration = parseDuration(textOf(vr.optJSONObject("lengthText"), "simpleText"))
                val artist = vr.optJSONObject("ownerText")?.let { textOf(it, "runs") }.orEmpty()
                out += LyreonTrack(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    durationSec = duration,
                    thumbnailUrl = thumb,
                )
                if (out.size >= limit) return out
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Playlist
    // ------------------------------------------------------------------

    suspend fun playlist(playlistId: String, limit: Int = 200): List<LyreonTrack> =
        withContext(Dispatchers.IO) {
            ensureReady()
            runCatching {
                val req = InnertubeRequest.playlist(Client.WEB, InnertubeConfig.webClientVersion(), playlistId, InnertubeConfig.visitor())
                val root = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    JSONObject(resp.body?.string().orEmpty())
                }
                parsePlaylist(root, limit)
            }.getOrDefault(emptyList())
        }

    private fun parsePlaylist(root: JSONObject, limit: Int): List<LyreonTrack> {
        val out = ArrayList<LyreonTrack>(limit)
        val contents = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return out

        fun textOf(node: JSONObject?, key: String): String {
            if (node == null) return ""
            val runs = node.optJSONArray("runs") ?: return node.optString("simpleText")
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString()
        }

        for (t in 0 until contents.length()) {
            val sections = contents.optJSONObject(t)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue
            for (s in 0 until sections.length()) {
                val items = sections.optJSONObject(s)
                    ?.optJSONObject("musicPlaylistShelfRenderer")
                    ?.optJSONArray("contents") ?: sections.optJSONObject(s)
                    ?.optJSONObject("playlistVideoListRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (i in 0 until items.length()) {
                    val row = items.optJSONObject(i) ?: continue
                    val vr = row.optJSONObject("playlistVideoRenderer") ?: continue
                    val videoId = vr.optString("videoId").orEmpty()
                    if (videoId.isBlank() || videoId.length !in 6..20) continue
                    val title = textOf(vr.optJSONObject("title"), "text").ifBlank { continue }
                    val thumb = vr.optJSONObject("thumbnail")?.optJSONObject("thumbnails")
                        ?.optJSONArray("thumbnails")
                        ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
                        .orEmpty()
                    val length = vr.optJSONObject("lengthSeconds")?.optString("simpleText").orEmpty()
                        .takeIf { it.isNotBlank() } ?: textOf(vr.optJSONObject("lengthText"), "simpleText")
                    val duration = length.toLongOrNull() ?: parseDuration(length)
                    val artist = vr.optJSONObject("shortBylineText")?.let { textOf(it, "runs") }.orEmpty()
                    out += LyreonTrack(videoId, title, artist, durationSec = duration, thumbnailUrl = thumb)
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private fun parseDuration(raw: String): Long {
        if (raw.isBlank()) return 0L
        val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    /** Id playlist dari URL (watch?list= / playlist?list=). */
    fun playlistIdOf(url: String): String =
        Regex("[?&]list=([a-zA-Z0-9_-]{6,})").find(url)?.groupValues?.get(1)
            ?: url.substringAfter("/playlist?list=").substringBefore("&")
            ?: url.substringAfter("list=").substringBefore("&")

    fun isPlaylistUrl(url: String): Boolean =
        url.contains("youtube.com") && (url.contains("list=") || url.contains("/playlist"))
}
