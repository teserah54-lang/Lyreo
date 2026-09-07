/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt

import org.json.JSONArray
import org.json.JSONObject

/** Jenis butir hasil browse — menentukan ke mana ketukan diarahkan. */
enum class BrowseItemKind { TRACK, PLAYLIST, ALBUM, ARTIST, OTHER }

/**
 * Satu butir rak (lagu, album, playlist, atau artis). Semua identitas dibawa
 * sekaligus supaya layar tidak perlu menebak dari bentuk teks.
 */
data class BrowseItem(
    val kind: BrowseItemKind = BrowseItemKind.OTHER,
    val title: String = "",
    val subtitle: String = "",
    val thumbUrl: String = "",
    val videoId: String = "",
    val playlistId: String = "",
    val browseId: String = "",
    val browseParams: String = "",
    val durationSec: Int = 0,
)

/** Satu rak berjudul beserta isinya. */
data class BrowseSection(
    val title: String = "",
    val items: List<BrowseItem> = emptyList(),
)

/**
 * Kepala halaman (halaman artis/album/playlist): sampul besar, ringkasan, dan
 * dua endpoint pemutaran YouTube Music.
 *
 * [shufflePlaylistId] biasanya `OLAK5uy_...` (isi album/katalog apa adanya),
 * [radioPlaylistId] biasanya `RDAMVM<videoId>` atau `RDCLAK...` (radio tak
 * berujung dari benih). Keduanya boleh kosong — layar harus tetap berguna
 * dengan tombol "Putar" biasa dari daftar lagu.
 */
data class BrowseHeader(
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val thumbUrl: String = "",
    /** Baris meta: jumlah pelanggan / pendengar bulanan / tahun. */
    val meta: String = "",
    val shufflePlaylistId: String = "",
    val radioPlaylistId: String = "",
    val seedVideoId: String = "",
) {
    val isEmpty: Boolean get() = title.isBlank() && thumbUrl.isBlank() && description.isBlank()
}

/** Hasil satu panggilan browse InnerTube (halaman artis, genre/mood, kategori). */
data class BrowsePage(
    val title: String = "",
    val sections: List<BrowseSection> = emptyList(),
    val browseId: String = "",
    val header: BrowseHeader = BrowseHeader(),
) {
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }
}

/**
 * Pengurai respons `youtubei/v1/browse` (klien WEB_REMIX) menjadi [BrowsePage].
 *
 * Bentuk yang ditangani — inilah yang dipakai YouTube Music untuk halaman artis,
 * album, playlist, dan genre/mood:
 * - `singleColumnBrowseResultsRenderer.tabs[].tabRenderer.content.sectionListRenderer`
 * - rak: `musicShelfRenderer` (baris daftar), `musicCarouselShelfRenderer` dan
 *   `musicImmersiveCarouselShelfRenderer` (kartu dua baris), `gridRenderer`
 * - butir: `musicResponsiveListItemRenderer` dan `musicTwoRowItemRenderer`
 *
 * Pengurai ini murni (tanpa jaringan, tanpa Android) supaya mudah dibaca dan
 * tidak bisa menjatuhkan pemutar bila YouTube mengubah bentuk respons: semua
 * pembacaan memakai optX dan hasil kosong lebih baik daripada crash.
 */
object BrowseParser {

    private val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"
    private val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"
    private val PLAYLIST_PAGE = "MUSIC_PAGE_TYPE_PLAYLIST"
    private val TRACK_PAGE = "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"
    private val USER_PAGE = "MUSIC_PAGE_TYPE_USER_CHANNEL"

    /**
     * Baris `musicResponsiveListItemRenderer` tunggal → [BrowseItem].
     *
     * Diekspos (bukan privat) supaya pengurai lain — misalnya pencarian YouTube
     * Music di [MusicSearchParser] — memakai SATU sumber kebenaran untuk bentuk
     * baris yang sama. YouTube memakai renderer identik di browse dan search,
     * jadi dua implementasi hanya akan berbeda saat salah satunya basi.
     */
    fun parseRow(row: JSONObject): BrowseItem? = parseListItem(row)

    /** Kartu `musicTwoRowItemRenderer` tunggal → [BrowseItem]. */
    fun parseCard(card: JSONObject): BrowseItem? = parseTwoRow(card)

