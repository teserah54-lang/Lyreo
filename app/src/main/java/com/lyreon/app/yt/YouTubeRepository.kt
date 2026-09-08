/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt

import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.SearchFilter
import com.lyreon.app.data.model.YtPlaylist
import android.util.Log
import com.lyreon.app.data.settings.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.search.filter.FilterItem
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeTrendingExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Klien HTTP bersama: satu untuk ekstraksi NewPipe, satu untuk streaming audio. */
object LyreonHttp {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val extractClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val orig = chain.request()
                val url = orig.url
                val reqBuilder = orig.newBuilder()

                if (url.host.endsWith("googlevideo.com")) {
                    // CDN mencocokkan User-Agent request dengan klien yang MENCETAK URL
                    // (parameter `c=` + `cver=`). UA yang melenceng adalah penyebab klasik
                    // 403 padahal URL-nya sah — dan Lyreon dulu menebak dari `c=` saja lalu
                    // memasang UA app lawas (untuk `c=ANDROID_VR` yang terpasang justru
                    // `com.google.android.youtube/19.45.38`: nama paket salah, versi salah).
                    // Sekarang UA diambil dari spec yang sama dengan tangga klien, sehingga
                    // request pemutaran identik dengan probe `StreamUrlValidator`.
                    val specUa = com.lyreon.app.yt.innertube.PlayerClientLadder
                        .streamUserAgentFor(url.queryParameter("c"), url.queryParameter("cver"))
                    if (specUa != null) {
                        reqBuilder.header("User-Agent", specUa)
                        // Samakan dengan probe: tanpa Origin/Referer.
                        reqBuilder.removeHeader("Referer")
                        reqBuilder.removeHeader("Origin")
                    } else if (url.queryParameter("sabr") != null) {
                        // URL SABR: tidak bisa di-GET biasa. Biarkan apa adanya supaya
                        // errornya terbaca, jangan dipakaikan UA web.
                        reqBuilder.header("User-Agent", USER_AGENT)
                    } else {
                        reqBuilder.header("User-Agent", USER_AGENT)
                        reqBuilder.header("Referer", "https://www.youtube.com/")
                        reqBuilder.header("Origin", "https://www.youtube.com")
                    }
                } else {
                    if (orig.header("User-Agent") == null) {
                        reqBuilder.header("User-Agent", USER_AGENT)
                    }
                }
                chain.proceed(reqBuilder.build())
            }
            .build()
    }
}

/** Stream audio terpilih untuk satu videoId (dipakai player & downloader). */
data class ResolvedAudio(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val suffix: String,
    val bitrateKbps: Int,
    val expiresAtMs: Long,
    /**
     * Kandidat stream ke-2 dari keluarga format berbeda (m4a ↔ webm/opus).
     * Jalur penandatanganan tiap format kerap terpisah, sehingga saat URL
     * utama 403/tak tersedia, kandidat ini sering masih hidup ("stream lama"
     * sebagai cadangan otomatis, tanpa intervensi pengguna).
     */
    val fallbackUrl: String? = null,
    /**
     * True bila [url] adalah **manifest HLS** (m3u8), bukan file audio progresif.
     * Jalur ini dipakai saat klien anonim hanya diberi `hlsManifestUrl` — manifest
     * HLS tidak menuntut poToken GVS, berbeda dari URL langsung. Player menukar
     * `MediaItem` ke URL ini dengan [mimeType] m3u8 supaya `media3-exoplayer-hls`
     * yang memutarnya.
     */
    val isManifest: Boolean = false,
    /**
     * Panjang file dalam byte (dari `adaptiveFormats[].contentLength`, atau `clen=`
     * di URL). Dipakai [com.lyreon.app.yt.innertube.StreamUrlValidator] untuk
     * mem-probe **byte terakhir** file — satu-satunya cara mendeteksi URL yang
     * sebenarnya cuma pratinjau ~1 MiB dan akan 403 di tengah lagu.
     */
    val contentLength: Long = 0L,
    /**
     * Loudness referensi dari YouTube (`loudnessDb`, atau `perceptualLoudnessDb`
     * bila itu yang ada) untuk format audio terpilih, dalam dB. Dipakai normalisasi
     * volume per lagu: gain = -loudnessDb (dijepit) lewat `LoudnessEnhancer`.
     * `null` = YouTube tidak memberi data untuk klien/format ini.
     */
    val loudnessDb: Float? = null,
    /** Kunci klien InnerTube yang mencetak URL ini (`visionos`, `android_vr`, …). */
    val clientKey: String = "",
    /**
     * False HANYA untuk jalur cadangan terakhir: semua URL ditolak CDN dan tidak ada
     * manifest yang hidup, jadi URL yang ditolak itu tetap diserahkan ke pemutar
     * (aturan Meld: pratinjau ~1 MiB lebih baik daripada tidak ada stream). Pemutaran
     * mungkin berhenti di tengah lagu; **unduhan menolak** memakai URL seperti ini
     * supaya tidak menyimpan file terpotong diam-diam.
     */
    val validated: Boolean = true,
)

data class YtTrackBundle(
    val track: LyreonTrack,
    val related: List<LyreonTrack>,
    /** Sinyal konteks benih — dipanen dari halaman tontonan YouTube: */
    val seedTags: List<String> = emptyList(),          // tagar resmi video (#speedup)
    val seedHashtags: List<String> = emptyList(),      // tagar di teks deskripsi (#sadvibes)
    val seedCategory: String = "",                     // kategori YouTube (Music, dll)
)

/** Sesi pencarian stateful — halaman berikutnya dilanjutkan dari objek ini. */
class YtSearchSession internal constructor(
    private val extractor: SearchExtractor,
) {
    private var nextPage: Page? = null

    var hasNext: Boolean = true
        private set

    suspend fun first(): List<InfoItem> = withContext(Dispatchers.IO) {
        extractor.fetchPage()
        val page = extractor.initialPage
        nextPage = page.nextPage
        hasNext = page.hasNextPage()
        page.items
    }

    suspend fun more(): List<InfoItem> = withContext(Dispatchers.IO) {
        val np = nextPage ?: return@withContext emptyList<InfoItem>()
        val page = extractor.getPage(np)
        nextPage = page.nextPage
        hasNext = page.hasNextPage()
        page.items
    }
}

/**
 * Repository YouTube Music (online) — semua akses jaringan via NewPipeExtractor.
 * Metode suspend berjalan di Dispatchers.IO; `resolveAudioBlocking` sengaja blocking
 * karena dipanggil dari thread loader ExoPlayer (pola yang sama dipakai InnerTune/Metrolist).
 */
class YouTubeRepository {

