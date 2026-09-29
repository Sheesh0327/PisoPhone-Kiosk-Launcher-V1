package com.pisophone.kiosk.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object KioskSoundSynthesizer {

    fun generateCoinSoundBuffer(sampleRate: Int = 44100, durationSec: Double = 0.38): ShortArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val buffer = ShortArray(numSamples)
        val splitSample = (0.085 * sampleRate).toInt()
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val valSample: Double
            val env: Double
            if (i < splitSample) {
                val f = 987.77 // B5
                env = 1.0 - (t / 0.085) * 0.15
                valSample = 0.7 * sin(2.0 * PI * f * t) + 0.25 * sin(4.0 * PI * f * t)
            } else {
                val f = 1318.51 // E6
                val t2 = t - 0.085
                env = exp(-t2 * 8.5)
                valSample = 0.75 * sin(2.0 * PI * f * t) + 0.2 * sin(4.0 * PI * f * t) + 0.1 * sin(6.0 * PI * f * t)
            }
            val sample = (valSample * env * 32767.0 * 0.88).toInt().coerceIn(-32768, 32767)
            buffer[i] = sample.toShort()
        }
        return buffer
    }

    fun generateWaitingMusicBuffer(sampleRate: Int = 44100, loopDurationSec: Double = 15.0): ShortArray {
        val totalSamples = (loopDurationSec * sampleRate).toInt()
        val buffer = ShortArray(totalSamples)

        val notes = doubleArrayOf(
            523.25, 659.25, 783.99, 1046.50, // C5, E5, G5, C6 (s 1-4)
            880.00, 698.46, 783.99, 659.25,  // A5, F5, G5, E5 (s 5-8)
            587.33, 659.25, 783.99, 880.00,  // D5, E5, G5, A5 (s 9-12)
            987.77, 1046.50, 1174.66         // B5, C6, D6 (s 13-15 urgency)
        )

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val beatIdx = minOf(14, t.toInt())
            val beatT = t - beatIdx

            // 1. Rhythmic clock tick on every second
            val tickEnv = exp(-beatT * 35.0)
            val tickVal = 0.28 * sin(2.0 * PI * 2200.0 * beatT) * tickEnv

            // 2. Warm bass pulse
            val bassF = when {
                beatIdx < 4 -> 130.81
                beatIdx < 8 -> 174.61
                beatIdx < 12 -> 196.00
                else -> 130.81
            }
            val bassEnv = exp(-beatT * 3.5)
            val bassVal = 0.32 * sin(2.0 * PI * bassF * t) * bassEnv

            // 3. Arpeggiated melody note
            val noteF = notes[beatIdx]
            val subBeat = ((beatT * 4) % 4).toInt()
            val arpMult = when (subBeat) {
                0 -> 1.0
                1 -> 1.25
                2 -> 1.5
                else -> 1.25
            }
            val curF = noteF * arpMult
            val subT = (beatT * 4) - (beatT * 4).toInt()
            val melEnv = exp(-subT * 6.0)
            val melVal = 0.22 * sin(2.0 * PI * curF * t) * melEnv

            var total = (tickVal + bassVal + melVal) * 0.75
            if (t < 0.1) {
                total *= (t / 0.1)
            } else if (t > 14.8) {
                total *= ((15.0 - t) / 0.2)
            }

            val sample = (total * 32767.0).toInt().coerceIn(-32768, 32767)
            buffer[i] = sample.toShort()
        }
        return buffer
    }

    fun generateSynthesizedToneBuffer(freqHz: Int, durationMs: Int, sampleRate: Int = 44100): ShortArray {
        val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * i / (sampleRate.toDouble() / freqHz)
            val decay = 1.0 - (i.toDouble() / numSamples.toDouble())
            buffer[i] = (sin(angle) * 32767 * decay * 0.7).toInt().toShort()
        }
        return buffer
    }

    fun generateLowBatteryToneBuffer(sampleRate: Int = 44100, durationSec: Double = 0.45): ShortArray {
        val numSamples = (sampleRate * durationSec).toInt()
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val freq = if ((t * 9).toInt() % 2 == 0) 880.0 else 1174.66
            val decay = 1.0 - (t / durationSec) * 0.2
            val sample = (sin(2.0 * PI * freq * t) * 32767 * decay * 0.85).toInt().coerceIn(-32768, 32767)
            buffer[i] = sample.toShort()
        }
        return buffer
    }

    fun generateHighBatteryToneBuffer(sampleRate: Int = 44100, durationSec: Double = 0.4): ShortArray {
        val numSamples = (sampleRate * durationSec).toInt()
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val freq = if (t < 0.2) 1318.51 else 1567.98
            val env = 1.0 - (t / durationSec) * 0.15
            val sample = (sin(2.0 * PI * freq * t) * 32767 * env * 0.75).toInt().coerceIn(-32768, 32767)
            buffer[i] = sample.toShort()
        }
        return buffer
    }
}