    fun parse(root: JSONObject, browseId: String): BrowsePage {
        val sections = ArrayList<BrowseSection>()
        val header = parseHeader(root.optJSONObject("header"))
        var title = header.title.ifBlank { headerTitle(root.optJSONObject("header")) }

        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
        if (tabs != null) {
            for (t in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(t)?.optJSONObject("tabRenderer") ?: continue
                if (title.isBlank()) title = tab.optString("title")
                collectSections(tab.optJSONObject("content"), sections)
            }
        } else {
            collectSections(root.optJSONObject("contents"), sections)
        }

        return BrowsePage(
            title = title,
            sections = sections.filter { it.items.isNotEmpty() },
            browseId = browseId,
            header = if (header.title.isBlank()) header.copy(title = title) else header,
        )
    }

    // ------------------------------------------------------------------
    // Kepala halaman artis/album/playlist
    // ------------------------------------------------------------------

    private val RADIO_BUTTONS = setOf("startRadioButton", "radioButton")
    private val PLAY_BUTTONS = setOf("playButton", "shuffleButton")

    /**
     * Bentuk kepala yang ditangani (YouTube Music mengubahnya berkala, jadi
     * semuanya dicoba): `musicImmersiveHeaderRenderer` (artis),
     * `musicDetailHeaderRenderer` (album/playlist), `musicVisualHeaderRenderer`.
     */
    private fun parseHeader(header: JSONObject?): BrowseHeader {
        if (header == null) return BrowseHeader()
        val node = firstNonNull(
            header.optJSONObject("musicImmersiveHeaderRenderer"),
            header.optJSONObject("musicDetailHeaderRenderer"),
            header.optJSONObject("musicVisualHeaderRenderer"),
            header.optJSONObject("musicHeaderRenderer"),
        ) ?: return BrowseHeader()

        val title = firstNonBlank(
            runsText(node.optJSONObject("title")),
            runsText(
                node.optJSONObject("header")
                    ?.optJSONObject("musicResponsiveHeaderRenderer")
                    ?.optJSONObject("title"),
            ),
            node.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty(),
        )

        val subtitleNode = node.optJSONObject("subtitle")
            ?: node.optJSONObject("header")
                ?.optJSONObject("musicResponsiveHeaderRenderer")
                ?.optJSONObject("subtitle")
        val subtitle = runsText(subtitleNode)

        // Deskripsi artis ada di dalam deskripsi rak, bukan di judul.
        val description = runsText(
            node.optJSONObject("description")
                ?.optJSONObject("musicDescriptionShelfRenderer")
                ?.optJSONObject("description"),
        ).ifBlank {
            runsText(node.optJSONObject("description"))
        }

        val thumb = bestThumb(
            node.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
        ).ifBlank {
            bestThumb(
                node.optJSONObject("thumbnail")
                    ?.optJSONObject("croppedSquareThumbnailRenderer")
                    ?.optJSONArray("thumbnails"),
            )
        }.ifBlank {
            bestThumb(
                node.optJSONObject("foregroundThumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails"),
            )
        }

        var shuffleId = ""
        var radioId = ""
        collectPlaylistEndpoints(node) { key, playlistId ->
            when {
                key in RADIO_BUTTONS && radioId.isBlank() -> radioId = playlistId
                key in PLAY_BUTTONS && shuffleId.isBlank() -> shuffleId = playlistId
                radioId.isBlank() && playlistId.startsWith("RD") -> radioId = playlistId
                shuffleId.isBlank() -> shuffleId = playlistId
            }
        }

        // Radio "RDAMVM<videoId>" membawa benihnya — berguna untuk memulai radio
        // sendiri bila playlist radio tidak bisa dibuka.
        val seed = radioId.removePrefix("RDAMVM").takeIf { radioId.startsWith("RDAMVM") }.orEmpty()

        return BrowseHeader(
            title = title,
            subtitle = subtitle,
            description = description,
            thumbUrl = thumb,
            meta = subtitle,
            shufflePlaylistId = shuffleId,
            radioPlaylistId = radioId,
            seedVideoId = seed,
        )
    }

    /**
     * Telusuri pohon kepala dan kumpulkan setiap `watchPlaylistEndpoint.playlistId`
     * beserta nama kunci induknya, sehingga tombol radio bisa dibedakan dari
     * tombol putar/acak tanpa bergantung pada urutan.
     */
    private fun collectPlaylistEndpoints(node: Any?, parentKey: String = "", visit: (String, String) -> Unit) {
        when (node) {
            is JSONObject -> {
                val endpoint = node.optJSONObject("watchPlaylistEndpoint")
                val playlistId = endpoint?.optString("playlistId").orEmpty()
                if (playlistId.isNotBlank()) visit(parentKey, playlistId)
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectPlaylistEndpoints(node.opt(key), key, visit)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectPlaylistEndpoints(node.opt(i), parentKey, visit)
            }
        }
    }

