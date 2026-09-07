/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    // ---- Liked ----
    @Query("SELECT * FROM liked_tracks ORDER BY likedAt DESC")
    fun likedTracks(): Flow<List<LikedTrackEntity>>

    @Query("SELECT videoId FROM liked_tracks")
    fun likedIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun like(track: LikedTrackEntity)

    @Query("DELETE FROM liked_tracks WHERE videoId = :videoId")
    suspend fun unlike(videoId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE videoId = :videoId)")
    fun isLiked(videoId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE videoId = :videoId)")
    suspend fun isLikedOnce(videoId: String): Boolean

    // ---- History ----
    @Query("SELECT * FROM history ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun history(limit: Int = 100): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun mostPlayed(limit: Int = 20): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entry: HistoryEntity)

    @Query("SELECT * FROM history WHERE videoId = :videoId")
    suspend fun historyEntry(videoId: String): HistoryEntity?

    @Query("DELETE FROM history WHERE videoId NOT IN (SELECT videoId FROM history ORDER BY lastPlayedAt DESC LIMIT 500)")
    suspend fun trimHistory()

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    // ---- Playlists ----
    @Query("SELECT * FROM playlists ORDER BY createdAt ASC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query(
        "SELECT p.*, " +
            "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS itemCount, " +
            "(SELECT i.thumbnailUrl FROM playlist_items i WHERE i.playlistId = p.id ORDER BY i.position ASC LIMIT 1) AS coverUrl " +
            "FROM playlists p ORDER BY p.createdAt ASC",
    )
    fun playlistsWithStats(): Flow<List<PlaylistWithStats>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun playlistItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun playlistItemsOnce(playlistId: Long): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removePlaylistItem(playlistId: Long, videoId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun playlistSize(playlistId: Long): Int
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    fun byId(videoId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    suspend fun byIdOnce(videoId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED','DOWNLOADING') ORDER BY createdAt ASC")
    fun active(): Flow<List<DownloadEntity>>

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED','DOWNLOADING')")
    suspend fun activeCount(): Int

    @Query("UPDATE downloads SET state = 'QUEUED' WHERE state = 'DOWNLOADING'")
    suspend fun resetDownloadingToQueued()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("UPDATE downloads SET state = 'CANCELED' WHERE state IN ('QUEUED','DOWNLOADING')")
    suspend fun cancelAll()
}
