/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonTextBright
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import com.lyreon.app.ui.theme.LyreonHairline
import com.lyreon.app.ui.theme.LyreonSurfaceTranslucent

/** Ajakan donasi — tampil ramah untuk mendukung pengembang di saweria.co/riznotdev. */
@Composable
fun DonateDialog(
    onDonate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyreonElevated,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.border(1.dp, LyreonHairline, RoundedCornerShape(28.dp)),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(LyreonCrimson.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = LyreonCrimson,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.donate_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = LyreonTextPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Text(
                stringResource(R.string.donate_body),
                style = MaterialTheme.typography.bodyMedium,
                color = LyreonTextSecondary,
                textAlign = TextAlign.Center,
            )
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(LyreonCrimson)
                        .clickable(onClick = onDonate)
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.donate_yes),
                        style = MaterialTheme.typography.titleMedium,
                        color = LyreonTextBright,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(LyreonSurfaceTranslucent)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.donate_later),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LyreonTextMuted,
                    )
                }
            }
        },
    )
}
