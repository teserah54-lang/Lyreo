/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.core.LocaleHelper
import com.lyreon.app.data.settings.AudioQuality
import com.lyreon.app.ui.theme.LyreonAccentSoft
import com.lyreon.app.ui.theme.LyreonHairline
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.ThemeMode
import com.lyreon.app.ui.vm.SettingsViewModel

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOpenLicenses: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 28.dp)) {
                Text(stringResource(R.string.settings_kicker), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = LyreonTextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(40.dp).height(2.dp).background(LyreonCrimson))
            }
        }

        // ---- Profil: nama sapaan (disambut di Home) ----
        item {
            ProfileSection(
                displayName = settings.displayName.ifBlank { "Farizy" },
                onSave = vm::setDisplayName,
            )
        }

        item {
            SettingSection(stringResource(R.string.sec_appearance)) {
                Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                Spacer(Modifier.height(10.dp))
                SegmentedGrid(
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.DARK to stringResource(R.string.theme_dark),
                        ThemeMode.BLACK to stringResource(R.string.theme_black),
                        ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ),
                    selected = settings.themeMode,
                    onSelect = vm::setTheme,
                )
                // Material You hanya ada di Android 12+; di bawah itu barisnya
                // disembunyikan supaya tidak menawarkan sesuatu yang tak jalan.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(Modifier.height(14.dp))
                    ToggleRow(
                        title = stringResource(R.string.dynamic_title),
                        body = stringResource(R.string.dynamic_body),
                        checked = settings.dynamicColor,
                        onChange = vm::setDynamicColor,
                    )
                }
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = stringResource(R.string.artwork_accent_title),
                    body = stringResource(R.string.artwork_accent_body),
                    checked = settings.artworkAccent,
                    onChange = vm::setArtworkAccent,
                )
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.accent_label), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                Spacer(Modifier.height(10.dp))
                AccentPicker(
                    currentArgb = settings.accentArgb,
                    onPick = vm::setAccent,
                )
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.font_label), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                Spacer(Modifier.height(10.dp))
                FontPicker(
                    currentKey = settings.fontKey,
                    onPick = vm::setFont,
                )
            }
        }

        item {
            SettingSection(stringResource(R.string.sec_audio)) {
                Text(stringResource(R.string.quality_label), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.quality_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LyreonTextMuted,
                )
                Spacer(Modifier.height(10.dp))
                SegmentedRow(
                    options = listOf(
                        AudioQuality.HIGH to stringResource(R.string.quality_high),
                        AudioQuality.BALANCED to stringResource(R.string.quality_balanced),
                        AudioQuality.DATA_SAVER to stringResource(R.string.quality_saver),
                    ),
                    selected = settings.audioQuality,
                    onSelect = vm::setQuality,
                )
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = stringResource(R.string.normalize_title),
                    body = stringResource(R.string.normalize_body),
                    checked = settings.normalizeAudio,
                    onChange = vm::setNormalizeAudio,
                )
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = stringResource(R.string.skip_silence_title),
                    body = stringResource(R.string.skip_silence_body),
                    checked = settings.skipSilence,
                    onChange = vm::setSkipSilence,
                )
                if (settings.skipSilence) {
                    Spacer(Modifier.height(14.dp))
                    ToggleRow(
                        title = stringResource(R.string.skip_silence_instant_title),
                        body = stringResource(R.string.skip_silence_instant_body),
                        checked = settings.skipSilenceInstant,
                        onChange = vm::setSkipSilenceInstant,
                    )
                }
            }
        }

        item {
            SettingSection(stringResource(R.string.sec_playback)) {
                ToggleRow(
                    title = stringResource(R.string.autoplay_title),
                    body = stringResource(R.string.autoplay_body),
                    checked = settings.autoplayRelated,
                    onChange = vm::setAutoplay,
                )
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = stringResource(R.string.reduce_title),
                    body = stringResource(R.string.reduce_body),
                    checked = settings.reduceMotion,
                    onChange = vm::setReduceMotion,
                )
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = stringResource(R.string.motion_blur_title),
                    body = stringResource(R.string.motion_blur_body),
                    checked = settings.motionBlur,
                    onChange = vm::setMotionBlur,
                )
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = stringResource(R.string.history_queue_title),
                    body = stringResource(R.string.history_queue_body),
                    checked = settings.historyAutoQueue,
                    onChange = vm::setHistoryAutoQueue,
                )
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = stringResource(R.string.kugou_title),
                    body = stringResource(R.string.kugou_body),
                    checked = settings.kugouEnabled,
                    onChange = vm::setKugou,
                )
            }
        }

        item {
            StreamHealthSection(vm)
        }

        item {
            LanguageSection()
        }

        item {
            SettingSection(stringResource(R.string.sec_data)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, LyreonLine)
                        .background(LyreonSurface.copy(alpha = 0.3f))
                        .clickable { confirmClear = true }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.clear_history_title), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                        Text(
                            stringResource(R.string.clear_history_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = LyreonTextMuted,
                        )
                    }
                    Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
                }
            }
        }

        // ---- Catatan pembaruan Beta (v3.5.0) ----
        item {
            SettingSection(stringResource(R.string.sec_beta_notes)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, LyreonCrimson, RoundedCornerShape(6.dp))
                            .background(LyreonCrimson.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            stringResource(R.string.beta_notes_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = LyreonCrimson,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.beta_notes_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = LyreonTextPrimary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.beta_notes_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LyreonTextMuted,
                )
                Spacer(Modifier.height(10.dp))
                listOf(
                    R.string.beta_note_1,
                    R.string.beta_note_2,
                    R.string.beta_note_3,
                    R.string.beta_note_4,
                    R.string.beta_note_5,
                ).forEach { res ->
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        item {
            SettingSection(stringResource(R.string.sec_about)) {
                Text(stringResource(R.string.about_version_beta, "3.5.0"), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.about_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LyreonTextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.about_engine),
                    style = MaterialTheme.typography.labelSmall,
                    color = LyreonTextMuted,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .border(1.dp, LyreonLine)
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://achfarizy.biz.id")),
                                )
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(stringResource(R.string.about_website), style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .border(1.dp, LyreonCrimson)
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/riznotdev")),
                                )
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(stringResource(R.string.about_donate), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, LyreonLine)
                        .clickable { onOpenLicenses() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(R.string.licenses_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = LyreonTextPrimary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.about_legal),
                    style = MaterialTheme.typography.labelSmall,
                    color = LyreonTextMuted,
                )
            }
        }
    }

    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = com.lyreon.app.ui.theme.LyreonElevated,
            title = { Text(stringResource(R.string.clear_history_confirm), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) },
            text = {
                Text(stringResource(R.string.clear_history_confirm_body), style = MaterialTheme.typography.bodyMedium, color = LyreonTextSecondary)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.clearHistory()
                    confirmClear = false
                }) { Text(stringResource(R.string.clear_history_yes), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
                }
            },
        )
    }
}