    private fun firstNonNull(vararg nodes: JSONObject?): JSONObject? =
        nodes.firstOrNull { it != null }

    // ------------------------------------------------------------------
    // Rak
    // ------------------------------------------------------------------

    private fun collectSections(content: JSONObject?, out: MutableList<BrowseSection>) {
        if (content == null) return
        val list = content.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
        if (list != null) {
            for (i in 0 until list.length()) addSection(list.optJSONObject(i), out)
            return
        }
        addSection(content, out)
    }

    private fun addSection(node: JSONObject?, out: MutableList<BrowseSection>) {
        if (node == null) return
        node.optJSONObject("musicShelfRenderer")?.let { shelf ->
            val items = ArrayList<BrowseItem>()
            val rows = shelf.optJSONArray("contents") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val renderer = rows.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                parseListItem(renderer)?.let(items::add)
            }
            out.add(BrowseSection(runsText(shelf.optJSONObject("title")), items))
        }
        node.optJSONObject("musicCarouselShelfRenderer")?.let { shelf ->
            out.add(BrowseSection(carouselTitle(shelf), twoRowItems(shelf.optJSONArray("contents"))))
        }
        node.optJSONObject("musicImmersiveCarouselShelfRenderer")?.let { shelf ->
            out.add(BrowseSection(carouselTitle(shelf), twoRowItems(shelf.optJSONArray("contents"))))
        }
        node.optJSONObject("gridRenderer")?.let { grid ->
            val head = grid.optJSONObject("header")?.optJSONObject("gridHeaderRenderer")
            out.add(BrowseSection(runsText(head?.optJSONObject("title")), twoRowItems(grid.optJSONArray("items"))))
        }
    }

    private fun carouselTitle(shelf: JSONObject): String {
        val header = shelf.optJSONObject("header")
        val basic = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            ?: header?.optJSONObject("musicImmersiveCarouselShelfBasicHeaderRenderer")
        return runsText(basic?.optJSONObject("title"))
    }

    private fun twoRowItems(array: JSONArray?): List<BrowseItem> {
        val items = ArrayList<BrowseItem>()
        if (array == null) return items
        for (i in 0 until array.length()) {
            val renderer = array.optJSONObject(i)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
            parseTwoRow(renderer)?.let(items::add)
        }
        return items
    }

    // ------------------------------------------------------------------
    // Butir
    // ------------------------------------------------------------------

    private fun parseListItem(row: JSONObject): BrowseItem? {
        val flex = row.optJSONArray("flexColumns") ?: return null
        val first = flex.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        val titleNode = first?.optJSONObject("text") ?: return null
        val title = runsText(titleNode)
        if (title.isBlank()) return null

        val subParts = ArrayList<String>()
        for (i in 1 until flex.length()) {
            val col = flex.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            val text = runsText(col?.optJSONObject("text"))
            if (text.isNotBlank()) subParts.add(text)
        }

        // Durasi biasanya di kolom tetap terakhir ("3:45")
        var duration = 0
        val fixed = row.optJSONArray("fixedColumns")
        if (fixed != null) {
            for (i in 0 until fixed.length()) {
                val col = fixed.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                duration = parseDuration(runsText(col?.optJSONObject("text")))
            }
        }

        val nav = row.optJSONObject("navigationEndpoint")
        val watchNav = titleNode.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
        val overlayWatch = row.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")

        val videoId = firstNonBlank(
            overlayWatch?.optJSONObject("watchEndpoint")?.optString("videoId"),
            watchNav?.optJSONObject("watchEndpoint")?.optString("videoId"),
            nav?.optJSONObject("watchEndpoint")?.optString("videoId"),
        )
        val playlistId = firstNonBlank(
            overlayWatch?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId"),
            nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId"),
            watchNav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId"),
        )
        val browseEndpoint = nav?.optJSONObject("browseEndpoint")
            ?: watchNav?.optJSONObject("browseEndpoint")
        val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
            .orEmpty()

        return BrowseItem(
            kind = when {
                pageType == ARTIST_PAGE || pageType == USER_PAGE -> BrowseItemKind.ARTIST
                pageType == ALBUM_PAGE -> BrowseItemKind.ALBUM
                pageType == PLAYLIST_PAGE || playlistId.isNotBlank() -> BrowseItemKind.PLAYLIST
                videoId.isNotBlank() -> BrowseItemKind.TRACK
                else -> BrowseItemKind.OTHER
            },
            title = title,
            // Pemisah ini BUKAN hiasan: ia kontrak parsing yang dibaca kembali oleh
        // MusicSearchParser (substringBefore/After) untuk memisahkan artis, album,
        // dan tahun. Titik tengah dipilih justru karena praktis tak pernah muncul
        // di metadata asli, sedangkan "-" sering ada di judul album sehingga
        // pemisahan akan salah. Jangan ganti tanpa mengubah semua konsumennya.
        subtitle = subParts.joinToString(" • "),
            thumbUrl = listThumb(row),
            videoId = videoId,
            playlistId = playlistId,
            browseId = browseEndpoint?.optString("browseId").orEmpty(),
            browseParams = browseEndpoint?.optString("params").orEmpty(),
            durationSec = duration,
        )
    }

    private fun parseTwoRow(card: JSONObject): BrowseItem? {
        val titleRuns = card.optJSONObject("title")?.optJSONArray("runs") ?: return null
        if (titleRuns.length() == 0) return null
        val title = runsText(card.optJSONObject("title"))
        if (title.isBlank()) return null

        val nav = titleRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            ?: card.optJSONObject("navigationEndpoint")
        val browseEndpoint = nav?.optJSONObject("browseEndpoint")
        val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
            .orEmpty()

        val overlayWatch = card.optJSONObject("thumbnailOverlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")

        val videoId = firstNonBlank(
            nav?.optJSONObject("watchEndpoint")?.optString("videoId"),
            overlayWatch?.optJSONObject("watchEndpoint")?.optString("videoId"),
        )
        val playlistId = firstNonBlank(
            nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId"),
            overlayWatch?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId"),
            browseEndpoint?.optString("browseId")?.takeIf { it.startsWith("VL") }?.removePrefix("VL"),
        )

        return BrowseItem(
            kind = when {
                pageType == ARTIST_PAGE || pageType == USER_PAGE -> BrowseItemKind.ARTIST
                pageType == ALBUM_PAGE -> BrowseItemKind.ALBUM
                pageType == PLAYLIST_PAGE || playlistId.isNotBlank() -> BrowseItemKind.PLAYLIST
                pageType == TRACK_PAGE || videoId.isNotBlank() -> BrowseItemKind.TRACK
                else -> BrowseItemKind.OTHER
            },
            title = title,
            subtitle = runsText(card.optJSONObject("subtitle")),
            thumbUrl = twoRowThumb(card),
            videoId = videoId,
            playlistId = playlistId,
            browseId = browseEndpoint?.optString("browseId").orEmpty(),
            browseParams = browseEndpoint?.optString("params").orEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Bantuan
    // ------------------------------------------------------------------

    private fun headerTitle(header: JSONObject?): String {
        if (header == null) return ""
        return firstNonBlank(
            runsText(header.optJSONObject("musicHeaderRenderer")?.optJSONObject("title")),
            runsText(
                header.optJSONObject("musicImmersiveHeaderRenderer")?.optJSONObject("title"),
            ),
            runsText(
                header.optJSONObject("musicVisualHeaderRenderer")?.optJSONObject("title"),
            ),
            runsText(
                header.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                    ?.optJSONObject("header")
                    ?.optJSONObject("musicResponsiveHeaderRenderer")
                    ?.optJSONObject("title"),
            ),
            header.optJSONObject("musicDetailHeaderRenderer")
                ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty(),
        )
    }

    /** Gabungkan semua run jadi satu teks (pola InnerTube: teks terpecah per run). */
    private fun runsText(node: JSONObject?): String {
        val runs = node?.optJSONArray("runs") ?: return node?.optString("simpleText").orEmpty()
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    private fun listThumb(row: JSONObject): String = bestThumb(
        row.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails"),
    )

    private fun twoRowThumb(card: JSONObject): String = bestThumb(
        card.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails"),
    )

    /** Ambil ukuran terbesar; YouTube mengurutkan menaik, tetapi jangan dipercaya. */
    private fun bestThumb(thumbs: JSONArray?): String {
        if (thumbs == null || thumbs.length() == 0) return ""
        var best = ""
        var bestArea = -1
        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            val area = t.optInt("width") * t.optInt("height")
            val url = t.optString("url")
            if (url.isNotBlank() && area > bestArea) {
                bestArea = area
                best = url
            }
        }
        return best.ifBlank { thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() }
    }

    private fun parseDuration(text: String): Int {
        val parts = text.trim().split(":")
        if (parts.size < 2 || parts.size > 3) return 0
        return runCatching {
            val nums = parts.map { it.toInt() }
            when (nums.size) {
                2 -> nums[0] * 60 + nums[1]
                else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            }
        }.getOrDefault(0)
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}
