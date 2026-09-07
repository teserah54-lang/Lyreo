/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.local

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.LyreonTrack.Companion.LOCAL_ID_PREFIX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Membaca musik lokal dari perangkat lewat DUA jalur:
 *
 *  1) **MediaStore** — indeks audio bawaan Android. Seleksi sengaja dilonggarkan
 *     (TANPA `IS_MUSIC != 0`): banyak file pengguna (unduhan browser, kiriman
 *     chat, rekaman studio) ditandai bukan-musik sehingga lolos dari filter lama.
 *     Yang dibuang hanya nada dering/notifikasi/alarm (+ rekaman suara di API 29+).
 *
 *  2) **Folder kustom (SAF)** — `ACTION_OPEN_DOCUMENT_TREE`. Ini satu-satunya
 *     cara menembus folder ber-`.nomedia` (WhatsApp/Telegram — tempat lagu
 *     pengguna Indonesia sering menumpuk) karena folder seperti itu memang
 *     sengaja tidak diindeks MediaStore oleh Android.
 *
 * Thumbnail: embedded album art via [MediaMetadataRetriever.getEmbeddedPicture]
 * → downscale ≤512px → cache di `cacheDir/local_art/` sebagai URI `file://`.
 */
class LocalMusicRepository(private val context: Context) {