    companion object {
        private const val TAG = "LyraArtistAvatar"

        /** Batas tunggu player response di extractor (detik). */
        private const val LOADING_TIMEOUT_SEC = 12

        @Volatile
        private var initialized = false

        /** Dipanggil sekali dari LyreonApp.onCreate. */
        fun initNewPipe() {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                NewPipe.init(
                    OkHttpDownloader.init(LyreonHttp.extractClient),
                    Localization("en", "US"),
                )
                // Knob extractor: batas tunggu player response dinaikkan (default
                // fork 5 s terlalu pendek di jaringan seluler) dan panggilan
                // dislike pihak ketiga dimatikan (tidak dipakai Lyreon — satu
                // request ke returnyoutubedislikeapi.com hilang per pemutaran).
                runCatching { ServiceList.YouTube.setLoadingTimeout(LOADING_TIMEOUT_SEC) }
                    .onFailure { Log.w(TAG, "setLoadingTimeout gagal: ${it.message}") }
                runCatching { ServiceList.YouTube.setFetchDislike(false) }
                    .onFailure { Log.w(TAG, "setFetchDislike gagal: ${it.message}") }
                // Lyreon selalu ANONIM: `ServiceList.YouTube.setTokens()` sengaja
                // tidak pernah dipanggil (keputusan produk — pengguna tidak perlu
                // mengambil cookie). Konsekuensinya extractor hanya memakai jalur
                // klien anonimnya; jalur tangga klien InnerTube di InnertubeFallback
                // yang menutup sisanya.
                initialized = true
            }
        }

