/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ------------------------------------------------------------------
// "Smooth motion blur" LYREON.
//
// Hasil riset (September 2026) yang mendasari desain ini:
//  1. `Modifier.blur` / `RenderEffect` hanya berfungsi di Android 12+ (API 31);
//     di bawah itu efek diabaikan diam-diam — jadi wajib ada guard eksplisit.
//  2. Blur gerakan yang benar dikendalikan oleh KECEPATAN NYATA, bukan durasi
//     tetap: pengali blur dihitung dari kecepatan lalu dipetakan ke [0,1],
//     sehingga blur memudar sendiri saat gerakan melambat (tidak "menempel").
//  3. Nilai animasi harus dibaca di FASE DRAW (`Modifier.graphicsLayer { }`),
//     bukan lewat recomposition — inilah anjuran resmi Compose untuk animasi.
//  4. AGSL/RuntimeShader (API 33+) adalah satu-satunya cara mendapat blur
//     rotasional sejati, tetapi shader dikompilasi ulang bila tidak di-`remember`
//     — biaya per frame yang tidak sepadan untuk transisi layar. Kita pakai
//     `RenderEffect.createBlurEffect` (dibuat sekali per radius terkuantisasi
//     dan di-cache) sehingga tidak ada kompilasi shader sama sekali.
//
// DUA lapis, dua sumber gerak:
//  A. TRANSISI LAYAR — [pageMotionBlur]: blur menempel pada HALAMAN itu
//     sendiri, radius-nya diturunkan langsung dari progres transisi
//     halaman (NavHost), bukan animasi/`pulse` terpisah di kontainer.
//     Halaman yang bergerak ikut blur; halaman diam tajam. Ini transisi
//     motion blur sungguhan, bukan "kedipan blur" di seluruh layar.
//  B. GULIR CEPAT — [scrollMotionBlur]: koneksi nested scroll menerjemahkan
//     kecepatan gulir menjadi blur kecil pada kontainer daftar.
//
// Aturan biaya (lihat notes/03 §4):
//  - maksimal SATU lapis blur per layar, tidak pernah di dalam item list;
//  - saat diam, `renderEffect = null` → tidak ada lapisan offscreen sama sekali,
//    jadi biaya saat idle adalah NOL;
//  - mati total bila reduce motion aktif atau perangkat rendah memori.
// ------------------------------------------------------------------

/** Radius maksimum blur saat transisi layar (nilai tertinggi, sesaat). */
val MotionBlurTransitionRadius: Dp = 26.dp

/**
 * Radius efektif maksimum saat gulir cepat. Lapisan blur dipakai bersama dengan
 * transisi layar ([MotionBlurTransitionRadius]); gulir dibatasi lewat target
 * maksimum 0.28 di [ScrollBlurConnection] sehingga hasilnya ≈ 4–5dp.
 */
val MotionBlurScrollRadius: Dp = 16.dp

/**
 * Kecepatan gulir (px/ms) saat blur mulai muncul. Di bawah ini layar dianggap
 * "tenang" dan tetap tajam — blur hanya untuk gerakan yang benar-benar cepat.
 */
private const val SCROLL_SPEED_THRESHOLD_PX_PER_MS = 3.6f

private val effectCache = HashMap<Int, androidx.compose.ui.graphics.RenderEffect>()

/**
 * Blur Gaussian terkuantisasi (langkah 1 px) + di-cache: membuat `RenderEffect`
 * tiap frame akan mengalokasikan objek native berulang kali tanpa perlu.
 */