    private val artDir: File by lazy {
        File(context.cacheDir, "local_art").apply { mkdirs() }
    }
    private val prefs by lazy {
        context.getSharedPreferences("lyreon_local", Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------
    // Izin
    // ------------------------------------------------------------------

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Jalur 1: MediaStore
    // ------------------------------------------------------------------

    suspend fun scanMediaStore(): List<LyreonTrack> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        runCatching { queryStore() }.getOrDefault(emptyList())
    }

    private fun queryStore(): List<LyreonTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        // Longgar: JANGAN pakai IS_MUSIC (membuang banyak file asli pengguna).
        // Cukup singkirkan suara sistem & rekaman voice memo.
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_RINGTONE} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Audio.Media.IS_RECORDING} = 0")
            }
        }
        // Sortir polos — ekspresi COLLATE bisa ditolak provider beberapa OEM
        val sort = "${MediaStore.Audio.Media.TITLE} ASC"

        val out = mutableListOf<LyreonTrack>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sort,
        )?.use { cursor ->
            val colId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val colTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val colArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val colAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val colDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(colId)
                val durationMs = cursor.getLong(colDuration)
                val title = cursor.getString(colTitle).orEmpty()
                // Saring audio sangat pendek & entri tanpa judul
                if (durationMs < 10_000L || title.isBlank()) continue
                out += LyreonTrack(
                    videoId = LOCAL_ID_PREFIX + id,
                    title = title,
                    artist = cursor.getString(colArtist).orEmpty()
                        .removePrefix("<unknown>").trim(),
                    album = cursor.getString(colAlbum).orEmpty(),
                    durationSec = durationMs / 1000L,
                    thumbnailUrl = cachedArt("ms_$id") ?: "",
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Jalur 2: Folder kustom via SAF (menembus .nomedia)
    // ------------------------------------------------------------------

    fun trees(): List<String> =
        prefs.getStringSet(KEY_TREES, emptySet()).orEmpty().toList()

    fun addTree(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        prefs.edit().putStringSet(KEY_TREES, trees().toSet() + uri.toString()).apply()
    }

    fun removeTree(treeUri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        prefs.edit().putStringSet(KEY_TREES, trees().toSet() - treeUri).apply()
    }

    /** Label pendek sebuah tree untuk chip UI ("primary:Music/Pop" → "Music/Pop"). */
    fun treeLabel(treeUri: String): String =
        runCatching { Uri.parse(treeUri).lastPathSegment.orEmpty() }
            .getOrDefault("")
            .substringAfter(':')
            .ifBlank { treeUri.takeLast(24) }

    suspend fun scanTrees(): List<LyreonTrack> = withContext(Dispatchers.IO) {
        val out = mutableListOf<LyreonTrack>()
        trees().forEach { tree ->
            runCatching { walkTree(Uri.parse(tree), out) }
        }
        out
    }

    private fun walkTree(treeUri: Uri, out: MutableList<LyreonTrack>) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return

        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 10) return
            dir.listFiles().forEach { f ->
                when {
                    f.isDirectory -> walk(f, depth + 1)
                    f.isFile -> {
                        val name = f.name ?: return@forEach
                        if (!isAudioFile(name)) return@forEach
                        val docUri = f.uri.toString()
                        out += LyreonTrack(
                            videoId = SAF_PREFIX + Uri.encode(docUri),
                            title = name.substringBeforeLast('.'),
                            artist = "",
                            album = "",
                            durationSec = 0L, // diisi bertahap oleh enrichSafChunk
                            thumbnailUrl = cachedArt("sa_" + docUri.hashCode()) ?: "",
                        )
                    }
                }
            }
        }
        walk(root, 0)
    }

    /**
     * Enrich sebagian lagu SAF (per chunk): baca judul/artis/album/durasi asli
     * dari tag ID3 dkk + ekstrak art — dengan SATU MediaMetadataRetriever yang
     * dipakai ulang agar cepat. File gagal dibaca dibiarkan apa adanya.
     */
    suspend fun enrichSafChunk(tracks: List<LyreonTrack>): List<LyreonTrack> =
        withContext(Dispatchers.IO) {
            val mmr = MediaMetadataRetriever()
            try {
                tracks.map { t ->
                    runCatching {
                        val uri = uriForVideoId(t.videoId) ?: return@runCatching t
                        mmr.setDataSource(context, uri)
                        val durMs = mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION,
                        )?.toLongOrNull() ?: 0L
                        val tagTitle = mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_TITLE,
                        )?.trim().orEmpty()
                        val artist = mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_ARTIST,
                        ).orEmpty().removePrefix("<unknown>").trim()
                        val album = mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_ALBUM,
                        )?.trim().orEmpty()
                        val artKey = "sa_" + uri.toString().hashCode()
                        val art = cachedArt(artKey)
                            ?: mmr.embeddedPicture?.let { saveArt(artKey, it) }
                        t.copy(
                            title = tagTitle.ifBlank { t.title },
                            artist = artist,
                            album = album,
                            durationSec = durMs / 1000L,
                            thumbnailUrl = art ?: t.thumbnailUrl,
                        )
                    }.getOrDefault(t)
                }
            } finally {
                runCatching { mmr.release() }
            }
        }

    // ------------------------------------------------------------------
    // Thumbnail (embedded album art)
    // ------------------------------------------------------------------

    private fun cachedArt(key: String): String? {
        val f = File(artDir, "$key.jpg")
        return if (f.exists() && f.length() > 0L) Uri.fromFile(f).toString() else null
    }

    private fun saveArt(key: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val biggest = maxOf(bounds.outWidth, bounds.outHeight)
        while (biggest / (sample * 2) >= 512) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return null
        val target = File(artDir, "$key.jpg")
        return runCatching {
            target.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            Uri.fromFile(target).toString()
        }.getOrNull()
    }

    /** Ekstrak art lagu MediaStore (dipakai bertahap dari ViewModel). */
    suspend fun embeddedArt(track: LyreonTrack): String? = withContext(Dispatchers.IO) {
        val storeId = track.videoId.removePrefix(LOCAL_ID_PREFIX).toLongOrNull()
            ?: return@withContext null
        val key = "ms_$storeId"
        cachedArt(key)?.let { return@withContext it }
        val marker = File(artDir, "$key.none")
        if (marker.exists()) return@withContext null

        val bytes = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, contentUri(storeId))
                mmr.embeddedPicture
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()

        if (bytes == null || bytes.isEmpty()) {
            runCatching { marker.createNewFile() }
            return@withContext null
        }
        saveArt(key, bytes)
    }

    companion object {
        const val SAF_PREFIX = "local:sa:"
        private const val KEY_TREES = "tree_uris"

        /** Izin runtime sesuai level API (Android 13+ memakai izin media granular). */
        fun requiredPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }

        /** URI `content://media/external/audio/media/{id}` dari MediaStore ID. */
        fun contentUri(storeId: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, storeId)

        /** Rekonstruksi URI pemutaran dari videoId lokal (MediaStore maupun SAF). */
        fun uriForVideoId(videoId: String): Uri? = when {
            videoId.startsWith(SAF_PREFIX) ->
                runCatching { Uri.parse(Uri.decode(videoId.removePrefix(SAF_PREFIX))) }.getOrNull()
            else -> videoId.removePrefix(LOCAL_ID_PREFIX).toLongOrNull()?.let(::contentUri)
        }

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus",
            "wav", "amr", "mid", "midi", "3gp",
        )

        fun isAudioFile(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
    }
}
