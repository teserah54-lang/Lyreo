/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lyreon.app.LyreonApp
import com.lyreon.app.MainActivity
import com.lyreon.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service (tipe dataSync) untuk mengunduh audio di latar belakang.
 * Notifikasi progres selalu terlihat; berhenti otomatis saat antrean kosong.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSelf()
        if (started) return START_STICKY
        started = true

        serviceScope.launch {
            try {
                (application as LyreonApp).locator.downloads.processQueue { active, title, progress ->
                    updateNotification(active, title, progress)
                }
            } finally {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startSelf() {
        val notification = buildNotification(0, null, Float.NaN)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateNotification(active: Int, title: String?, progress: Float) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(active, title, progress))
    }

    private fun buildNotification(active: Int, title: String?, progress: Float): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                if (active > 0) getString(R.string.app_name) + " mengunduh ($active)"
                else getString(R.string.app_name) + " - unduhan selesai",
            )
            .setContentText(title ?: "Menyiapkan…")
            .setContentIntent(openApp)
            .setOngoing(active > 0)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (progress.isNaN()) {
            builder.setProgress(100, 0, true)
        } else {
            val pct = (progress.coerceIn(0f, 1f) * 100f)
            builder.setProgress(100, pct.roundToInt(), false)
            builder.setSubText("${pct.roundToInt()}%")
        }
        return builder.build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "lyreon_downloads"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
