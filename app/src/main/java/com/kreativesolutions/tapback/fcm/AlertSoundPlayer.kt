package com.kreativesolutions.tapback.fcm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays on the alarm stream so a check-in still rings when the phone is on silent
 * or vibrate. Notification sound follows ringer volume; alarm volume does not.
 */
object AlertSoundPlayer {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var restoredAlarmVolume: Int? = null
    private var vibrator: Vibrator? = null

    fun start(context: Context) {
        val app = context.applicationContext
        val gen = synchronized(lock) {
            stopLocked()
            ++generation
        }
        mainHandler.post {
            synchronized(lock) {
                if (gen != generation) return@synchronized
                startLocked(app)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            stopLocked()
        }
    }

    private fun startLocked(app: Context) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        ensureAlarmAudible(am)
        requestFocus(am)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val started = if (uri != null) {
            runCatching {
                val mp = MediaPlayer()
                try {
                    mp.setAudioAttributes(attrs)
                    mp.setDataSource(app, uri)
                    mp.isLooping = true
                    mp.setVolume(1f, 1f)
                    mp.prepare()
                    mp.start()
                    player = mp
                    true
                } catch (error: Exception) {
                    mp.release()
                    throw error
                }
            }.getOrDefault(false)
        } else {
            false
        }

        if (!started && uri != null) {
            runCatching {
                RingtoneManager.getRingtone(app, uri)?.apply {
                    audioAttributes = attrs
                    isLooping = true
                    play()
                    ringtone = this
                }
            }
        }

        startVibration(app)
    }

    private fun stopLocked() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { ringtone?.stop() }
        ringtone = null
        vibrator?.cancel()
        vibrator = null
        focusRequest?.let { req ->
            audioManager?.abandonAudioFocusRequest(req)
        }
        focusRequest = null
        restoredAlarmVolume?.let { volume ->
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        }
        restoredAlarmVolume = null
        audioManager = null
    }

    private fun ensureAlarmAudible(am: AudioManager) {
        val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
        if (current > 0) return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        restoredAlarmVolume = current
        am.setStreamVolume(
            AudioManager.STREAM_ALARM,
            (max * 0.7f).toInt().coerceAtLeast(1),
            0
        )
    }

    private fun requestFocus(am: AudioManager) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun startVibration(context: Context) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator = vib
        val pattern = longArrayOf(0, 600, 250, 600, 250, 600, 700)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vib.vibrate(
                effect,
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
            )
        } else {
            vib.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
        }
    }
}
