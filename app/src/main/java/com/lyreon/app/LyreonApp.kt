/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.lyreon.app.core.ServiceLocator
import com.lyreon.app.download.DownloadService
import com.lyreon.app.yt.YouTubeRepository

/**
 * Lyreon — Hear What Words Can't Say.
 * Client musik YouTube audio-only: pencarian, playlist, unduhan, background play.
 */
class LyreonApp : Application() {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        YouTubeRepository.initNewPipe()
        locator = ServiceLocator(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val downloads = NotificationChannel(
            DownloadService.CHANNEL_ID,
            getString(R.string.downloads_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.downloads_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(downloads)
    }
}
