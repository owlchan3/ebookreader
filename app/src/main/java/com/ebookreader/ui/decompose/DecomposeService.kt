package com.ebookreader.ui.decompose

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
import kotlinx.coroutines.launch

/** 前台服务：在通知栏展示 AI 拆书进度，允许切屏/退到后台时继续拆书。 */
class DecomposeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var bookId: Long = -1L
    private var bookType: String = "general"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bookId = intent?.getLongExtra(DecomposeViewModel.EXTRA_BOOK_ID, -1L) ?: -1L
        bookType = intent?.getStringExtra(DecomposeViewModel.EXTRA_BOOK_TYPE) ?: "general"
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, buildNotification(0, 0, "准备拆书…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(0, 0, "准备拆书…"))
        }

        collectJob?.cancel()
        collectJob = scope.launch {
            DecomposeEngine.state.collect { state ->
                when (state) {
                    is DecomposeState.Generating -> {
                        val n = buildNotification(state.done, state.total, "进度 ${state.done}/${state.total} · 当前章节：${state.currentChapter}")
                        nm().notify(NOTIFICATION_ID, n)
                    }
                    is DecomposeState.NeedsRetry -> {
                        val n = buildNotification(state.done, state.total, "进度 ${state.done + 1}/${state.total} · 章节生成失败，请回到应用处理")
                        nm().notify(NOTIFICATION_ID, n)
                    }
                    is DecomposeState.Done -> {
                        nm().notify(NOTIFICATION_ID, buildFinishNotification("拆书完成"))
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    is DecomposeState.Error -> {
                        nm().notify(NOTIFICATION_ID, buildFinishNotification("拆书失败：${state.message.take(20)}"))
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    is DecomposeState.Idle -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
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
                CHANNEL_ID, "拆书进度", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "AI 拆书进度"
                setShowBadge(false)
            }
            nm().createNotificationChannel(channel)
        }
    }

    private fun buildNotification(done: Int, total: Int, text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 拆书")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) {
            builder.setProgress(total, done, false)
        }
        return builder.build()
    }

    private fun buildFinishNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 拆书")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(DecomposeViewModel.EXTRA_BOOK_ID, bookId)
            putExtra(DecomposeViewModel.EXTRA_BOOK_TYPE, bookType)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "decompose_progress"
        private const val NOTIFICATION_ID = 1001
    }
}