private fun blurEffectFor(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val key = radiusPx.toInt().coerceIn(1, 64)
    return effectCache.getOrPut(key) {
        RenderEffect
            .createBlurEffect(key.toFloat(), key.toFloat(), Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    }
}

/** Cache blur TERARAH (radius X ≠ Y): kunci gabungan (rx shl 16) or ry. */
private val directionalEffectCache = HashMap<Long, androidx.compose.ui.graphics.RenderEffect>()

/**
 * Blur gerak horizontal ala iOS: dominan di sumbu X (arah geser halaman),
 * tipis di sumbu Y. Inilah yang membuat transisi terlihat "meluncur", bukan
 * sekadar memudar.
 */
private fun directionalBlurEffectFor(
    radiusXPx: Float,
    radiusYPx: Float,
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val rx = radiusXPx.toInt().coerceIn(1, 64)
    val ry = radiusYPx.toInt().coerceIn(1, 64)
    val key = (rx.toLong() shl 16) or ry.toLong()
    return directionalEffectCache.getOrPut(key) {
        RenderEffect
            .createBlurEffect(rx.toFloat(), ry.toFloat(), Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    }
}

/** Perangkat ini sanggup menanggung blur lapisan penuh tanpa menjatuhkan frame? */
fun deviceSupportsMotionBlur(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
    return runCatching { !am.isLowRamDevice }.getOrDefault(true)
}

/**
 * MOTION BLUR TRANSISI LAYAR (pola A di atas).
 *
 * Dipasang pada ROOT tiap halaman NavHost bersama scope animasi halaman itu
 * (`this@composable`). Progres transisi (PreEnter/Visible/PostExit) dianimasikan
 * dengan spring yang sama seperti geseran halaman, lalu radius blur diturunkan
 * dari jarak menuju posisi diam:
 *
 *    amount = 1 − progres   →  baru masuk/keluar = blur penuh,
 *                              halaman mantap = tajam sempurna (tanpa layer).
 *
 * @param scope scope AnimatedVisibility milik entry NavHost halaman ini.
 * @param enabled gabungan setelan pengguna + reduce motion (guard perangkat
 *   dicek otomatis di dalam sini).
 */
@Composable
fun Modifier.pageMotionBlur(
    scope: AnimatedVisibilityScope,
    maxRadius: Dp = MotionBlurTransitionRadius,
    enabled: Boolean = true,
): Modifier {
    val context = LocalContext.current
    val supported = remember(context, enabled) {
        enabled && deviceSupportsMotionBlur(context)
    } && !reduceMotionEnabled
    if (!supported) return this

    // Progres 0→1 saat halaman masuk (PreEnter→Visible), 1→0 saat keluar
    // (Visible→PostExit). Spring sengaja sama dengan spec geser layar di
    // Motion.kt supaya kurva blur mengikuti kurva geser.
    val progress by scope.transition.animateFloat(
        transitionSpec = {
            spring(
                dampingRatio = LyreonMotion.dampingCalm,
                stiffness = LyreonMotion.stiffnessBrisk,
            )
        },
        label = "pageMotionBlurProgress",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 0f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> 0f
        }
    }

    return graphicsLayer {
        val amount = (1f - progress).coerceIn(0f, 1f)
        renderEffect = if (amount > 0.04f) {
            val px = amount * maxRadius.toPx()
            // Dominan horizontal: halaman bergerak menyamping di NavHost.
            directionalBlurEffectFor(px, px * 0.34f)
        } else {
            null
        }
        // Peregangan horizontal kecil mengikuti gerak — kesan "meluncur"
        // ala iOS: halaman baru tampak menjulur masuk, lalu mengunci pas.
        scaleX = 1f + amount * 0.05f
    }
}

/**
 * Sumber kebenaran satu lapis blur: `amount` 0..1, dibaca di fase draw.
 *
 * Nilai target ditulis lewat [setTarget] (sekadar menulis snapshot state —
 * tanpa alokasi, aman dipanggil tiap frame dari koneksi nested scroll).
 */
/** Jeda tanpa pembaruan target yang dianggap "gerakan sudah berhenti". */
private const val IDLE_RELEASE_NS = 110_000_000L

@Stable
class MotionBlurState {

    private val target = mutableFloatStateOf(0f)
    private val animatable = Animatable(0f)

    @Volatile
    private var lastUpdateNs = 0L

    /** Nilai efektif saat ini (0..1). Hanya dibaca di fase draw. */
    val amount: Float get() = animatable.value

    /** Minta tingkat blur baru; animasi menuju nilai itu dijalankan [run]. */
    fun setTarget(value: Float) {
        target.floatValue = value.coerceIn(0f, 1f)
        lastUpdateNs = System.nanoTime()
    }

    /** Lonjakan singkat 0 lalu 1 (transisi layar); [release] menurunkannya lagi. */
    fun pulse() {
        setTarget(1f)
    }

    /** Kembalikan ke tajam. */
    fun release() {
        target.floatValue = 0f
        lastUpdateNs = System.nanoTime()
    }

    /**
     * Jalankan sekali per [MotionBlurState] di dalam `LaunchedEffect`.
     *
     * Dua tugas:
     *  1. animasi menuju target — naik cepat (blur harus sudah terlihat begitu
     *     gerakan mulai), turun lebih lambat (blur yang hilang mendadak
     *     terlihat seperti kedipan);
     *  2. watchdog yang melepas target bila tidak ada pembaruan selama beberapa
     *     frame. Ini pengganti callback "fling selesai": `NestedScrollConnection`
     *     tidak punya `onPostFling` di semua versi Compose, dan mengikat tanda
     *     tangannya membuat berkas ini gagal dikompilasi begitu BOM Compose naik
     *     (terjadi di CI 34087499598). Watchdog hanya bangun selama blur aktif,
     *     jadi tidak ada kerja tambahan saat idle.
     */
    suspend fun run() {
        coroutineScope {
            launch {
                snapshotFlow { target.floatValue }.collect { value ->
                    val spec = if (value > animatable.value) tween<Float>(70) else tween<Float>(200)
                    animatable.animateTo(value, spec)
                }
            }
            launch {
                snapshotFlow { target.floatValue > 0f }.collect { active ->
                    if (!active) return@collect
                    while (target.floatValue > 0f) {
                        delay(50L)
                        if (System.nanoTime() - lastUpdateNs > IDLE_RELEASE_NS) {
                            target.floatValue = 0f
                            break
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberMotionBlurState(): MotionBlurState {
    val state = remember { MotionBlurState() }
    LaunchedEffect(state) { state.run() }
    return state
}

/**
 * Pasang lapisan blur pada satu kontainer. `maxRadius` adalah radius saat
 * `amount` = 1; saat `amount` ≈ 0 tidak ada `RenderEffect` sama sekali.
 *
 * Untuk gulir cepat: pasang dengan [MotionBlurScrollRadius] (pola B).
 */
fun Modifier.motionBlurLayer(
    state: MotionBlurState,
    maxRadius: Dp = MotionBlurScrollRadius,
    enabled: Boolean = true,
): Modifier = graphicsLayer {
    val amount = if (enabled) state.amount else 0f
    renderEffect = if (amount > 0.03f) {
        blurEffectFor(amount * maxRadius.toPx())
    } else {
        null
    }
}

/**
 * Koneksi nested scroll yang menerjemahkan kecepatan gulir menjadi target blur.
 *
 * Dipasang di INDUK daftar (bukan di item): `LazyColumn` meneruskan nested
 * scroll ke atas, jadi satu koneksi melayani seluruh daftar tanpa biaya per item.
 * Blur dilepas oleh watchdog [MotionBlurState.run] begitu tidak ada pembaruan
 * kecepatan, jadi tidak pernah "menempel" di layar yang berhenti menggulir.
 */
private class ScrollBlurConnection(
    private val state: MotionBlurState,
    /**
     * Target maksimum yang boleh diminta gulir. Satu lapis blur dipakai bersama
     * oleh transisi layar dan gulir; transisi boleh penuh (1.0), gulir dibatasi
     * karena area yang diblur jauh lebih lama terlihat.
     */
    private val maxTarget: Float = 0.28f,
) : NestedScrollConnection {

    private var lastNs = 0L

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val now = System.nanoTime()
        // dt dibatasi: jeda panjang (mis. aplikasi kembali ke depan) tidak boleh
        // dibaca sebagai "kecepatan nol" yang menahan blur di nilai lama.
        val dtMs = if (lastNs == 0L) {
            16.6f
        } else {
            ((now - lastNs) / 1_000_000f).coerceIn(2f, 120f)
        }
        lastNs = now

        val pxPerMs = (abs(consumed.y) + abs(available.y)) / dtMs
        val over = pxPerMs - SCROLL_SPEED_THRESHOLD_PX_PER_MS
        val raw = if (over <= 0f) 0f else over / SCROLL_SPEED_THRESHOLD_PX_PER_MS
        state.setTarget(raw * maxTarget)
        return Offset.Zero
    }

    // Tidak ada onPostFling di sini dengan sengaja: pelepasan blur saat gulir
    // berhenti ditangani watchdog di MotionBlurState.run(), sehingga berkas ini
    // tidak bergantung pada bentuk callback fling yang berubah antar versi Compose.
}

/**
 * Modifier siap pakai untuk kontainer yang bisa digulir. `enabled` harus
 * menggabungkan setelan pengguna, reduce motion, dan [deviceSupportsMotionBlur].
 */
@Composable
fun Modifier.scrollMotionBlur(state: MotionBlurState, enabled: Boolean): Modifier {
    val context = LocalContext.current
    val supported = remember(context) { enabled && deviceSupportsMotionBlur(context) }
    val connection = remember(state) { ScrollBlurConnection(state) }
    return if (supported) this.nestedScroll(connection) else this
}
