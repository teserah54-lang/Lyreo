/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import com.lyreon.app.R
import com.lyreon.app.player.PlaybackService
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget pemutar di home screen (wishlist #16).
 *
 * Prinsip yang dijaga:
 * - RemoteViews dirender dua kali: sekali segera (teks + tombol, murah) dan
 *   sekali lagi setelah artwork selesai dimuat, supaya launcher tidak pernah
 *   menunggu jaringan.
 * - Tombol memakai intent media button standar (ACTION_MEDIA_BUTTON + KeyEvent)
 *   yang diarahkan ke layanan [PlaybackService], jadi jalur kendalinya sama
 *   persis dengan notifikasi, headset, dan Android Auto — tidak ada logika
 *   pemutaran kedua yang bisa berbeda perilaku.
 * - Semua kegagalan (artwork, RemoteViews, launcher aneh) ditelan: widget tidak
 *   boleh menjatuhkan proses pemutaran.
 */
class PlayerWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val snap = WidgetState.snapshot
        appWidgetIds.forEach { id ->
            runCatching { appWidgetManager.updateAppWidget(id, buildViews(context, snap, null)) }
        }
        if (snap.artUrl.isBlank()) return
        val pending = goAsync()
        scope.launch {
            val art = loadArtwork(snap.artUrl)
            if (art != null) {
                appWidgetIds.forEach { id ->
                    runCatching { appWidgetManager.updateAppWidget(id, buildViews(context, snap, art)) }
                }
            }
            runCatching { pending.finish() }
        }
    }

    companion object {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val httpClient by lazy { OkHttpClient() }

        /** Sisi terpanjang artwork widget; cukup untuk kotak 52 dp di layar rapat. */
        private const val ART_MAX_PX = 256

        @Volatile
        private var cachedArtUrl: String = ""

        @Volatile
        private var cachedArt: Bitmap? = null

        private const val REQ_OPEN_APP = 10
        private const val REQ_PREV = 11
        private const val REQ_PLAY_PAUSE = 12
        private const val REQ_NEXT = 13

        /** Minta launcher merender ulang semua instance widget ini. */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetReceiver::class.java))
                if (ids.isEmpty()) return
                val intent = Intent(context, PlayerWidgetReceiver::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        private fun buildViews(context: Context, snap: WidgetSnapshot, art: Bitmap?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(
                R.id.widget_title,
                snap.title.ifBlank { context.getString(R.string.widget_empty_title) },
            )
            views.setTextViewText(
                R.id.widget_artist,
                snap.artist.ifBlank { context.getString(R.string.common_youtube) },
            )
            if (art != null) {
                views.setImageViewBitmap(R.id.widget_art, art)
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_note)
            }
            views.setImageViewResource(
                R.id.widget_play,
                if (snap.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setOnClickPendingIntent(R.id.widget_prev, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, REQ_PREV))
            views.setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, REQ_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT, REQ_NEXT))
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            return views
        }

        private fun mediaButton(context: Context, keyCode: Int, requestCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName(context, PlaybackService::class.java)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            return PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, PlaybackService::class.java)
            return PendingIntent.getActivity(
                context,
                REQ_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Ambil artwork thumbnail tanpa Coil: RemoteViews butuh Bitmap perangkat
         * lunak biasa, dan widget hanya menampilkan SATU gambar pada satu waktu,
         * jadi cache satu-entri di memori sudah cukup dan membuat tombol
         * putar/jeda tidak mengunduh ulang gambar yang sama.
         */
        private fun loadArtwork(url: String): Bitmap? {
            if (url.isBlank()) return null
            cachedArt?.let { if (cachedArtUrl == url) return it }
            val decoded = runCatching {
                val call = httpClient.newCall(Request.Builder().url(url).build())
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        null
                    } else {
                        response.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                    }
                }
            }.getOrNull() ?: return null
            val art = if (decoded.width > ART_MAX_PX || decoded.height > ART_MAX_PX) {
                runCatching { Bitmap.createScaledBitmap(decoded, ART_MAX_PX, ART_MAX_PX, true) }.getOrDefault(decoded)
            } else {
                decoded
            }
            cachedArtUrl = url
            cachedArt = art
            return art
        }
    }
}
