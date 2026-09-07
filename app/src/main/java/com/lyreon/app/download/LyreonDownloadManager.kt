/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.download

import android.content.Context
import android.os.Environment
import com.lyreon.app.core.ServiceLocator
import com.lyreon.app.data.db.DownloadEntity
import com.lyreon.app.data.db.DownloadState
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.yt.LyreonHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Mesin unduhan audio-only Lyreon.
 * - Resolve stream URL (mengikuti kualitas audio di pengaturan)
 * - Salin streaming via OkHttp ke penyimpanan app (Android/data/.../files/Music/Lyreon)
 *   → tidak butuh izin storage di API 29+ dan file tetap ada "jangka panjang".
 * - State tersimpan di Room: QUEUED → DOWNLOADING → DONE / ERROR / CANCELED.
 */
class LyreonDownloadManager(
    private val context: Context,
    private val locator: ServiceLocator,
) {
    private val runMutex = Mutex()

    private val downloadsDir: File
        get() = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir)
            .let { File(it, "Lyreon") }
            .apply { mkdirs() }

    /** Antrekan satu track. UI memanggil ini; service yang bekerja. */
    suspend fun enqueue(track: LyreonTrack) {
        val dao = locator.db.downloadDao()
        val existing = dao.byIdOnce(track.videoId)
        if (existing?.state == DownloadState.DONE &&
            existing.filePath?.let { File(it).exists() } == true
        ) return

        dao.upsert(
            DownloadEntity(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationSec = track.durationSec,
                state = DownloadState.QUEUED,
                filePath = null,
                bytesTotal = 0L,
                bytesDone = 0L,
                errorMessage = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
        DownloadService.start(context)
    }

    /** Dipanggil DownloadService. Mengunduh antrean satu per satu sampai habis. */
    suspend fun processQueue(
        onProgress: (active: Int, currentTitle: String?, progress: Float) -> Unit,
    ) {
        if (!runMutex.tryLock()) return
        progressCallback = onProgress
        try {
            val dao = locator.db.downloadDao()
            while (true) {
                val next = dao.nextQueued() ?: break
                val active = dao.activeCount()
                onProgress(active, next.title, Float.NaN)

                dao.upsert(next.copy(state = DownloadState.DOWNLOADING))
                try {
                    val file = downloadTrack(next)
                    dao.upsert(
                        next.copy(
                            state = DownloadState.DONE,
                            filePath = file.absolutePath,
                            bytesTotal = file.length(),
                            bytesDone = file.length(),
                            errorMessage = null,
                        ),
                    )
                    onProgress(active - 1, null, 1f)
                } catch (ce: CancelledDownload) {
                    dao.upsert(
                        next.copy(
                            state = DownloadState.CANCELED,
                            bytesDone = 0L,
                            errorMessage = "Dibatalkan",
                        ),
                    )
                } catch (e: Exception) {
                    dao.upsert(
                        next.copy(
                            state = DownloadState.ERROR,
                            errorMessage = e.message ?: "Unduhan gagal",
                        ),
                    )
                }
            }
        } finally {
            progressCallback = null
            runMutex.unlock()
        }
    }

    private class CancelledDownload : IOException()

    /**
     * Unduh satu lagu ke penyimpanan app.
     *
     * Dua jalur, satu hasil (satu file utuh):
     *  - **progresif** (URL langsung) → salin response HTTP apa adanya;
     *  - **manifest HLS** → ambil init segment + seluruh segmen lalu satukan
     *    ([HlsFlatDownloader]). Jalur ini yang membuat lagu "HLS-only" — kasus
     *    yang makin sering terjadi untuk pengguna anonim — tetap bisa diunduh.
     */
    private suspend fun downloadTrack(entry: DownloadEntity): File = withContext(Dispatchers.IO) {
        val resolved = locator.youtube.resolveCachedBlocking(entry.videoId)
        // URL cadangan terakhir (ditolak CDN, kemungkinan cuma pratinjau ~1 MiB) tidak
        // boleh diunduh: file hasilnya terpotong diam-diam. Lebih baik gagal dengan alasan
        // jelas — pemutaran tetap memakai URL itu, unduhan tidak.
        if (!resolved.validated) {
            throw IOException(
                "URL stream untuk ${entry.videoId} tidak lolos verifikasi CDN " +
                    "(kemungkinan pratinjau terpotong) - unduhan dibatalkan",
            )
        }
        // hapus varian lama dengan ekstensi berbeda
        downloadsDir.listFiles { f -> f.name.startsWith("${entry.videoId}.") }
            ?.forEach { it.delete() }
        val tmp = File(downloadsDir, "${entry.videoId}.part")
        val dao = locator.db.downloadDao()

        val suffix = if (resolved.isManifest) {
            var lastPush = 0L
            val result = HlsFlatDownloader.downloadFlat(
                client = LyreonHttp.streamClient,
                manifestUrl = resolved.url,
                target = tmp,
                isCancelled = { dao.byIdOnce(entry.videoId)?.state == DownloadState.CANCELED },
                onProgress = { done, estimated, _, _ ->
                    val now = System.currentTimeMillis()
                    if (now - lastPush > 400L) {
                        lastPush = now
                        dao.upsert(
                            entry.copy(
                                state = DownloadState.DOWNLOADING,
                                bytesTotal = estimated,
                                bytesDone = done,
                            ),
                        )
                        onProgressSafe(estimated, done, entry.title)
                    }
                },
            )
            // Wadah hasil gabungan segmen: fMP4 → .mp4, MPEG-TS → .ts
            result.container.suffix
        } else {
            writeProgressive(entry, resolved.url, resolved.fallbackUrl, tmp)
            resolved.suffix.ifBlank { "m4a" }
        }

        val outFile = File(downloadsDir, "${entry.videoId}.$suffix")
        if (!tmp.renameTo(outFile)) {
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
        }
        outFile
    }

    /**
     * Salin satu URL progresif ke [tmp] dengan laporan progres + cek pembatalan.
     * Bila URL utama ditolak (403/404/429) dan ada kandidat dari keluarga format
     * lain, kandidat itu dicoba sebelum menyerah.
     */
    private suspend fun writeProgressive(
        entry: DownloadEntity,
        url: String,
        fallbackUrl: String?,
        tmp: File,
    ) {
        val dao = locator.db.downloadDao()
        val request = Request.Builder().url(url).get().build()
        var response = LyreonHttp.streamClient.newCall(request).execute()
        if (!response.isSuccessful &&
            response.code in intArrayOf(403, 404, 429) &&
            !fallbackUrl.isNullOrBlank()
        ) {
            response.close()
            response = LyreonHttp.streamClient.newCall(
                Request.Builder().url(fallbackUrl).get().build(),
            ).execute()
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 403) locator.youtube.invalidate(entry.videoId)
                throw IOException("HTTP ${resp.code} saat mengunduh")
            }
            val body = resp.body
            val total = body.contentLength().takeIf { it > 0 } ?: 0L

            tmp.outputStream().buffered().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastUiPush = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read

                        val now = System.currentTimeMillis()
                        if (now - lastUiPush > 400L) {
                            lastUiPush = now
                            dao.upsert(
                                entry.copy(
                                    state = DownloadState.DOWNLOADING,
                                    bytesTotal = total,
                                    bytesDone = done,
                                ),
                            )
                            onProgressSafe(total, done, entry.title)

                            // cek pembatalan via DB
                            val state = dao.byIdOnce(entry.videoId)?.state
                            if (state == DownloadState.CANCELED) {
                                out.flush()
                                throw CancelledDownload()
                            }
                        }
                    }
                    out.flush()
                }
            }
        }
    }

    private suspend fun onProgressSafe(total: Long, done: Long, title: String?) {
        // callback notifikasi opsional; kesalahan UI tak boleh menghentikan unduhan
        runCatching {
            val p = if (total > 0) done.toFloat() / total.toFloat() else Float.NaN
            progressCallback?.invoke(1, title, p)
        }
    }

    @Volatile
    private var progressCallback: ((Int, String?, Float) -> Unit)? = null

    fun setProgressCallback(cb: ((Int, String?, Float) -> Unit)?) {
        progressCallback = cb
    }

    suspend fun cancel(videoId: String) {
        val dao = locator.db.downloadDao()
        val row = dao.byIdOnce(videoId) ?: return
        if (row.state == DownloadState.QUEUED || row.state == DownloadState.DOWNLOADING) {
            dao.upsert(row.copy(state = DownloadState.CANCELED, errorMessage = "Dibatalkan"))
        }
    }

    suspend fun remove(videoId: String) {
        val dao = locator.db.downloadDao()
        cancel(videoId)
        val row = dao.byIdOnce(videoId)
        row?.filePath?.let { runCatching { File(it).delete() } }
        downloadsDir.listFiles { f -> f.name.startsWith("$videoId.") }?.forEach {
            runCatching { it.delete() }
        }
        dao.delete(videoId)
    }

    suspend fun retry(videoId: String) {
        val dao = locator.db.downloadDao()
        val row = dao.byIdOnce(videoId) ?: return
        dao.upsert(row.copy(state = DownloadState.QUEUED, errorMessage = null, bytesDone = 0L))
        delay(50L)
        DownloadService.start(context)
    }
}
