package com.example.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class HorrorAudioEngine(context: Context) {

    private var soundPool: SoundPool? = null
    private var loaded = false
    var enabled: Boolean = true

    private var sfxFootstepWood = 0
    private var sfxGrandmaStep = 0
    private var sfxHeartbeat = 0
    private var sfxDoorCreak = 0
    private var sfxPickup = 0
    private var sfxWoodSnap = 0
    private var sfxLockOpen = 0
    private var sfxFlashClick = 0
    private var sfxScare = 0
    private var sfxEscape = 0

    init {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(attrs)
                .build()

            val dir = File(context.cacheDir, "horror_sfx").apply { mkdirs() }
            sfxFootstepWood = loadSynth(dir, "step_wood.wav", synthWoodStep(0.11f, 115f))
            sfxGrandmaStep = loadSynth(dir, "step_grandma.wav", synthGrandmaStep(0.18f))
            sfxHeartbeat = loadSynth(dir, "heartbeat.wav", synthHeartbeat(0.32f))
            sfxDoorCreak = loadSynth(dir, "door_creak.wav", synthCreak(0.45f))
            sfxPickup = loadSynth(dir, "pickup.wav", synthPickup(0.28f))
            sfxWoodSnap = loadSynth(dir, "wood_snap.wav", synthWoodSnap(0.26f))
            sfxLockOpen = loadSynth(dir, "lock_open.wav", synthLockClank(0.24f))
            sfxFlashClick = loadSynth(dir, "flash_click.wav", synthClick(0.05f))
            sfxScare = loadSynth(dir, "scare.wav", synthJumpscare(0.95f))
            sfxEscape = loadSynth(dir, "escape.wav", synthEscapeChord(1.2f))
            loaded = true
        } catch (_: Throwable) {
            loaded = false
        }
    }

    fun playPlayerStep(sprinting: Boolean, crouching: Boolean) {
        if (!enabled || !loaded) return
        val vol = when {
            crouching -> 0.12f
            sprinting -> 0.36f
            else -> 0.22f
        }
        val pitch = if (sprinting) 1.12f else 0.92f + Random.nextFloat() * 0.14f
        soundPool?.play(sfxFootstepWood, vol, vol, 1, 0, pitch)
    }

    fun playGrandmaStep(distance: Float, stereoPan: Float, sameFloor: Boolean) {
        if (!enabled || !loaded) return
        val maxDist = 18f
        if (distance > maxDist) return
        val floorAtten = if (sameFloor) 1.0f else 0.45f
        val atten = (1f - (distance / maxDist)).coerceIn(0f, 1f)
        val vol = (atten * atten * 0.85f * floorAtten).coerceIn(0f, 0.9f)
        if (vol < 0.02f) return
        val pan = stereoPan.coerceIn(-1f, 1f)
        val left = vol * (1f - pan.coerceAtLeast(0f) * 0.7f)
        val right = vol * (1f + pan.coerceAtMost(0f) * 0.7f)
        soundPool?.play(sfxGrandmaStep, left, right, 2, 0, 0.88f + Random.nextFloat() * 0.18f)
    }

    fun playHeartbeat(intensity: Float) {
        if (!enabled || !loaded) return
        val vol = (intensity * 0.72f).coerceIn(0.08f, 0.75f)
        soundPool?.play(sfxHeartbeat, vol, vol, 1, 0, 0.95f + intensity * 0.2f)
    }

    fun playDoorCreak() {
        if (!enabled || !loaded) return
        soundPool?.play(sfxDoorCreak, 0.45f, 0.45f, 1, 0, 0.92f + Random.nextFloat() * 0.15f)
    }

    fun playPickup() {
        if (!enabled || !loaded) return
        soundPool?.play(sfxPickup, 0.55f, 0.55f, 2, 0, 1.0f)
    }

    fun playWoodSnap() {
        if (!enabled || !loaded) return
        soundPool?.play(sfxWoodSnap, 0.65f, 0.65f, 2, 0, 1.0f)
    }

    fun playLockOpen() {
        if (!enabled || !loaded) return
        soundPool?.play(sfxLockOpen, 0.6f, 0.6f, 2, 0, 1.0f)
    }

    fun playFlashClick() {
        if (!enabled || !loaded) return
        soundPool?.play(sfxFlashClick, 0.4f, 0.4f, 1, 0, 1.0f)
    }

    fun playScare() {
        if (!enabled || !loaded) return
        soundPool?.play(sfxScare, 0.95f, 0.95f, 3, 0, 1.0f)
    }

    fun playEscape() {
        if (!enabled || !loaded) return
        soundPool?.play(sfxEscape, 0.75f, 0.75f, 3, 0, 1.0f)
    }

    fun release() {
        try {
            soundPool?.release()
        } catch (_: Throwable) {
        }
        soundPool = null
    }

    private fun loadSynth(dir: File, name: String, samples: ShortArray): Int {
        val file = File(dir, name)
        writeWav(file, samples, SAMPLE_RATE)
        return soundPool?.load(file.absolutePath, 1) ?: 0
    }

    companion object {
        private const val SAMPLE_RATE = 22050

        private fun synthWoodStep(dur: Float, baseFreq: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = exp(-t * 28f)
                val thump = sin(2f * PI.toFloat() * baseFreq * exp(-t * 8f) * t)
                val grit = (Random.nextFloat() * 2f - 1f) * 0.35f
                out[i] = ((thump * 0.75f + grit) * env * 22000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthGrandmaStep(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = exp(-t * 16f)
                val heavy = sin(2f * PI.toFloat() * 68f * t) + 0.5f * sin(2f * PI.toFloat() * 145f * t)
                val caneClick = if (t > 0.07f) exp(-(t - 0.07f) * 55f) * sin(2f * PI.toFloat() * 640f * t) * 0.6f else 0f
                out[i] = ((heavy * env * 0.65f + caneClick) * 24000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthHeartbeat(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val b1 = exp(-t * 24f) * sin(2f * PI.toFloat() * 52f * t)
                val b2 = if (t > 0.13f) exp(-(t - 0.13f) * 28f) * sin(2f * PI.toFloat() * 44f * (t - 0.13f)) * 0.72f else 0f
                out[i] = ((b1 + b2) * 26000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthCreak(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            var phase = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = sin(PI.toFloat() * (t / dur))
                val freq = 240f + sin(t * 26f) * 75f + t * 180f
                phase += 2f * PI.toFloat() * freq / SAMPLE_RATE
                val pulse = if (sin(t * 75f) > -0.2f) 1f else 0.35f
                val sig = (sin(phase) * 0.6f + sin(phase * 2.03f) * 0.4f) * env * pulse
                out[i] = (sig * 17000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthPickup(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = exp(-t * 10f)
                val f1 = sin(2f * PI.toFloat() * 880f * t)
                val f2 = sin(2f * PI.toFloat() * 1320f * t) * 0.6f
                out[i] = ((f1 + f2) * env * 15000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthWoodSnap(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = exp(-t * 18f)
                val crack = (Random.nextFloat() * 2f - 1f) * 0.8f + sin(2f * PI.toFloat() * 180f * t) * 0.4f
                out[i] = (crack * env * 24000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthLockClank(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = exp(-t * 16f)
                val ring = sin(2f * PI.toFloat() * 720f * t) + 0.7f * sin(2f * PI.toFloat() * 1190f * t)
                out[i] = (ring * env * 14000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthClick(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = exp(-t * 95f)
                val sig = sin(2f * PI.toFloat() * 1600f * t) + (Random.nextFloat() * 2f - 1f) * 0.5f
                out[i] = (sig * env * 16000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthJumpscare(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = exp(-t * 2.4f)
                val dissonance =
                    sin(2f * PI.toFloat() * 290f * t) +
                    sin(2f * PI.toFloat() * 311f * t) +
                    sin(2f * PI.toFloat() * 622f * t) +
                    (Random.nextFloat() * 2f - 1f) * 0.55f
                out[i] = (dissonance * 0.35f * env * 28000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun synthEscapeChord(dur: Float): ShortArray {
            val n = (dur * SAMPLE_RATE).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / SAMPLE_RATE
                val env = exp(-t * 1.8f) * (1f - exp(-t * 35f))
                val chord =
                    sin(2f * PI.toFloat() * 261.63f * t) +
                    sin(2f * PI.toFloat() * 329.63f * t) * 0.8f +
                    sin(2f * PI.toFloat() * 392.00f * t) * 0.8f +
                    sin(2f * PI.toFloat() * 523.25f * t) * 0.6f
                out[i] = (chord * 0.3f * env * 25000f).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }

        private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
            val dataBytes = samples.size * 2
            val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray())
            buf.putInt(36 + dataBytes)
            buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray())
            buf.putInt(16) // PCM chunk size
            buf.putShort(1) // PCM format
            buf.putShort(1) // mono
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * 2)
            buf.putShort(2)
            buf.putShort(16)
            buf.put("data".toByteArray())
            buf.putInt(dataBytes)
            for (s in samples) buf.putShort(s)
            FileOutputStream(file).use { it.write(buf.array()) }
        }
    }
}