        private fun videoIdOf(url: String): String = try {
            YoutubeStreamLinkHandlerFactory.getInstance().getId(url)
        } catch (e: Exception) {
            Regex("[?&]v=([\\w-]{6,})").find(url)?.groupValues?.get(1)
                ?: url.substringAfter("/shorts/").substringBefore("?").takeIf { it.length in 6..15 }
                ?: url.substringAfter("youtu.be/").substringBefore("?").ifBlank { url }
        }
    }

    // ------------------------------------------------------------------
    // Pencarian
    // ------------------------------------------------------------------

    fun newSearchSession(query: String, filter: SearchFilter): YtSearchSession {
        // Fork Metrolist: getSearchExtractor menerima List<FilterItem>, bukan string.
        // Nama filter ("music_songs", "videos", dsb.) dipetakan ke FilterItem
        // milik YoutubeSearchQueryHandlerFactory; fallback = pencarian tanpa filter.
        val contentFilter = resolveContentFilter(filter.newPipeFilter)
        val extractor = runCatching {
            ServiceList.YouTube.getSearchExtractor(query, listOfNotNull(contentFilter), null)
        }.getOrElse {
            ServiceList.YouTube.getSearchExtractor(query)
        }
        return YtSearchSession(extractor)
    }

    /** Pohon Filter konten YouTube; di-resolve sekali, thread-safe. */
    private val ytContentFilterRoot: org.schabi.newpipe.extractor.search.filter.Filter? by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        runCatching { YoutubeSearchQueryHandlerFactory.getInstance().availableContentFilter }
            .getOrNull()
    }

    private fun resolveContentFilter(name: String): FilterItem? =
        ytContentFilterRoot?.filterGroups
            ?.flatMap { it.filterItems.toList() }
            ?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    data class SearchOutcome(
        val tracks: List<LyreonTrack>,
        val playlists: List<YtPlaylist>,
    )

    fun mapSearchItems(items: List<InfoItem>): SearchOutcome {
        val tracks = mutableListOf<LyreonTrack>()
        val playlists = mutableListOf<YtPlaylist>()
        items.forEach { item ->
            when (item) {
                is StreamInfoItem -> mapInfoItem(item)?.let(tracks::add)
                is PlaylistInfoItem -> playlists.add(
                    YtPlaylist(
                        url = item.url.orEmpty(),
                        name = item.name.orEmpty(),
                        uploader = item.uploaderName.orEmpty(),
                        thumbnailUrl = item.thumbnailUrl.orEmpty(),
                        streamCount = item.streamCount,
                    ),
                )
                else -> Unit
            }
        }
        return SearchOutcome(
            tracks = tracks.distinctBy { it.videoId }.filter { it.durationSec > 0 },
            playlists = playlists.distinctBy { it.url },
        )
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            ServiceList.YouTube.suggestionExtractor.suggestionList(query)
        }.getOrDefault(emptyList()).take(10)
    }

    // ------------------------------------------------------------------
    // Metadata & resolusi stream
    // ------------------------------------------------------------------

    private suspend fun streamInfo(videoId: String): StreamInfo = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
    }

    /** Info lengkap: track + rekomendasi terkait (radio). */
    suspend fun bundle(videoId: String): YtTrackBundle {
        val info = streamInfo(videoId)
        val track = LyreonTrack(
            videoId = videoId,
            title = info.name.orEmpty(),
            artist = info.uploaderName.orEmpty(),
            album = "",
            durationSec = info.duration,
            thumbnailUrl = bestThumb(info.thumbnails),
        )
        val related = info.relatedItems.orEmpty()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { mapInfoItem(it) }
            .filter { it.durationSec > 0 }
            .distinctBy { it.videoId }
            .take(25)
        // Panen konteks benih: tagar resmi + tagar deskripsi + kategori —
        // bahan bakar algoritma selera (semuanya gratis dari halaman yang sama).
        val descText = runCatching { info.description?.content.orEmpty() }.getOrDefault("")
        val seedTags = runCatching { info.tags.orEmpty() }.getOrDefault(emptyList())
        val seedCategory = runCatching { info.category.orEmpty() }.getOrDefault("")
        val seedHashtags = com.lyreon.app.data.taste.MusicTextAnalyzer
            .hashtagsOf(descText + " " + seedTags.joinToString(" "))
        return YtTrackBundle(track, related, seedTags, seedHashtags, seedCategory)
    }

    suspend fun relatedOf(videoId: String): List<LyreonTrack> {
        val primary = runCatching { bundle(videoId).related }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) return primary
        // FALLBACK InnerTube (endpoint /next) untuk radio bila Metrolist gagal
        return runCatching { fallback.related(videoId) }.getOrDefault(emptyList())
    }

    /** Pencarian lagu cepat (filter SONGS) — untuk query persona algoritma. */
    suspend fun searchTracks(query: String, limit: Int = 20): List<LyreonTrack> =
        withContext(Dispatchers.IO) {
            val primary = runCatching {
                val s = newSearchSession(query, SearchFilter.SONGS)
                mapSearchItems(s.first()).tracks.take(limit)
            }.getOrNull()
            if (!primary.isNullOrEmpty()) return@withContext primary
            // FALLBACK InnerTube bila Metrolist gagal / kosong
            Log.w(TAG, "searchTracks: Metrolist kosong/gagal ($query) → fallback InnerTube")
            fallback.search(query, SearchFilter.SONGS, limit)
        }

    /**
     * Blocking — dipanggil dari ResolvingDataSource (thread loader ExoPlayer)
     * dan dari layanan unduhan. Memilih stream audio-only sesuai kualitas.
     *
     * Urutan jalur:
     *  1. **MetrolistExtractor** (NewPipe) — dilewati bila [PlayerClientLadder]
     *     baru saja membuktikan extractor membalas SABR-only berulang (bypass
     *     10 menit), supaya tiap lagu tidak membayar timeout extractor dulu.
     *  2. **Tangga klien InnerTube anonim** ([InnertubeFallback]) — URL langsung
     *     dari klien bebas poToken, atau manifest HLS bila hanya itu yang ada.
     *
     * @param allowManifest false untuk unduhan: `DownloadManager` hanya bisa
     *   mengunduh file progresif, jadi manifest HLS tidak boleh dipilih di sana.
     */
    fun resolveAudioBlocking(
        videoId: String,
        quality: AudioQuality,
        allowManifest: Boolean = true,
    ): ResolvedAudio {
        if (!com.lyreon.app.yt.innertube.PlayerClientLadder.extractorBypassed()) {
            val info = try {
                StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            } catch (e: Exception) {
                // Metrolist gagal → catat alasannya (SABR-only vs error per-video),
                // lalu coba jalur fallback InnerTube.
                noteExtractorFailure(videoId, e)
                return fallbackResolve(videoId, quality, cause = e, allowManifest = allowManifest)
            }
            val rawStreams: List<AudioStream> = info.audioStreams.orEmpty()
                .filter { it.content.isNullOrBlank().not() }
            val progressive = rawStreams.filterNot { isHlsStream(it) }
            val manifests = rawStreams.filter { isHlsStream(it) }

            // URL langsung lebih hemat (audio-only). Manifest HLS dipakai bila
            // tidak ada pilihan lain — Lyreon memutarnya lewat media3-exoplayer-hls.
            val streams: List<AudioStream> = when {
                progressive.isNotEmpty() -> progressive
                allowManifest && manifests.isNotEmpty() -> manifests
                else -> emptyList()
            }

            if (streams.isEmpty()) {
                // Metrolist OK tapi tanpa stream audio → fallback InnerTube
                com.lyreon.app.yt.innertube.PlayerClientLadder.push(
                    "extractor: 0 stream audio untuk $videoId " +
                        "(mentah=${rawStreams.size}, HLS=${manifests.size})",
                )
                return fallbackResolve(videoId, quality, cause = null, allowManifest = allowManifest)
            }
            com.lyreon.app.yt.innertube.PlayerClientLadder.noteExtractorSuccess()

            fun bitrateOf(s: AudioStream): Int = when {
                s.averageBitrate > 0 -> s.averageBitrate
                s.bitrate > 0 -> s.bitrate
                else -> -1
            }

            val scored = streams.map { it to bitrateOf(it) }

            val chosen = when (quality) {
                AudioQuality.HIGH -> scored
                    .filter { it.second > 0 }
                    .maxWithOrNull(compareBy<Pair<AudioStream, Int>> { it.second }
                        .thenBy { if (it.first.format?.suffix == "m4a") 1 else 0 })
                AudioQuality.DATA_SAVER -> scored
                    .filter { it.second > 0 }
                    .minWithOrNull(compareBy<Pair<AudioStream, Int>> { it.second }
                        .thenBy { if (it.first.format?.suffix == "m4a") 0 else 1 })
                AudioQuality.BALANCED -> scored
                    .filter { it.second > 0 }
                    .minWithOrNull(
                        compareBy<Pair<AudioStream, Int>> { kotlin.math.abs(it.second - 128) }
                            .thenBy { if (it.first.format?.suffix == "m4a") -1 else 0 },
                    )
            } ?: scored.first()

            val stream = chosen.first
            val url = stream.content
            val format = stream.format
            val manifest = isHlsStream(stream)
            val suffix = if (manifest) "m3u8" else (format?.suffix ?: "m4a")
            // Manifest HLS tidak punya MediaFormat audio di extractor → MIME wajib
            // diisi manual, kalau tidak DefaultMediaSourceFactory salah memilih
            // ProgressiveMediaSource dan gagal mengendus formatnya.
            val mime = if (manifest) {
                com.lyreon.app.yt.innertube.InnertubeFallback.HLS_MIME
            } else {
                format?.mimeType ?: "audio/mp4"
            }
            val bitrateKbps = (chosen.second.takeIf { it > 0 } ?: 128)

            // Kandidat cadangan dari keluarga format BERBEDA (m4a ↔ webm/opus),
            // bitrate sedekat mungkin dengan pilihan utama.
            val fallbackUrl = streams
                .filter { it !== stream && (it.format?.suffix ?: suffix) != suffix }
                .map { it to bitrateOf(it) }
                .filter { it.second > 0 }
                .minByOrNull { kotlin.math.abs(it.second - bitrateKbps) }
                ?.first?.content
                ?.takeUnless { it.isBlank() || it == url }

            val resolved = ResolvedAudio(
                videoId = videoId,
                url = url,
                mimeType = mime,
                suffix = suffix,
                bitrateKbps = bitrateKbps,
                expiresAtMs = parseExpire(url),
                fallbackUrl = fallbackUrl,
                isManifest = manifest,
            )

            // VALIDASI URL EXTRACTOR — pelajaran dari Meld.
            //
            // NewPipe memberi URL dari klien yang kini diwajibkan poToken GVS, sehingga
            // URL-nya ADA tetapi 403 begitu di-GET: persis laporan "extractor streams=4,
            // pemutar ERROR_CODE_IO_BAD_HTTP_STATUS". Probe byte terakhir membedakan
            // keduanya; bila ditolak, jangan dipakai — turun ke tangga klien InnerTube
            // yang memvalidasi tiap URL sebelum diserahkan ke ExoPlayer.
            if (!manifest) {
                val probe = com.lyreon.app.yt.innertube.StreamUrlValidator.validate(
                    url = url,
                    contentLength = null, // diturunkan dari `clen=` di URL
                    label = "extractor videoId=$videoId",
                )
                if (!probe.accepted) {
                    com.lyreon.app.yt.innertube.PlayerClientLadder.push(
                        "URL extractor DITOLAK CDN (${probe.code} pada ${probe.range}) → turun ke tangga klien",
                    )
                    Log.w(TAG, "URL extractor untuk $videoId ditolak CDN (${probe.code}) — pakai tangga klien")
                    return fallbackResolve(
                        videoId = videoId,
                        quality = quality,
                        cause = java.io.IOException("URL extractor ditolak CDN (HTTP ${probe.code})"),
                        allowManifest = allowManifest,
                    )
                }
            }

            return resolved
        }

        // Extractor sedang di-bypass (SABR berulang) → langsung tangga klien.
        return fallbackResolve(videoId, quality, cause = null, allowManifest = allowManifest)
    }

    /** Ekstraktor cadangan InnerTube (langsung ke Google). */
    private val fallback: com.lyreon.app.yt.innertube.InnertubeFallback by lazy {
        com.lyreon.app.yt.innertube.InnertubeFallback()
    }

    /**
     * Rekam kegagalan extractor dan bedakan dua sebab yang butuh tindakan beda:
     *  - **SABR-only** → kebijakan server YouTube, kena ke semua lagu; jalurnya
     *    ganti klien di [PlayerClientLadder] (Lyreon tetap anonim, tanpa cookie).
     *    Setelah berulang, extractor di-bypass sementara.
     *  - **selain itu** → biasanya spesifik video (privat, dihapus, batas umur).
     */
    private fun noteExtractorFailure(videoId: String, e: Exception) {
        val message = e.message.orEmpty()
        if (message.contains("SABR", ignoreCase = true)) {
            com.lyreon.app.yt.innertube.PlayerClientLadder.noteExtractorSabr(
                "$videoId (${e.javaClass.simpleName})",
            )
        } else {
            com.lyreon.app.yt.innertube.PlayerClientLadder.push(
                "extractor gagal untuk $videoId (${e.javaClass.simpleName})",
            )
        }
        Log.w(TAG, "extractor gagal untuk $videoId: ${e.javaClass.simpleName}: ${message.take(200)}")
    }

    /** Resolve via jalur InnerTube; melempar IOException bila gagal juga. */
    private fun fallbackResolve(
        videoId: String,
        quality: AudioQuality,
        cause: Throwable?,
        allowManifest: Boolean = true,
    ): ResolvedAudio {
        try {
            val resolved = fallback.resolveAudioBlocking(videoId, quality, allowManifest)
            Log.i("InnertubeFallback", "fallback OK untuk $videoId")
            return resolved.copy(videoId = videoId)
        } catch (e: com.lyreon.app.yt.innertube.StreamUnavailableException) {
            // Bawa alasan apa adanya (SABR-only / HLS-only / playability) ke atas
            // supaya PlayerManager bisa memberi pesan yang bisa ditindaklanjuti.
            throw e
        } catch (e: Exception) {
            throw IOException(
                "Stream tidak tersedia (Metrolist + InnerTube gagal) untuk $videoId",
                cause ?: e,
            )
        }
    }

    /**
     * True bila stream ini sebenarnya manifest HLS (m3u8), bukan file audio
     * progresif. Muncul dari `hlsManifestUrl` — jalur yang tetap terbuka untuk
     * klien anonim (HLS tidak menuntut poToken GVS). Lyreon memutarnya lewat
     * `media3-exoplayer-hls`, tetapi unduhan tetap menghindarinya.
     */
    private fun isHlsStream(stream: AudioStream): Boolean {
        val url = stream.content.orEmpty()
        val mime = stream.format?.mimeType.orEmpty()
        return mime.contains("mpegurl", ignoreCase = true) ||
            mime.contains("x-mpegURL", ignoreCase = true) ||
            url.contains(".m3u8", ignoreCase = true) ||
            url.contains("/api/manifest/hls_variant") ||
            url.contains("/api/manifest/hls/")
    }

    private fun parseExpire(url: String): Long {
        val exp = Regex("[?&]expire=(\\d{9,})").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val now = System.currentTimeMillis()
        return if (exp != null && exp > 0) exp * 1000L else now + 5L * 60L * 60L * 1000L
    }

    /**
     * Mode VIDEO: pilih stream muxed (video+audio dalam satu URL, itag 18/22 gaya lama)
     * terbaik hingga 720p, prefer MP4. Null jika tak ada → UI kembali ke mode audio.
     */
    suspend fun resolveVideoMuxed(videoId: String): String? = withContext(Dispatchers.IO) {
        val info = runCatching { streamInfo(videoId) }.getOrNull() ?: return@withContext null
        val muxed = info.videoStreams.orEmpty()
            .filter { !it.isVideoOnly && !it.content.isNullOrBlank() }
        if (muxed.isEmpty()) return@withContext null

        fun resOf(s: VideoStream): Int =
            Regex("(\\d{3,4})p").find(s.resolution.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val best = muxed
            .filter { resOf(it) in 1..720 }
            .maxWithOrNull(
                compareBy(
                    { s: VideoStream -> if (s.format?.suffix == "mp4") 1 else 0 },
                    { s: VideoStream -> resOf(s) },
                ),
            )
            ?: muxed.minByOrNull { resOf(it) }
        best?.content
    }

    // ------------------------------------------------------------------
    // Playlist YouTube (buka & impor)
    // ------------------------------------------------------------------

    data class YtPlaylistDetail(
        val name: String,
        val uploader: String,
        val thumbnailUrl: String,
        val tracks: List<LyreonTrack>,
    )

    suspend fun playlistDetail(url: String): YtPlaylistDetail = withContext(Dispatchers.IO) {
        val primary = runCatching {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val tracks = info.relatedItems.orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { mapInfoItem(it) }
                .filter { it.durationSec > 0 }
                .distinctBy { it.videoId }
            YtPlaylistDetail(
                name = info.name.orEmpty(),
                uploader = info.uploaderName.orEmpty(),
                thumbnailUrl = info.thumbnailUrl.orEmpty(),
                tracks = tracks,
            )
        }.getOrNull()
        if (primary != null && (primary.tracks.isNotEmpty() || primary.name.isNotBlank())) return@withContext primary
        // FALLBACK InnerTube bila Metrolist gagal
        Log.w(TAG, "playlistDetail: Metrolist gagal ($url) → fallback InnerTube")
        val pid = fallback.playlistIdOf(url)
        val tracks = fallback.playlist(pid)
        YtPlaylistDetail(
            name = "Playlist",
            uploader = "",
            thumbnailUrl = "",
            tracks = tracks,
        )
    }

    // ------------------------------------------------------------------
    // Home feed
    // ------------------------------------------------------------------

    /**
     * Feed beranda asli YouTube Music (`FEmusic_home`).
     *
     * Tidak lagi berupa pencarian teks "today's hits music" — itu hanyalah
     * query arbitrary dan tidak mencerminkan carousel resmi YouTube Music.
     * Jalur ini memakai `browse` WEB_REMIX yang sama dengan charts/genre-mood,
     * lalu [BrowseParser] mengubah rak `musicCarouselShelfRenderer` /
     * `musicShelfRenderer` menjadi [BrowseItem]. Rak beranda yang umum muncul:
     * Quick Picks, Trending, New releases, dsb.
     *
     * Cache-nya dibuat 10 menit, BUKAN 6 jam seperti halaman browse generik:
     * beranda dirancang untuk cepat berubah, umur 6 jam justru membuat
     * "trending" menjadi basi.
     */
    private val homeFeedLock = Any()
    private val homeFeedCache = HashMap<String, Pair<Long, List<LyreonTrack>>>()
    private val HOME_FEED_CACHE_MS = 10L * 60_000L

    suspend fun homeFeed(gl: String = "US", limit: Int = 40): List<LyreonTrack> = withContext(Dispatchers.IO) {
        val cacheKey = "$gl|$limit"
        synchronized(homeFeedLock) {
            homeFeedCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < HOME_FEED_CACHE_MS }
        }?.let { return@withContext it.second }

        val page = runCatching { fetchBrowsePage("FEmusic_home", null, gl) }
            .getOrDefault(BrowsePage(browseId = "FEmusic_home"))
        val tracks = page.sections
            .flatMap { it.items }
            .filter { it.kind == BrowseItemKind.TRACK && it.videoId.isNotBlank() }
            .map {
                LyreonTrack(
                    videoId = it.videoId,
                    title = it.title,
                    artist = it.subtitle.substringBefore(" • "),
                    album = it.subtitle.substringAfter(" • ", ""),
                    durationSec = it.durationSec.toLong(),
                    thumbnailUrl = it.thumbUrl.ifBlank { thumbHi(it.videoId) },
                )
            }
            .distinctBy { it.videoId }
            .take(limit)

        if (tracks.isNotEmpty()) {
            synchronized(homeFeedLock) {
                homeFeedCache[cacheKey] = System.currentTimeMillis() to tracks
            }
        }
        tracks
    }

    /**
     * Quick picks: terkait lagu terakhir yang diputar, atau feed beranda
     * YouTube Music yang sebenarnya.
     *
     * @param gl region yang dipakai feed beranda (mis. "ID", "US").
     */
    suspend fun quickPicks(seedVideoId: String?, gl: String = "US"): List<LyreonTrack> = withContext(Dispatchers.IO) {
        if (!seedVideoId.isNullOrBlank()) {
            val rel = relatedOf(seedVideoId)
            if (rel.isNotEmpty()) return@withContext rel.take(12)
        }
        val primary = runCatching { homeFeed(gl = gl, limit = 12) }.getOrNull()
        if (!primary.isNullOrEmpty()) return@withContext primary
        // FALLBACK terakhir: search biasa bila feed beranda gagal/parse kosong.
        val searchPrimary = runCatching {
            val session = newSearchSession("today's hits music", SearchFilter.SONGS)
            mapSearchItems(session.first()).tracks.take(12)
        }.getOrNull()
        if (!searchPrimary.isNullOrEmpty()) return@withContext searchPrimary
        fallback.search("today's hits music", SearchFilter.SONGS, 12)
    }

    /** Movement/genre section — kueri YouTube Music sesuai desain. */
    suspend fun movement(query: String, limit: Int = 14): List<LyreonTrack> = withContext(Dispatchers.IO) {
        val primary = runCatching {
            val session = newSearchSession(query, SearchFilter.SONGS)
            mapSearchItems(session.first()).tracks.take(limit)
        }.getOrNull()
        if (!primary.isNullOrEmpty()) return@withContext primary
        // FALLBACK InnerTube bila Metrolist gagal
        fallback.search(query, SearchFilter.SONGS, limit)
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private fun bestThumb(images: List<org.schabi.newpipe.extractor.Image?>?): String {
        if (images.isNullOrEmpty()) return ""
        return images.filterNotNull()
            .maxByOrNull { it.height }
            ?.url.orEmpty()
    }

    private fun mapInfoItem(item: StreamInfoItem): LyreonTrack? {
        val url = item.url ?: return null
        val duration = item.duration
        val vid = videoIdOf(url)
        return LyreonTrack(
            videoId = vid,
            title = item.name.orEmpty(),
            artist = item.uploaderName.orEmpty(),
            album = "",
            durationSec = if (duration > 0) duration else 0L,
            thumbnailUrl = thumbHi(vid, item.thumbnailUrl),
        )
    }

    /**
     * Thumbnail kualitas tinggi langsung dari CDN YouTube (480×360),
     * menggantikan thumbnail bawaan extractor yang sering resolusi rendah.
     */
    private fun thumbHi(videoId: String, fallback: String? = null): String =
        if (videoId.length in 6..20) {
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        } else {
            fallback.orEmpty()
        }

    // ------------------------------------------------------------------
    // Cache URL stream (expire ±6 jam) — dipakai player & unduhan
    // ------------------------------------------------------------------

    private val streamCache = ConcurrentHashMap<String, ResolvedAudio>()
    private val locks = ConcurrentHashMap<String, Any>()

    @Volatile
    var defaultQuality: AudioQuality = AudioQuality.BALANCED

    /** Resolve dengan cache + anti-duplikasi request + satu serangan ulang. Boleh blocking. */
    fun resolveCachedBlocking(
        videoId: String,
        quality: AudioQuality = defaultQuality,
        allowManifest: Boolean = true,
    ): ResolvedAudio {
        val fresh = streamCache[videoId]
        if (fresh != null && fresh.expiresAtMs - 60_000L > System.currentTimeMillis()) {
            return fresh
        }
        val lock = locks.getOrPut(videoId) { Any() }
        synchronized(lock) {
            val cached = streamCache[videoId]
            if (cached != null && cached.expiresAtMs - 60_000L > System.currentTimeMillis()) {
                return cached
            }
            // Percobaan ke-2 langsung di sini: kegagalan sesaat & token basi (pot/n-sig)
            // sering sembuh dengan ekstraksi ulang yang benar-benar baru.
            val resolved = runCatching {
                resolveAudioBlocking(videoId, quality, allowManifest)
            }.recoverCatching {
                Thread.sleep(350L)
                resolveAudioBlocking(videoId, quality, allowManifest)
            }.getOrThrow()
            streamCache[videoId] = resolved
            return resolved
        }
    }

    // ------------------------------------------------------------------
    // Trending per negara (halaman "Trending" YouTube, region via ?gl=)
    // ------------------------------------------------------------------

    suspend fun trending(country: String): List<LyreonTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.youtube.com/feed/trending?gl=${country.uppercase()}"
            val handler = YoutubeTrendingLinkHandlerFactory().fromUrl(url)
            val extractor = YoutubeTrendingExtractor(ServiceList.YouTube, handler, "Trending")
            // Region TIDAK dibaca dari ?gl= URL — harus dipaksa per-instance,
            // kalau tidak extractor selalu pakai content country default global.
            extractor.forceContentCountry(ContentCountry(country.uppercase()))
            extractor.fetchPage()
            extractor.initialPage.items.orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { mapInfoItem(it) }
                .filter { it.durationSec > 60L } // buang shorts/klip pendek
                .distinctBy { it.videoId }
                .take(24)
        }.getOrDefault(emptyList())
    }

    fun invalidate(videoId: String) {
        streamCache.remove(videoId)
        fallback.invalidate(videoId)
    }

    /** Buang seluruh cache URL stream di kedua jalur (extractor & InnerTube). */
    fun invalidateAll() {
        streamCache.clear()
        fallback.invalidateAll()
    }

    // ------------------------------------------------------------------
    // Diagnostik stream (Settings → "Tes koneksi")
    // ------------------------------------------------------------------

    /**
     * Laporan satu percobaan resolusi menyeluruh: jalur extractor dulu, lalu
     * seluruh tangga klien InnerTube. Inilah alat untuk menjawab "YouTube sedang
     * menutup klien yang mana hari ini?" tanpa perlu membongkar APK.
     */
    data class StreamProbe(
        val videoId: String,
        val extractorOk: Boolean,
        val extractorAudioStreams: Int,
        val extractorMs: Long,
        val extractorError: String,
        val ladder: com.lyreon.app.yt.innertube.LadderProbe,
        val diagnostics: List<String>,
    ) {
        /** True bila setidaknya satu jalur masih memberi URL audio yang bisa diputar. */
        val anyPathWorks: Boolean get() = extractorOk || ladder.success
    }

    suspend fun probeStream(videoId: String): StreamProbe = withContext(Dispatchers.IO) {
        val id = videoIdOf(videoId).ifBlank { videoId }
        invalidate(id)

        val startedAt = System.currentTimeMillis()
        val extractorResult = runCatching {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        }
        val extractorMs = System.currentTimeMillis() - startedAt
        val info = extractorResult.getOrNull()
        val audioCount = runCatching {
            info?.audioStreams.orEmpty().count { !it.content.isNullOrBlank() }
        }.getOrDefault(0)
        val extractorError = extractorResult.exceptionOrNull()?.let { e ->
            "${e.javaClass.simpleName}: ${e.message.orEmpty().take(180)}"
        }.orEmpty()

        val ladderProbe = runCatching { fallback.probeBlocking(id, defaultQuality) }.getOrElse { e ->
            com.lyreon.app.yt.innertube.LadderProbe(
                videoId = id,
                usableClient = null,
                audioUrlFound = false,
                manifestClient = null,
                sabrOnly = false,
                hlsOnly = false,
                drmOnly = false,
                totalMs = 0L,
                attempts = listOf(
                    com.lyreon.app.yt.innertube.ProbeAttempt(
                        client = "-",
                        verdict = "TRANSPORT_ERROR",
                        elapsedMs = 0L,
                        detail = e.javaClass.simpleName,
                    ),
                ),
            )
        }

        StreamProbe(
            videoId = id,
            extractorOk = extractorResult.isSuccess && audioCount > 0,
            extractorAudioStreams = audioCount,
            extractorMs = extractorMs,
            extractorError = extractorError,
            ladder = ladderProbe,
            diagnostics = com.lyreon.app.yt.innertube.PlayerClientLadder.report(),
        )
    }

    suspend fun warmUp(vararg videoIds: String) = withContext(Dispatchers.IO) {
        videoIds.forEach { id ->
            runCatching { resolveCachedBlocking(id) }
        }
    }

    /**
     * Manifest HLS hasil warm-up (bila ada) untuk satu videoId — dipakai
     * `PlayerManager.warmUpcoming` menukar `MediaItem` lagu BERIKUTNYA ke URL
     * m3u8 + MIME yang benar sebelum lagu itu diputar, sehingga pemutar tidak
     * perlu gagal dulu lalu retry.
     *
     * @return Pair(url, mimeType) atau null bila cache kosong/progresif.
     */
    fun cachedManifestFor(videoId: String): Pair<String, String>? =
        streamCache[videoId]?.takeIf { it.isManifest }?.let { it.url to it.mimeType }

    // ------------------------------------------------------------------
    // Artis populer per benua — YouTube Music Charts (InnerTube FEmusic_charts)
    //
    // Diagram alur (mengikuti cara InnerTune/Spotube):
    //   GET music.youtube.com  →  regex INNERTUBE_API_KEY  →
    //   POST /youtubei/v1/browse?key=…  { browseId: "FEmusic_charts", gl }
    //   →  sectionListRenderer → musicShelfRenderer "Top artists"
    //
    // Bila charts tidak tersedia/gagal parse → daftar kurasi per wilayah,
    // sehingga kartu artis TIDAK PERNAH kosong.
    // ------------------------------------------------------------------

    data class ArtistCard(
        val name: String,
        val thumbUrl: String,
        val subtitle: String,
        /** browseId kanal artis (UC…) — kosong berarti halaman artis tidak tersedia. */
        val browseId: String = "",
    )

    /** Wilayah kartu artis di tab Search (label di-UI per bahasa). */
    enum class ArtistRegion(val gl: String) {
        EUROPE("GB"),
        ASIA("ID"),
        AMERICA("US"),
        AFRICA("ZA"),
        ARAB("SA"),
    }

    @Volatile
    private var innertubeKey: String? = null
    private val chartsLock = Any()
    private val chartsCache = HashMap<String, Pair<Long, List<ArtistCard>>>()

    suspend fun topArtists(region: ArtistRegion): List<ArtistCard> = withContext(Dispatchers.IO) {
        synchronized(chartsLock) {
            chartsCache[region.gl]
                ?.takeIf { System.currentTimeMillis() - it.first < 6L * 3600_000L }
        }?.let { return@withContext it.second }

        val live = runCatching { fetchChartsTopArtists(region.gl) }.getOrDefault(emptyList())
        // Foto profil asli kanal diisi untuk entri tanpa foto (via pencarian kanal)
        val result = enrichArtistAvatars(live.ifEmpty { seedArtists(region) })
        synchronized(chartsLock) { chartsCache[region.gl] = System.currentTimeMillis() to result }
        result
    }

    private fun fetchInnertubeKey(): String {
        val cached = innertubeKey
        if (cached != null) return cached
        val key = runCatching {
            val req = Request.Builder()
                .url("https://music.youtube.com/")
                .header("User-Agent", LyreonHttp.USER_AGENT)
                .get().build()
            LyreonHttp.streamClient.newCall(req).execute().use { resp ->
                val html = resp.body.string()
                Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"")
                    .find(html)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
        // Kunci WEB_REMIX publik (dicadangkan bila halaman gagal diambil)
        return (key ?: "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30").also { innertubeKey = it }
    }

    private fun fetchChartsTopArtists(gl: String): List<ArtistCard> {
        val key = fetchInnertubeKey()
        val payload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20260804.01.00")
                        .put("hl", "en")
                        .put("gl", gl),
                ),
            )
            .put("browseId", "FEmusic_charts")
            .toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?key=$key")
            .header("User-Agent", LyreonHttp.USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .post(payload)
            .build()

        val root = LyreonHttp.streamClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "fetchChartsTopArtists(gl=$gl): browse gagal, HTTP ${resp.code}")
                return emptyList()
            }
            JSONObject(resp.body.string())
        }

        fun textOf(node: JSONObject?, key: String): String {
            val runs = node?.optJSONObject(key)?.optJSONArray("runs") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString()
        }

        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return emptyList()

        for (t in 0 until tabs.length()) {
            val sections = tabs.optJSONObject(t)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue

            for (s in 0 until sections.length()) {
                val shelf = sections.optJSONObject(s)?.optJSONObject("musicShelfRenderer") ?: continue
                val title = textOf(shelf.optJSONObject("title"), "text")
                if (!title.contains("artist", ignoreCase = true)) continue

                val items = shelf.optJSONArray("contents") ?: continue
                val out = ArrayList<ArtistCard>(12)
                for (i in 0 until items.length()) {
                    val row = items.optJSONObject(i)
                        ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    val flex = row.optJSONArray("flexColumns") ?: continue
                    val name = textOf(flex.optJSONObject(0), "musicResponsiveListItemFlexColumnRenderer")
                    if (name.isBlank()) continue
                    val sub = textOf(flex.optJSONObject(1), "musicResponsiveListItemFlexColumnRenderer")
                    val artistBrowseId = row.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId")
                        .orEmpty()
                    val thumbs = row.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    var thumb = ""
                    var bestW = -1
                    if (thumbs != null) {
                        for (k in 0 until thumbs.length()) {
                            val th = thumbs.optJSONObject(k) ?: continue
                            val w = th.optInt("width", 0)
                            if (w > bestW) {
                                bestW = w
                                thumb = th.optString("url").orEmpty()
                            }
                        }
                    }
                    out += ArtistCard(name = name, thumbUrl = thumb, subtitle = sub, browseId = artistBrowseId)
                    if (out.size >= 12) break
                }
                if (out.isNotEmpty()) return out
            }
        }
        return emptyList()
    }

    /** Kurasi bila charts wilayah tak tersedia — kartu tetap terisi bermakna. */
    private fun seedArtists(region: ArtistRegion): List<ArtistCard> = when (region) {        ArtistRegion.EUROPE -> listOf(
            "Dua Lipa", "Ed Sheeran", "Coldplay", "Adele", "Calvin Harris",
            "David Guetta", "Rita Ora", "Stromae", "Zara Larsson", "Clean Bandit",
        )
        ArtistRegion.ASIA -> listOf(
            "Tulus", "Juicy Luicy", "Hindia", "Bernadya", "Nadin Amizah",
            "YOASOBI", "BLACKPINK", "Ado", "Sheila On 7", "Raisa",
        )
        ArtistRegion.AMERICA -> listOf(
            "Bad Bunny", "Taylor Swift", "Billie Eilish", "The Weeknd", "Bruno Mars",
            "Kendrick Lamar", "Shakira", "Ariana Grande", "Drake", "SZA",
        )
        ArtistRegion.AFRICA -> listOf(
            "Tyla", "Burna Boy", "Rema", "Tems", "Asake",
            "Ayra Starr", "Davido", "Wizkid", "Amaarae", "Uncle Waffles",
        )
        ArtistRegion.ARAB -> listOf(
            "Amr Diab", "Nancy Ajram", "Elissa", "Tamer Hosny", "Fairuz",
            "Mohamed Ramadan", "Saad Lamjarred", "Assala", "Hussain Al Jassmi", "Cairokee",
        )
    }.map { ArtistCard(name = it, thumbUrl = "", subtitle = "") }

    // ------------------------------------------------------------------
    // Foto profil kanal (avatar artis/pengunggah)
    // ------------------------------------------------------------------

    private val uploaderAvatarCache = ConcurrentHashMap<String, String>()
    private val channelAvatarCache = ConcurrentHashMap<String, String>()

    private fun absUrl(url: String): String = if (url.startsWith("//")) "https:$url" else url

    /** Foto kanal pengunggah lagu (untuk bar artis di Now Playing). */
    suspend fun uploaderAvatar(videoId: String): String? = withContext(Dispatchers.IO) {
        uploaderAvatarCache[videoId]?.let { return@withContext it.ifBlank { null } }
        val url = runCatching {
            val info = streamInfo(videoId)
            absUrl(bestThumb(info.uploaderAvatars).ifBlank { info.uploaderAvatarUrl.orEmpty() })
        }.getOrDefault("")
        uploaderAvatarCache[videoId] = url
        url.ifBlank { null }
    }

    /**
     * Profil kanal YouTube dari nama artis → (avatarUrl, subtitle subscriber).
     *
     * Jalur utama sekarang InnerTube /search (WEB_REMIX), sama seperti jalur
     * browse & charts. YouTube memperketat pencarian kanal dan ChannelInfo lewat
     * Extractor (SABR/poToken sering membuatnya kosong), sedangkan `musicSearch`
     * sudah memakai klien WEB_REMIX yang kembali membaca `musicTwoRowItemRenderer`
     * beserta thumbnail kanal asli.
     *
     * Jalur NewPipe hanya dipakai sebagai cadangan bila InnerTube gagal/kosong.
     */
    suspend fun channelProfile(artistName: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val key = artistName.lowercase(java.util.Locale.ROOT)
        channelAvatarCache[key]?.let { cached ->
            if (cached.isBlank()) return@withContext null
            val parts = cached.split('|', limit = 3)
            return@withContext (parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" })
        }

        val innerTube = runCatching {
            musicSearch(artistName, SearchFilter.ARTISTS, "US").artists
                .firstOrNull { it.thumbUrl.isNotBlank() }
                ?.let { absUrl(it.thumbUrl) to it.subtitle }
        }.getOrNull()

        val result = innerTube?.takeIf { it.first.isNotBlank() }
            ?: runCatching {
                val cf = resolveContentFilter("channels")
                val extractor = runCatching {
                    ServiceList.YouTube.getSearchExtractor(artistName, listOfNotNull(cf), null)
                }.getOrElse { ServiceList.YouTube.getSearchExtractor(artistName) }
                extractor.fetchPage()
                val channelUrl = extractor.initialPage.items
                    .filterIsInstance<org.schabi.newpipe.extractor.channel.ChannelInfoItem>()
                    .firstOrNull()
                    ?.url.orEmpty()
                if (channelUrl.isBlank()) {
                    Log.w(TAG, "channelProfile('$artistName'): search kanal InnerTube kosong + NewPipe kosong")
                    null
                } else {
                    val info = org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(channelUrl)
                    val avatar = absUrl(bestThumb(info.avatars))
                    if (avatar.isBlank()) {
                        Log.w(TAG, "channelProfile('$artistName'): ChannelInfo OK tapi avatars kosong — url=$channelUrl")
                        null
                    } else {
                        avatar to formatSubscribers(runCatching { info.subscriberCount }.getOrDefault(-1L))
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "channelProfile('$artistName') exception: ${e.javaClass.simpleName} — ${e.message}")
            }.getOrNull()

        channelAvatarCache[key] = result?.let { "${it.first}|${it.second}" } ?: ""
        result
    }

    private fun formatSubscribers(subs: Long): String = when {
        subs < 0L -> ""
        subs >= 1_000_000L -> "%.1fM subscribers".format(subs / 1_000_000f)
        subs >= 1_000L -> "%.0fK subscribers".format(subs / 1_000f)
        else -> "$subs subscribers"
    }

    /** Isi foto profil untuk kartu tanpa foto — resolve SEMUA secara paralel (tanpa cap). */
    private suspend fun enrichArtistAvatars(list: List<ArtistCard>): List<ArtistCard> = coroutineScope {
        list.map { card ->
            async {
                if (card.thumbUrl.isBlank()) {
                    val profile = channelProfile(card.name)
                    if (profile != null) {
                        card.copy(
                            thumbUrl = profile.first,
                            subtitle = card.subtitle.ifBlank { profile.second },
                        )
                    } else {
                        card
                    }
                } else {
                    card
                }
            }
        }.awaitAll()
    }

    // ------------------------------------------------------------------
    // Browse generik — halaman artis, album, genre/mood (gelombang 6)
    //
    //   POST /youtubei/v1/browse?key=…  { browseId, params?, gl }
    //
    // Bentuk responsnya beragam (rak baris, carousel kartu, grid), jadi
    // penguraiannya dipisah ke [BrowseParser] yang murni dan bebas Android.
    // Hasil di-cache 6 jam seperti charts: halaman artis tidak berubah cepat,
    // dan ini membuat navigasi bolak-balik terasa instan.
    // ------------------------------------------------------------------

    private val browseLock = Any()

    /** Umur cache halaman browse: 6 jam, sama seperti cache charts. */
    private val BROWSE_CACHE_MS = 6L * 3600_000L
    private val browseCache = HashMap<String, Pair<Long, BrowsePage>>()

    /**
     * Ambil satu halaman browse. Kegagalan jaringan/parse menghasilkan
     * [BrowsePage] kosong (bukan exception) supaya layar cukup menampilkan
     * keadaan kosong dengan tombol coba lagi.
     *
     * @param browseId id kanal artis (UC…), "FEmusic_moods_and_genres",
     *   "FEmusic_moods_and_genres_category", "VL"+playlistId, dsb.
     * @param params token tambahan (dipakai kategori genre/mood)
     */
    suspend fun browsePage(browseId: String, params: String? = null, gl: String = "US"): BrowsePage =
        withContext(Dispatchers.IO) {
            val cacheKey = "$browseId|${params.orEmpty()}|$gl"
            synchronized(browseLock) {
                browseCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < BROWSE_CACHE_MS }
            }?.let { return@withContext it.second }

            val page = runCatching { fetchBrowsePage(browseId, params, gl) }
                .getOrElse { err ->
                    Log.w(TAG, "browsePage($browseId) gagal: ${err.message}")
                    BrowsePage(browseId = browseId)
                }
            if (!page.isEmpty) {
                synchronized(browseLock) { browseCache[cacheKey] = System.currentTimeMillis() to page }
            }
            page
        }

    /** Hapus cache browse (dipanggil saat pengguna membersihkan cache aplikasi). */
    fun clearBrowseCache() {
        synchronized(browseLock) { browseCache.clear() }
    }

    // ------------------------------------------------------------------
    // Pencarian YouTube Music (WEB_REMIX) — lagu + artis + album + playlist
    //
    // Jalur extractor NewPipe hanya mengenal video & playlist YouTube biasa,
    // jadi kartu artis/album (permintaan pemilik proyek) harus datang dari
    // rak `musicShelfRenderer` YouTube Music. Permintaan ini memakai klien dan
    // host yang sama dengan `browsePage`/charts, yang sudah terbukti jalan.
    // ------------------------------------------------------------------

    private val musicSearchLock = Any()
    private val musicSearchCache = HashMap<String, Pair<Long, MusicSearchSummary>>()

    /** Umur cache pencarian musik: 10 menit (hasil pencarian cepat basi). */
    private val MUSIC_SEARCH_CACHE_MS = 10L * 60_000L

    /**
     * Cari di YouTube Music dan kembalikan semua jenis hasil sekaligus.
     * Kegagalan apa pun menghasilkan ringkasan kosong, bukan exception.
     */
    suspend fun musicSearch(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        gl: String = "US",
    ): MusicSearchSummary = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext MusicSearchSummary()
        val cacheKey = "$query|${filter.name}|$gl"
        synchronized(musicSearchLock) {
            musicSearchCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < MUSIC_SEARCH_CACHE_MS }
        }?.let { return@withContext it.second }

        val summary = runCatching { fetchMusicSearch(query, filter, gl) }.getOrElse { err ->
            Log.w(TAG, "musicSearch($query, $filter) gagal: ${err.message}")
            MusicSearchSummary()
        }
        if (!summary.isEmpty) {
            synchronized(musicSearchLock) {
                musicSearchCache[cacheKey] = System.currentTimeMillis() to summary
            }
        }
        summary
    }

    /** Buang cache pencarian musik (dipakai tombol "reset cache" di Pengaturan). */
    fun clearMusicSearchCache() {
        synchronized(musicSearchLock) { musicSearchCache.clear() }
    }

    /**
     * Parameter filter YouTube Music. Nilai disalin dari `SearchFilter` Metrolist
     * (`innertube/.../YouTube.kt`) — base64 protobuf, tidak boleh dikarang.
     */
    private fun musicSearchParams(filter: SearchFilter): String? = when (filter) {
        SearchFilter.SONGS -> "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        SearchFilter.VIDEOS -> "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        SearchFilter.ALBUMS -> "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        SearchFilter.ARTISTS -> "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        SearchFilter.PLAYLISTS -> "EgeKAQQoAEABagoQAxAEEAoQCRAF"
        SearchFilter.PODCASTS -> "EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D"
        SearchFilter.ALL -> null
    }

    private fun fetchMusicSearch(query: String, filter: SearchFilter, gl: String): MusicSearchSummary {
        val key = fetchInnertubeKey()
        val payload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20260804.01.00")
                        .put("hl", "en")
                        .put("gl", gl),
                ),
            )
            .put("query", query)
            .apply { musicSearchParams(filter)?.let { put("params", it) } }
            .toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?key=$key")
            .header("User-Agent", LyreonHttp.USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .post(payload)
            .build()

        val root = LyreonHttp.streamClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "fetchMusicSearch($query): HTTP ${resp.code}")
                return MusicSearchSummary()
            }
            JSONObject(resp.body.string())
        }
        return MusicSearchParser.parse(root)
    }

    private fun fetchBrowsePage(browseId: String, params: String?, gl: String): BrowsePage {
        val key = fetchInnertubeKey()
        val payload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20260804.01.00")
                        .put("hl", "en")
                        .put("gl", gl),
                ),
            )
            .put("browseId", browseId)
            .apply { if (!params.isNullOrBlank()) put("params", params) }
            .toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?key=$key")
            .header("User-Agent", LyreonHttp.USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .post(payload)
            .build()

        val root = LyreonHttp.streamClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "fetchBrowsePage($browseId): HTTP ${resp.code}")
                return BrowsePage(browseId = browseId)
            }
            JSONObject(resp.body.string())
        }
        return BrowseParser.parse(root, browseId)
    }
}
