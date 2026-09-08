/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset

// ------------------------------------------------------------------
// Gerakan LYREON — lihat notes/04-spesifikasi-tema-baru.md §Gerak
//
// Aturan: spring tenang (dampingRatio 0.85–0.9) untuk elemen yang "hidup",
// tween pendek (90–350 ms) untuk chrome. Bila pengguna mengaktifkan
// "Kurangi gerakan" — atau sistem mematikan animator — semua spec di bawah
// ini runtuh menjadi ~instan, jadi layar tidak perlu memeriksa sendiri.
//
// PENTING: jangan panggil `tween(...)` / `spring(...)` mentah di layar;
// selalu lewat helper di sini supaya reduceMotion benar-benar dihormati.
// ------------------------------------------------------------------

/** Preferensi "kurangi gerakan" (pengaturan app ATAU sistem). */
val LocalReduceMotion = staticCompositionLocalOf { false }

val reduceMotionEnabled: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalReduceMotion.current

/** Animator sistem aktif? (pengaturan developer "animation scale = 0" → false) */
fun systemAnimatorsEnabled(): Boolean =
    runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

object LyreonMotion {
    /** Transisi mikro: tekan, centang, indikator. */
    const val fast = 90

    /** Transisi standar chrome. */
    const val normal = 180

    /** Pergantian layar / lembar. */
    const val slow = 260

    /** Elemen besar (intro, now playing). Maksimum yang masih terasa gesit. */
    const val deliberate = 350

    /** Spring "hidup" — sedikit overshoot. */
    const val dampingSoft = 0.85f

    /** Spring tenang — tanpa overshoot berarti. */
    const val dampingCalm = 0.9f

    const val stiffnessBrisk = 380f
    const val stiffnessGentle = 300f
}

/** Spring tema; runtuh jadi praktis-instan saat reduce motion. */
@Composable
fun <T> lyreonSpring(
    dampingRatio: Float = LyreonMotion.dampingCalm,
    stiffness: Float = LyreonMotion.stiffnessGentle,
): SpringSpec<T> =
    if (LocalReduceMotion.current) {
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 20_000f)
    } else {
        spring(dampingRatio = dampingRatio, stiffness = stiffness)
    }

/** Tween tema; 0 ms saat reduce motion (tetap mengubah nilai, tanpa animasi). */
@Composable
fun <T> lyreonTween(
    durationMs: Int = LyreonMotion.normal,
    delayMs: Int = 0,
): TweenSpec<T> =
    if (LocalReduceMotion.current) {
        tween(durationMillis = 0, delayMillis = 0)
    } else {
        tween(durationMillis = durationMs, delayMillis = delayMs)
    }

/** Spec alpha (fade) — dipakai `animateFloatAsState` & transisi. */
@Composable
fun lyreonFade(durationMs: Int = LyreonMotion.normal): TweenSpec<Float> = lyreonTween(durationMs)

/** Masuknya chrome (mini player, bar bawah): geser + pudar, atau pudar saja. */
@Composable
fun lyreonChromeEnter(durationMs: Int = LyreonMotion.slow): EnterTransition =
    if (LocalReduceMotion.current) {
        fadeIn(tween(0))
    } else {
        slideInVertically(lyreonTween<IntOffset>(durationMs)) { it } + fadeIn(lyreonTween(durationMs))
    }

/** Keluarnya chrome — pasangan [lyreonChromeEnter]. */
@Composable
fun lyreonChromeExit(durationMs: Int = LyreonMotion.normal): ExitTransition =
    if (LocalReduceMotion.current) {
        fadeOut(tween(0))
    } else {
        slideOutVertically(lyreonTween<IntOffset>(durationMs)) { it } + fadeOut(lyreonTween(durationMs))
    }

// ------------------------------------------------------------------
// Transisi antar-layar (NavHost).
//
// Ditulis sebagai fungsi BIASA (bukan @Composable) karena lambda transisi
// NavHost tidak berjalan di konteks komposisi; status reduce motion dibaca
// sekali di layar lalu dioper ke sini.
//
// Rasa iOS: geser kecil (1/6 lebar, bukan seluruh layar) + pudar, spring
// tenang. Geser besar ala Material lama terasa berat dan memicu jank di
// perangkat menengah.
// ------------------------------------------------------------------

/** Layar baru masuk (maju): geser dari kanan + pudar. */
fun lyreonScreenEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(tween(0))
    } else {
        slideInHorizontally(
            spring(dampingRatio = LyreonMotion.dampingCalm, stiffness = LyreonMotion.stiffnessBrisk),
        ) { it / 6 } + fadeIn(tween(LyreonMotion.slow))
    }

/** Layar lama keluar (maju): geser sedikit ke kiri + pudar. */
fun lyreonScreenExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(tween(0))
    } else {
        slideOutHorizontally(
            spring(dampingRatio = LyreonMotion.dampingCalm, stiffness = LyreonMotion.stiffnessBrisk),
        ) { -it / 12 } + fadeOut(tween(LyreonMotion.normal))
    }

/** Layar sebelumnya kembali terlihat (mundur): geser dari kiri + pudar. */
fun lyreonScreenPopEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(tween(0))
    } else {
        slideInHorizontally(
            spring(dampingRatio = LyreonMotion.dampingCalm, stiffness = LyreonMotion.stiffnessBrisk),
        ) { -it / 12 } + fadeIn(tween(LyreonMotion.slow))
    }

/** Layar aktif keluar (mundur): geser ke kanan + pudar. */
fun lyreonScreenPopExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(tween(0))
    } else {
        slideOutHorizontally(
            spring(dampingRatio = LyreonMotion.dampingCalm, stiffness = LyreonMotion.stiffnessBrisk),
        ) { it / 6 } + fadeOut(tween(LyreonMotion.normal))
    }

// ------------------------------------------------------------------
// Hero artwork mini player ⇄ Now Playing.
//
// TIDAK memakai `Modifier.sharedBounds` lagi. Percobaan sebelumnya menyambung
// artwork mini player (hidup di `Scaffold.bottomBar`) dengan artwork Now
// Playing (hidup di dalam NavHost yang dibungkus `graphicsLayer` motion blur)
// lewat satu `SharedContentState`. Dua node di dua cabang hierarki yang
// berbeda, dengan lapisan RenderEffect di antaranya, membuat morph macet dan
// berosilasi tiap kali halaman dibuka — lihat notes/01 §B7.
//
// Gantinya: `NowPlayingScreen` menganimasikan kanvas artwork-nya SENDIRI
// (skala + pudar di fase draw memakai [lyreonSpring]). Satu layar = satu
// animasi, tanpa ketergantungan lintas-hierarki.
// ------------------------------------------------------------------
