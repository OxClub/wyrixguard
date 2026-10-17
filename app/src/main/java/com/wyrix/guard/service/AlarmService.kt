package com.wyrix.guard.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wyrix.guard.system.Notifs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AlarmState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    fun set(v: Boolean) { _running.value = v }
}

/** Owner-triggered alarm: visible full-screen UI, audible via the ALARM stream, auto-stops after 5 minutes. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifs.ensureChannels(this)
        try {
            ServiceCompat.startForeground(this, Notifs.ID_ALARM, Notifs.alarm(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        AlarmState.set(true)
        play()
        handler.postDelayed({ stopSelf() }, 5 * 60_000L)
        return START_NOT_STICKY
    }

    private fun play() {
        if (player != null) return
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(this@AlarmService, uri); isLooping = true; prepare(); start()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }; player?.release(); player = null
        AlarmState.set(false)
        super.onDestroy()
    }

    companion object {
        /** False if Android refused to start it (e.g. background start limits). */
        fun start(ctx: Context): Boolean = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, AlarmService::class.java)) }.isSuccess
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AlarmService::class.java)); AlarmState.set(false) }
    }
}