/** Profil: nama yang disapa di halaman Home. */
@Composable
private fun ProfileSection(
    displayName: String,
    onSave: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_profile), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(LyreonLine))
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, LyreonLine)
                .background(LyreonSurface.copy(alpha = 0.3f))
                .clickable { editing = true }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                Text(
                    stringResource(R.string.name_edit_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LyreonTextMuted,
                )
            }
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.name_edit_title),
                tint = LyreonCrimson,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (editing) {
        var name by remember { mutableStateOf(displayName) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = com.lyreon.app.ui.theme.LyreonElevated,
            title = { Text(stringResource(R.string.name_edit_title), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LyreonCrimson,
                        unfocusedBorderColor = LyreonLine,
                        cursorColor = LyreonTextPrimary,
                        focusedTextColor = LyreonTextPrimary,
                        unfocusedTextColor = LyreonTextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (name.isNotBlank()) {
                        onSave(name.trim())
                        editing = false
                    }
                }) { Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editing = false }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
                }
            },
        )
    }
}

/** Deretan swatch aksen + slider HSV untuk pilihan bebas. */
@Composable
private fun AccentPicker(
    currentArgb: Int,
    onPick: (Int) -> Unit,
) {
    val presets = listOf(
        intArrayOf(0xFF, 0xE9, 0x45, 0x60),
        intArrayOf(0xFF, 0xFF, 0x52, 0x52),
        intArrayOf(0xFF, 0xFF, 0x70, 0x43),
        intArrayOf(0xFF, 0xFF, 0xB3, 0x00),
        intArrayOf(0xFF, 0x9C, 0xCC, 0x65),
        intArrayOf(0xFF, 0x26, 0xA6, 0x9A),
        intArrayOf(0xFF, 0x29, 0xB6, 0xF6),
        intArrayOf(0xFF, 0x5C, 0x6B, 0xC0),
        intArrayOf(0xFF, 0xAB, 0x47, 0xBC),
        intArrayOf(0xFF, 0xEC, 0x40, 0x7A),
        intArrayOf(0xFF, 0xB0, 0xBE, 0xC5),
    )
    fun argb(a: IntArray): Int =
        (a[0] shl 24) or (a[1] shl 16) or (a[2] shl 8) or a[3]

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val defaultArgb = argb(intArrayOf(0xFF, 0xE9, 0x45, 0x60))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color(defaultArgb))
                    .border(
                        width = if (currentArgb < 0) 3.dp else 1.dp,
                        color = if (currentArgb < 0) LyreonTextPrimary else LyreonLine,
                        shape = androidx.compose.foundation.shape.CircleShape,
                    )
                    .clickable { onPick(-1) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.accent_default),
                style = MaterialTheme.typography.labelSmall,
                color = if (currentArgb < 0) LyreonTextPrimary else LyreonTextMuted,
            )
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(presets.size) { i ->
                val c = argb(presets[i])
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color(c))
                        .border(
                            width = if (currentArgb == c) 3.dp else 1.dp,
                            color = if (currentArgb == c) LyreonTextPrimary else LyreonLine,
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                        .clickable { onPick(c) },
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.accent_custom), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
    Spacer(Modifier.height(6.dp))

    // Slider HSV — pratinjau langsung saat digeser
    val initHsv = remember(currentArgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            if (currentArgb >= 0) currentArgb else argb(intArrayOf(0xFF, 0xE9, 0x45, 0x60)),
            hsv,
        )
        hsv
    }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var value by remember { mutableFloatStateOf(initHsv[2]) }
    val preview = androidx.compose.ui.graphics.Color.hsv(hue, sat, value)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(preview)
                .border(1.dp, LyreonLine, androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Slider(
                value = hue, onValueChange = { hue = it }, valueRange = 0f..360f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = LyreonTextPrimary, activeTrackColor = preview, inactiveTrackColor = LyreonSurface,
                ),
            )
            androidx.compose.material3.Slider(
                value = sat, onValueChange = { sat = it }, valueRange = 0f..1f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = LyreonTextPrimary, activeTrackColor = LyreonCrimson, inactiveTrackColor = LyreonSurface,
                ),
            )
            androidx.compose.material3.Slider(
                value = value, onValueChange = { value = it }, valueRange = 0f..1f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = LyreonTextPrimary, activeTrackColor = LyreonCrimson, inactiveTrackColor = LyreonSurface,
                ),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.accent_apply),
        style = MaterialTheme.typography.labelMedium,
        color = LyreonCrimson,
        modifier = Modifier
            .border(1.dp, LyreonCrimson)
            .clickable {
                var argb = ((255) shl 24) or
                    ((preview.red * 255f).toInt() shl 16) or
                    ((preview.green * 255f).toInt() shl 8) or
                    (preview.blue * 255f).toInt()
                // Putih penuh (0xFFFFFFFF) == -1 == sentinel "bawaan" → geser 1 unit
                if (argb == -1) argb = -2
                onPick(argb)
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Pilihan jenis huruf — label ditampilkan DALAM hurufnya sendiri. */
@Composable
private fun FontPicker(
    currentKey: String,
    onPick: (String) -> Unit,
) {
    val options = listOf(
        "default" to stringResource(R.string.font_default),
        "serif" to "Serif",
        "mono" to "Mono",
        "cursive" to "Cursive",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val active = key == currentKey
            Box(
                modifier = Modifier
                    .border(1.dp, if (active) LyreonCrimson else LyreonLine)
                    .background(
                        if (active) LyreonCrimson.copy(alpha = 0.18f)
                        else LyreonSurface.copy(alpha = 0.25f),
                    )
                    .clickable { onPick(key) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = com.lyreon.app.ui.theme.fontFamilyForKey(key),
                    color = if (active) LyreonTextPrimary else LyreonTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(LyreonLine))
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            SegmentChip(label = label, active = value == selected, onClick = { onSelect(value) })
        }
    }
}

/**
 * Varian [SegmentedRow] yang membungkus ke baris baru (2 kolom) — dipakai untuk
 * pilihan tema yang kini empat butir: SISTEM · GELAP · HITAM · KERTAS.
 */
@Composable
private fun <T> SegmentedGrid(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (value, label) ->
                    Box(Modifier.weight(1f)) {
                        SegmentChip(
                            label = label,
                            active = value == selected,
                            onClick = { onSelect(value) },
                            fill = true,
                        )
                    }
                }
                // Pengisi agar butir ganjil tetap selebar setengah baris.
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Satu butir pilihan: sudut membulat sedang, garis rambut, latar aksen redup. */
@Composable
private fun SegmentChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    fill: Boolean = false,
) {
    val shape = RoundedCornerShape(LyreonRadius.sm)
    Box(
        modifier = Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .border(1.dp, if (active) LyreonCrimson else LyreonHairline, shape)
            .background(if (active) LyreonAccentSoft else LyreonSurface.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = if (fill) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) LyreonTextPrimary else LyreonTextSecondary,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(LyreonRadius.sm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, LyreonHairline, shape)
            .background(LyreonSurface.copy(alpha = 0.3f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = LyreonTextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LyreonTextPrimary,
                checkedTrackColor = LyreonCrimson,
                uncheckedThumbColor = LyreonTextSecondary,
                uncheckedTrackColor = LyreonSurface,
            ),
        )
    }
}


