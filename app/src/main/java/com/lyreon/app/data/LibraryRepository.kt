/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data

import com.lyreon.app.data.db.DownloadDao
import com.lyreon.app.data.db.DownloadEntity
import com.lyreon.app.data.db.DownloadState
import com.lyreon.app.data.db.HistoryEntity
import com.lyreon.app.data.db.LibraryDao
import com.lyreon.app.data.db.LikedTrackEntity
import com.lyreon.app.data.db.PlaylistEntity
import com.lyreon.app.data.db.PlaylistItemEntity
import com.lyreon.app.data.model.LyreonTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(
    private val libraryDao: LibraryDao,
    private val downloadDao: DownloadDao,
) {

    // ---- Liked ----
    val likedTracks: Flow<List<LyreonTrack>> =
        libraryDao.likedTracks().map { list -> list.map { it.toTrack() } }

    val likedIds: Flow<Set<String>> =
        libraryDao.likedIds().map { it.toSet() }

    fun isLiked(videoId: String): Flow<Boolean> = libraryDao.isLiked(videoId)

    /** @return true bila setelah aksi ini lagu menjadi disukai. */
    suspend fun toggleLike(track: LyreonTrack): Boolean {
        return if (libraryDao.isLikedOnce(track.videoId)) {
            libraryDao.unlike(track.videoId)
            false
        } else {
            like(track)
            true
        }
    }

    suspend fun like(track: LyreonTrack) {
        libraryDao.like(
            LikedTrackEntity(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationSec = track.durationSec,
                likedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unlike(videoId: String) = libraryDao.unlike(videoId)

    // ---- History ----
    val history: Flow<List<LyreonTrack>> =
        libraryDao.history(100).map { list -> list.map { it.toTrack() } }

    val mostPlayed: Flow<List<LyreonTrack>> =
        libraryDao.mostPlayed(20).map { list -> list.map { it.toTrack() } }

    suspend fun recordPlay(track: LyreonTrack) {
        val existing = libraryDao.historyEntry(track.videoId)
        libraryDao.upsertHistory(
            HistoryEntity(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationSec = track.durationSec,
                lastPlayedAt = System.currentTimeMillis(),
                playCount = (existing?.playCount ?: 0) + 1,
            ),
        )
        libraryDao.trimHistory()
    }

    suspend fun clearHistory() = libraryDao.clearHistory()

    // ---- Playlists ----
    data class PlaylistSummary(
        val id: Long,
        val name: String,
        val createdAt: Long,
        val itemCount: Int,
        val coverUrl: String,
    )

    val playlists: Flow<List<PlaylistEntity>> = libraryDao.playlists()

    val playlistsWithStats: Flow<List<com.lyreon.app.data.db.PlaylistWithStats>> =
        libraryDao.playlistsWithStats()

    fun playlistItems(playlistId: Long): Flow<List<LyreonTrack>> =
        libraryDao.playlistItems(playlistId).map { list -> list.map { it.toTrack() } }

    suspend fun createPlaylist(name: String): Long =
        libraryDao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    suspend fun renamePlaylist(id: Long, name: String) = libraryDao.renamePlaylist(id, name)

    suspend fun deletePlaylist(playlist: PlaylistEntity) {
        libraryDao.clearPlaylist(playlist.id)
        libraryDao.deletePlaylist(playlist)
    }

    suspend fun addToPlaylist(playlistId: Long, track: LyreonTrack) {
        val size = libraryDao.playlistSize(playlistId)
        libraryDao.insertPlaylistItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                videoId = track.videoId,
                position = size,
                title = track.title,
                artist = track.artist,
                album = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationSec = track.durationSec,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Tambahkan banyak track sekaligus (impor m3u/pls) — posisi berlanjut. */
    suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<LyreonTrack>) {
        var size = libraryDao.playlistSize(playlistId)
        tracks.forEach { track ->
            libraryDao.insertPlaylistItem(
                PlaylistItemEntity(
                    playlistId = playlistId,
                    videoId = track.videoId,
                    position = size++,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    thumbnailUrl = track.thumbnailUrl,
                    durationSec = track.durationSec,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun removeFromPlaylist(playlistId: Long, videoId: String) {
        libraryDao.removePlaylistItem(playlistId, videoId)
        // rapikan posisi
        val items = libraryDao.playlistItemsOnce(playlistId)
        items.forEachIndexed { index, item ->
            if (item.position != index) {
                libraryDao.insertPlaylistItem(item.copy(position = index))
            }
        }
    }

    suspend fun moveInPlaylist(playlistId: Long, from: Int, to: Int) {
        val items = libraryDao.playlistItemsOnce(playlistId).toMutableList()
        if (from !in items.indices || to !in items.indices) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        items.forEachIndexed { index, item ->
            if (item.position != index) {
                libraryDao.insertPlaylistItem(item.copy(position = index))
            }
        }
    }

    // ---- Downloads (data access; mesin unduh di download/LyreonDownloadManager) ----
    val downloads: Flow<List<DownloadEntity>> = downloadDao.all()

    fun downloadState(videoId: String): Flow<DownloadEntity?> = downloadDao.byId(videoId)

    val downloadedIds: Flow<Set<String>> = downloadDao.all().map { list ->
        list.filter { it.state == DownloadState.DONE }.map { it.videoId }.toSet()
    }

    // ---- Mappers ----
    private fun LikedTrackEntity.toTrack() =
        LyreonTrack(videoId, title, artist, album, durationSec, thumbnailUrl)

    private fun HistoryEntity.toTrack() =
        LyreonTrack(videoId, title, artist, album, durationSec, thumbnailUrl)

    private fun PlaylistItemEntity.toTrack() =
        LyreonTrack(videoId, title, artist, album, durationSec, thumbnailUrl)
}
