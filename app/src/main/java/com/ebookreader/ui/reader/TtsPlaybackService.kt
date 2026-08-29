package com.ebookreader.ui.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ebookreader.MainActivity
import com.ebookreader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 听书前台服务：熄屏 / 切后台时保持朗读不中断，通知栏提供暂停/继续与停止。
 * 参考 [com.ebookreader.ui.decompose.DecomposeService] 的前台服务写法。
 */
class TtsPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> TtsServiceBridge.emit(TtsServiceBridge.Command.Toggle)
            ACTION_STOP -> {
                TtsServiceBridge.emit(TtsServiceBridge.Command.Stop)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        createChannel()
        val initial = TtsServiceBridge.state.value
        val notif = buildNotification(initial?.isPlaying ?: true, initial?.sentence ?: "正在朗读…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        collectJob?.cancel()
        collectJob = scope.launch {
            TtsServiceBridge.state.collect { snap ->
                if (snap == null) return@collect
                nm().notify(NOTIFICATION_ID, buildNotification(snap.isPlaying, snap.sentence))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        super.onDestroy()
    }

    private fun nm(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "听书", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "朗读进行中"
                setShowBadge(false)
            }
            nm().createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isPlaying: Boolean, sentence: String): Notification {
        val toggleLabel = if (isPlaying) "暂停" else "继续"
        val toggleIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("听书")
            .setContentText(sentence.take(80))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, toggleLabel, toggleIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_TOGGLE = "com.ebookreader.tts.TOGGLE"
        private const val ACTION_STOP = "com.ebookreader.tts.STOP"
    }
}

/** 听书前台服务的进程级桥：ViewModel 写状态/收命令，Service 读状态/发命令。 */
object TtsServiceBridge {
    data class Snapshot(val isPlaying: Boolean, val sentence: String)

    sealed interface Command {
        data object Stop : Command
        data object Toggle : Command
    }

    val state = MutableStateFlow<Snapshot?>(null)
    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)

    fun update(isPlaying: Boolean, sentence: String) {
        state.value = Snapshot(isPlaying, sentence)
    }

    fun clear() {
        state.value = null
    }

    fun emit(cmd: Command) {
        commands.tryEmit(cmd)
    }
}
