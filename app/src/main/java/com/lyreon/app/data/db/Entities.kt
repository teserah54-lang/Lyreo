/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

data class PlaylistWithStats(
    @Embedded val playlist: PlaylistEntity,
    val itemCount: Int,
    val coverUrl: String?,
)

@Entity(tableName = "liked_tracks")
data class LikedTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val likedAt: Long,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val lastPlayedAt: Long,
    val playCount: Int,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val addedAt: Long,
)

object DownloadState {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val DONE = "DONE"
    const val ERROR = "ERROR"
    const val CANCELED = "CANCELED"
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val durationSec: Long,
    val state: String,
    val filePath: String?,
    val bytesTotal: Long,
    val bytesDone: Long,
    val errorMessage: String?,
    val createdAt: Long,
)
