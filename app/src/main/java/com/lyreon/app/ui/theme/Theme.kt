/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.pow

// LYREON adalah aplikasi dark-first: hitam pekat + crimson logo.
// Empat mode tersedia: SISTEM, GELAP, HITAM (OLED #000 murni), dan KERTAS
// (terang). Warna aksen bebas dipilih lewat [accentArgb] (-1 = crimson bawaan),
// dan Material You (warna dinamis) bisa mengambil alih seluruh palet di
// Android 12+ tanpa merusak token lama — lihat [lyreonPaletteFromScheme].

enum class ThemeMode { SYSTEM, DARK, LIGHT, BLACK }

fun fontFamilyForKey(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

private fun darkScheme(p: LyreonPalette) = darkColorScheme(
    primary = p.crimson,
    onPrimary = p.textBright,
    primaryContainer = p.crimson,
    onPrimaryContainer = p.textBright,
    secondary = p.rose,
    onSecondary = p.background,
    secondaryContainer = p.elevated,
    onSecondaryContainer = p.textPrimary,
    tertiary = p.rose,
    onTertiary = p.background,
    background = p.background,
    onBackground = p.textPrimary,
    surface = p.background,
    onSurface = p.textPrimary,
    surfaceVariant = p.surface,
    onSurfaceVariant = p.textSecondary,
    surfaceContainerLowest = p.background,
    surfaceContainerLow = p.surface,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.elevated,
    surfaceContainerHighest = p.elevated,
    surfaceDim = p.background,
    surfaceBright = p.elevated,
    // Tanpa tint primary: permukaan tidak boleh terlihat kemerahan.
    surfaceTint = p.surface,
    outline = p.line,
    outlineVariant = p.lineSoft,
    inverseSurface = p.textPrimary,
    inverseOnSurface = p.background,
    inversePrimary = p.crimson,
    scrim = p.scrim,
)

private fun lightScheme(p: LyreonPalette) = lightColorScheme(
    primary = p.crimson,
    onPrimary = p.textBright,
    primaryContainer = p.crimson,
    onPrimaryContainer = p.textBright,
    secondary = p.rose,
    onSecondary = p.textPrimary,
    secondaryContainer = p.elevated,
    onSecondaryContainer = p.textPrimary,
    tertiary = p.crimson,
    onTertiary = p.textBright,
    background = p.background,
    onBackground = p.textPrimary,
    surface = p.surface,
    onSurface = p.textPrimary,
    surfaceVariant = p.elevated,
    onSurfaceVariant = p.textSecondary,
    surfaceContainerLowest = p.elevated,
    surfaceContainerLow = p.surface,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.elevated,
    surfaceContainerHighest = p.elevated,
    surfaceDim = p.background,
    surfaceBright = p.elevated,
    surfaceTint = p.surface,
    outline = p.line,
    outlineVariant = p.lineSoft,
    inverseSurface = p.textPrimary,
    inverseOnSurface = p.background,
    inversePrimary = p.crimson,
    scrim = p.scrim,
)

/**
 * Paksa skema Material You berlatar #000 murni untuk mode HITAM (panel AMOLED):
 * warna aksen tetap dari wallpaper, hanya permukaannya yang digelapkan.
 */
private fun ColorScheme.withBlackBase(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101014),
    surfaceContainerHigh = Color(0xFF17171C),
    surfaceContainerHighest = Color(0xFF1E1E24),
    surfaceVariant = Color(0xFF17171C),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF17171C),
    surfaceTint = Color(0xFF0A0A0A),
    scrim = Color(0xF2000000),
)

/**
 * Ganti warna aksen sebuah skema (aksen kustom pengguna atau palet sampul lagu)
 * tanpa mengubah permukaan — "Material You di atas, warna sampul di penekanan".
 *
 * `onPrimary` dihitung ulang dari luminansi aksen supaya teks di atas tombol
 * utama tetap terbaca (kontras >= 4.5:1) baik untuk aksen terang maupun gelap.
 */
private fun ColorScheme.withAccent(accent: Color): ColorScheme {
    val onAccent = if (accent.luminance() > 0.55f) Color(0xFF101014) else Color.White
    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent,
        onPrimaryContainer = onAccent,
        inversePrimary = accent,
    )
}

/** Luminansi relatif (WCAG) — dipakai memilih warna teks di atas aksen. */
private fun Color.luminance(): Float {
    fun channel(c: Float): Float = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

/**
 * Tema utama aplikasi.
 *
 * @param dynamicColor Material You (warna wallpaper). Hanya berlaku di Android 12+;
 *   di bawah itu otomatis diabaikan.
 * @param artworkAccentArgb aksen dari palet sampul lagu aktif
 *   ([NO_ARTWORK_ACCENT] = tidak ada). Menang atas [accentArgb] karena lebih
 *   baru niatnya: warna mengikuti lagu yang sedang diputar.
 * @param reduceMotion preferensi pengguna "kurangi gerakan"; digabung dengan
 *   status animator sistem lalu disediakan lewat [LocalReduceMotion].
 */
@Composable
fun LyreonTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentArgb: Int = -1,
    fontKey: String = "default",
    dynamicColor: Boolean = true,
    artworkAccentArgb: Int = NO_ARTWORK_ACCENT,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK, ThemeMode.BLACK -> true
        ThemeMode.LIGHT -> false
    }
    val black = themeMode == ThemeMode.BLACK
    // PENTING: warna opaque ARGB (0xFFxxxxxx) bernilai NEGATIF sebagai Int —
    // sentinel "bawaan" adalah tepat -1, bukan "negatif".
    val accent = if (accentArgb != -1) Color(accentArgb) else LyreonCrimsonDefault
    // null = tidak ada penggantian aksen (pakai warna skema apa adanya).
    val accentOverride: Color? = when {
        artworkAccentArgb != NO_ARTWORK_ACCENT -> Color(artworkAccentArgb)
        accentArgb != -1 -> Color(accentArgb)
        else -> null
    }
    val materialYou = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val scheme = remember(context, dark, black, accent, accentOverride, materialYou) {
        val base = when {
            materialYou -> {
                val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                if (black) dynamic.withBlackBase() else dynamic
            }
            dark -> darkScheme(if (black) lyreonBlackPalette(accent) else lyreonDarkPalette(accent))
            else -> lightScheme(lyreonLightPalette(accent))
        }
        if (accentOverride == null) base else base.withAccent(accentOverride)
    }

    val palette = remember(scheme, dark, black, accent, accentOverride, materialYou) {
        val effectiveAccent = accentOverride ?: accent
        when {
            // Skema sudah membawa aksen di primary → palette ikut dari skema.
            materialYou -> lyreonPaletteFromScheme(scheme, effectiveAccent, blackBase = black)
            black -> lyreonBlackPalette(effectiveAccent)
            dark -> lyreonDarkPalette(effectiveAccent)
            else -> lyreonLightPalette(effectiveAccent)
        }
    }

    val typography = remember(fontKey) { lyreonTypography(fontFamilyForKey(fontKey)) }

    // Hormati juga "animation scale = 0" di Opsi Developer.
    val systemReduced = remember { !systemAnimatorsEnabled() }
    val reduced = reduceMotion || systemReduced

    CompositionLocalProvider(
        LocalLyreonPalette provides palette,
        LocalReduceMotion provides reduced,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = LyreonShapes,
            content = content,
        )
    }
}
