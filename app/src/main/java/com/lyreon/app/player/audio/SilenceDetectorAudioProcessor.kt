/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Diadaptasi dari FrancescoGrazioso/Meld (GPL-3.0):
 *   app/src/main/kotlin/com/metrolist/music/playback/audio/SilenceDetectorAudioProcessor.kt
 * Metrolist Project (C) 2026 — lihat riwayat git upstream untuk kontributor.
 * Perubahan Lyreon: paket, penamaan parameter, dan penyesuaian @Deprecated
 * (di media3 1.10.1 hanya flush() yang usang, reset() tidak).
 */
package com.lyreon.app.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Processor PCM tembus-pandang (pass-through) yang MENDETEKSI rentang hening
 * panjang. Bila [instantModeEnabled] aktif dan keheningan lebih panjang dari
 * [minSilenceDurationUs] terdeteksi, [onLongSilence] dipanggil tepat satu kali
 * per segmen hening — pemanggil yang memutuskan mau melompat ke mana.
 *
 * Ini pelengkap `SilenceSkippingAudioProcessor` bawaan media3 (yang membuang
 * hening dari aliran audio): processor ini untuk kasus hening yang tidak cukup
 * sunyi untuk dibuang (fade panjang, jeda antar-lagu di mix/DJ set).
 */
@UnstableApi
@Suppress("DEPRECATION")
class SilenceDetectorAudioProcessor(
    private val minSilenceDurationUs: Long = 2_000_000L,
    private val silenceThreshold: Int = 256,
    private val onLongSilence: () -> Unit,
) : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    @Volatile
    var instantModeEnabled: Boolean = false

    @Volatile
    private var consecutiveSilentFrames: Long = 0

    @Volatile
    private var inSilence: Boolean = false

    private var notifiedThisSilence = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        if (encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            outputBuffer = EMPTY_BUFFER
            return
        }

        // Analisis PCM masuk tanpa mengubah posisi buffer.
        if (instantModeEnabled && sampleRate > 0 && channelCount > 0) {
            detectSilence(inputBuffer)
        } else {
            clearSilenceState()
        }

        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer)
        out.flip()
    }

    private fun detectSilence(inputBuffer: ByteBuffer) {
        // Endian harus pasti agar getShort(index) terbaca benar.
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val frameCount = inputBuffer.remaining() / 2 / channelCount
        val basePosition = inputBuffer.position()

        repeat(frameCount) { frameIndex ->
            var framePeak = 0
            repeat(channelCount) { channelIndex ->
                val sampleIndex = basePosition + (frameIndex * channelCount + channelIndex) * 2
                val sampleValue = abs(inputBuffer.getShort(sampleIndex).toInt())
                if (sampleValue > framePeak) framePeak = sampleValue
            }

            if (framePeak < silenceThreshold) {
                consecutiveSilentFrames++
                val silentDurationUs = (consecutiveSilentFrames * 1_000_000L) / sampleRate
                if (silentDurationUs >= minSilenceDurationUs) {
                    inSilence = true
                    if (!notifiedThisSilence) {
                        notifiedThisSilence = true
                        onLongSilence()
                    }
                }
            } else {
                clearSilenceState()
            }
        }
    }

    private fun clearSilenceState() {
        consecutiveSilentFrames = 0
        inSilence = false
        notifiedThisSilence = false
    }

    /** Panggil sebelum seek agar segmen yang sama tidak memicu ulang. */
    fun resetTracking() {
        clearSilenceState()
    }

    fun isCurrentlySilent(): Boolean = inSilence

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        clearSilenceState()
    }

    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    private companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
