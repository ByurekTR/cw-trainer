package com.cwtrainer.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import com.cwtrainer.core.audio.CwEngine

/**
 * The only Android-specific audio code: a thread that pulls float samples from the pure-Kotlin
 * [CwEngine] and writes them to a low-latency streaming [AudioTrack].
 *
 * USAGE_MEDIA means the hardware volume keys, headphones and Bluetooth routing all behave like
 * any music app.
 *
 * v2: background listening moves [start]/[stop] into a foreground service; the microphone
 * decoder is a sibling class using AudioRecord that feeds core's SendDecoder.
 */
class AudioPlayer(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val sampleRate: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000

    private val blockFrames: Int =
        (audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 240).coerceIn(64, 1024)

    val engine = CwEngine(sampleRate)

    /** Rough time between rendering a sample and hearing it; used to correct reaction times. */
    @Volatile
    var latencyMs: Long = 40
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "cw-audio").also { it.start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        engine.stopPlayback()
        engine.setKey(false)
        val t = thread
        thread = null
        try {
            t?.join(500)
        } catch (_: InterruptedException) {
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferBytes = maxOf(if (minBytes > 0) minBytes else 0, blockFrames * 4 * 2)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            running = false
            return
        }
        latencyMs = (track.bufferSizeInFrames * 1000L / sampleRate) + 15
        val buf = FloatArray(blockFrames)
        try {
            track.play()
            while (running) {
                engine.render(buf, blockFrames)
                if (track.write(buf, 0, blockFrames, AudioTrack.WRITE_BLOCKING) < 0) break
            }
            // Fade the last block out so stopping (even mid-noise) never clicks.
            engine.render(buf, blockFrames)
            for (i in 0 until blockFrames) buf[i] *= 1f - (i + 1).toFloat() / blockFrames
            track.write(buf, 0, blockFrames, AudioTrack.WRITE_BLOCKING)
            track.stop()
        } catch (_: Exception) {
        } finally {
            track.release()
            running = false
        }
    }
}
