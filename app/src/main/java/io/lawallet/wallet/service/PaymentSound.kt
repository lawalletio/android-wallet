package io.lawallet.wallet.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import io.lawallet.wallet.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** payment-sound.ts: `chime` (synth sweep 880→1320 Hz), `bananero` (mp3) or `off`. */
object PaymentSound {
    private val played = LinkedHashSet<String>()

    fun play(context: Context, choice: String, cueId: String? = null) {
        if (cueId != null) {
            if (!played.add(cueId)) return
            if (played.size > 200) played.remove(played.first())
        }
        when (choice) {
            "chime" -> chime()
            "bananero" -> MediaPlayer.create(context, R.raw.bananero)?.apply { setOnCompletionListener { it.release() }; start() }
        }
    }

    private fun chime() {
        val rate = 44_100
        val n = (rate * 0.34).toInt()
        val pcm = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val freq = if (t < 0.08) 880 * exp(ln(1320.0 / 880) * t / 0.08) else 1320.0
            val gain = if (t < 0.02) 0.0001 + (0.18 - 0.0001) * t / 0.02 else 0.18 * exp(ln(0.0001 / 0.18) * (t - 0.02) / 0.30).coerceAtLeast(0.0)
            phase += 2 * PI * freq / rate
            pcm[i] = (sin(phase) * gain * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
        track.write(pcm, 0, n)
        track.setNotificationMarkerPosition(n - 1)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        track.play()
    }
}