/** Pemilih bahasa — langsung recreate activity agar locale baru aktif. */
@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    val current = LocaleHelper.currentTag(context)
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sec_language), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(LyreonLine))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.language_label), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
        Spacer(Modifier.height(10.dp))
        LocaleHelper.SUPPORTED.forEach { lang ->
            val active = lang.tag == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (active) LyreonCrimson else LyreonLine,
                        RoundedCornerShape(12.dp),
                    )
                    .background(if (active) LyreonCrimson.copy(alpha = 0.15f) else LyreonSurface.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .clickable {
                        LocaleHelper.set(context, lang.tag)
                        findActivity(context)?.recreate()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lang.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = LyreonTextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = LyreonCrimson,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun findActivity(context: android.content.Context): android.app.Activity? {
    var ctx = context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

// ======================================================================
// Kesehatan stream & diagnostik tangga klien
//
// Lyreon ANONIM (tanpa cookie akun): yang bisa ditindaklanjuti pengguna saat
// YouTube menutup klien adalah melihat klien mana yang masih hidup, menyalin
// laporannya, dan mereset cache stream. Semua itu ada di bagian ini.
// ======================================================================

@Composable
private fun HealthButton(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val borderColor = if (accent) LyreonCrimson else LyreonLine
    Row(
        modifier = Modifier
            .border(1.dp, borderColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) LyreonCrimson else LyreonTextPrimary,
        )
    }
}

// ======================================================================
// Kesehatan stream + diagnostik tangga klien
// ======================================================================

@Composable
private fun StreamHealthSection(vm: SettingsViewModel) {
    val health by vm.health.collectAsStateWithLifecycle()
    val probe by vm.probe.collectAsStateWithLifecycle()
    val running by vm.probeRunning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    SettingSection(stringResource(R.string.sec_stream_health)) {
        val levelLabel = when (health.level) {
            com.lyreon.app.player.StreamHealthLevel.OK -> stringResource(R.string.health_ok)
            com.lyreon.app.player.StreamHealthLevel.DEGRADED -> stringResource(R.string.health_degraded)
            com.lyreon.app.player.StreamHealthLevel.TRIPPED ->
                stringResource(R.string.health_tripped, health.consecutiveFailures)
        }
        Text(levelLabel, style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            health.message ?: stringResource(R.string.health_body),
            style = MaterialTheme.typography.bodySmall,
            color = LyreonTextMuted,
        )
        if (health.sabrSuspected) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.health_sabr_hint),
                style = MaterialTheme.typography.bodySmall,
                color = LyreonTextSecondary,
            )
        }
        if (health.level != com.lyreon.app.player.StreamHealthLevel.OK) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthButton(stringResource(R.string.health_retry), accent = true) { vm.retryStream() }
                HealthButton(stringResource(R.string.health_dismiss)) { vm.dismissHealthAlert() }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.health_diag_title),
                style = MaterialTheme.typography.labelMedium,
                color = LyreonCrimson,
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(LyreonLine))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.health_diag_body),
            style = MaterialTheme.typography.bodySmall,
            color = LyreonTextMuted,
        )
        Spacer(Modifier.height(10.dp))
        HealthButton(
            if (running) stringResource(R.string.health_test_running) else stringResource(R.string.health_test),
        ) { vm.runStreamTest(null) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthButton(stringResource(R.string.health_copy)) {
                val text = vm.diagnosticsReport()
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.health_copied),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            HealthButton(stringResource(R.string.health_reset)) { vm.resetStreaming() }
        }

        probe?.let { report ->
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, LyreonLine)
                    .background(LyreonSurface.copy(alpha = 0.3f))
                    .padding(12.dp),
            ) {
                Text(
                    if (report.anyPathWorks) {
                        stringResource(R.string.health_test_ok)
                    } else {
                        stringResource(R.string.health_test_fail)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (report.anyPathWorks) LyreonCrimson else LyreonTextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                DiagLine(
                    stringResource(
                        R.string.health_test_extractor,
                        if (report.extractorOk) {
                            stringResource(R.string.health_test_extractor_ok, report.extractorAudioStreams, report.extractorMs.toInt())
                        } else {
                            report.extractorError.ifBlank { stringResource(R.string.health_test_extractor_fail) }
                        },
                    ),
                )
                DiagLine(
                    stringResource(
                        R.string.health_test_ladder,
                        report.ladder.usableClient
                            ?: stringResource(R.string.health_test_ladder_none),
                    ),
                )
                // Jalur HLS: manifest bisa diputar Lyreon, jadi ini kabar baik
                // (bukan kegagalan) selama tidak ada URL audio langsung.
                report.ladder.manifestClient?.let { client ->
                    DiagLine(stringResource(R.string.health_test_manifest, client))
                }
                // Temuan Meld: URL yang dikembalikan YouTube bisa jadi hanya pratinjau
                // ~1 MiB (403 sesudahnya). Baris ini yang membedakan "ada URL" dari
                // "URL itu bisa dibaca sampai habis".
                if (report.ladder.cdnRejected) {
                    DiagLine(stringResource(R.string.health_test_cdn_reject))
                }
                report.ladder.urlOnlyClient?.let { client ->
                    DiagLine(stringResource(R.string.health_test_url_only, client))
                }
                DiagLine(
                    stringResource(R.string.health_test_visitor, report.ladder.visitorOrigin),
                    dim = true,
                )
                if (report.ladder.sabrOnly) {
                    DiagLine(stringResource(R.string.health_test_sabr))
                }
                if (report.ladder.drmOnly) {
                    DiagLine(stringResource(R.string.health_test_drm))
                }
                if (report.ladder.hlsOnly && report.ladder.manifestClient == null) {
                    DiagLine(stringResource(R.string.health_test_hls))
                }
                Spacer(Modifier.height(8.dp))
                report.ladder.attempts.forEach { attempt ->
                    DiagLine(
                        "${attempt.client} → ${attempt.verdict} (${attempt.elapsedMs}ms)" +
                            if (attempt.detail.isBlank()) "" else " · ${attempt.detail}",
                        dim = true,
                    )
                }
                if (report.diagnostics.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    DiagLine(stringResource(R.string.health_test_recent), dim = true)
                    report.diagnostics.take(8).forEach { DiagLine(it, dim = true) }
                }
            }
        }
    }
}

@Composable
private fun DiagLine(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = if (dim) LyreonTextMuted else LyreonTextSecondary,
    )
    Spacer(Modifier.height(2.dp))
}
