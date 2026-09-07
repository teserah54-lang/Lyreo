/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.lyreon.app.LyreonApp
import com.lyreon.app.data.LibraryRepository
import com.lyreon.app.data.db.DownloadState
import com.lyreon.app.data.db.LyreonDatabase
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.settings.SettingsRepository
import com.lyreon.app.download.LyreonDownloadManager
import com.lyreon.app.lyrics.LyricsRepository
import com.lyreon.app.player.PlayerManager
import com.lyreon.app.yt.YouTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val Context.sessionStore by preferencesDataStore(name = "lyreon_session")

/** Menyimpan antrean & posisi terakhir agar sesi dengar bisa dilanjutkan. */
class SessionStore(private val context: Context) {

    @Serializable
    data class SavedSession(
        val queue: List<LyreonTrack> = emptyList(),
        val index: Int = 0,
        val positionMs: Long = 0L,
        val shuffle: Boolean = false,
        val repeatMode: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("session_json")

    suspend fun save(queue: List<LyreonTrack>, index: Int, positionMs: Long, shuffle: Boolean, repeatMode: Int) {
        val data = SavedSession(queue.take(200), index, positionMs, shuffle, repeatMode)
        runCatching {
            context.sessionStore.edit { it[key] = json.encodeToString(SavedSession.serializer(), data) }
        }
    }

    suspend fun load(): SavedSession? = runCatching {
        val raw = context.sessionStore.data.map { it[key] }.first() ?: return null
        json.decodeFromString(SavedSession.serializer(), raw)
    }.getOrNull()
}

/** Service locator sederhana (manual DI) — satu instance per aplikasi. */
class ServiceLocator(val app: LyreonApp) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: LyreonDatabase by lazy {
        Room.databaseBuilder(app, LyreonDatabase::class.java, "lyreon.db")
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
    }

    val library: LibraryRepository by lazy {
        LibraryRepository(db.libraryDao(), db.downloadDao())
    }

    val settings: SettingsRepository by lazy { SettingsRepository(app) }

    /**
     * Lyreon berjalan ANONIM penuh: tidak ada cookie/kredensial akun di mana pun.
     * Lihat notes/02 §1 — jangan menambahkan jalur login tanpa keputusan produk.
     */
    /** Profil selera pengguna untuk algoritma rekomendasi (lokal, on-device). */
    val taste: com.lyreon.app.data.taste.TasteRepository by lazy {
        com.lyreon.app.data.taste.TasteRepository(app)
    }

    /** Query pencarian yang diminta dari layar lain (mis. chip vibe di Home). */
    val pendingSearchQuery = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val youtube: YouTubeRepository by lazy { YouTubeRepository() }

    val lyrics: LyricsRepository by lazy { LyricsRepository(settings) }

    val local: com.lyreon.app.local.LocalMusicRepository by lazy {
        com.lyreon.app.local.LocalMusicRepository(app)
    }

    val session: SessionStore by lazy { SessionStore(app) }

    val player: PlayerManager by lazy { PlayerManager(app, this) }

    val downloads: LyreonDownloadManager by lazy { LyreonDownloadManager(app, this) }

    init {
        // Kualitas stream mengikuti pengaturan
        appScope.launch {
            settings.settings.collect { youtube.defaultQuality = it.audioQuality }
        }
        // Muat profil selera tersimpan (algoritma rekomendasi)
        appScope.launch { taste.warm() }
        // Unduhan yang terpotong karena proses mati → antre ulang
        appScope.launch {
            runCatching { db.downloadDao().resetDownloadingToQueued() }
        }
    }

    /** Dipakai ResolvingDataSource: file unduhan (jika ada & utuh) mengalahkan stream. */
    fun downloadedFileFor(videoId: String): String? = runBlocking(Dispatchers.IO) {
        val row = db.downloadDao().byIdOnce(videoId) ?: return@runBlocking null
        if (row.state != DownloadState.DONE) return@runBlocking null
        val path = row.filePath ?: return@runBlocking null
        if (File(path).exists() && File(path).length() > 0L) path else null
    }
}
