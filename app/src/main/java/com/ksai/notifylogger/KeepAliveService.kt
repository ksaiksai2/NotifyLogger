package com.ksai.notifylogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * v2.0.7 前台保活服务
 *
 * 用途：对抗 ColorOS 冻结/杀进程。
 * - 常驻低优先级状态栏通知 → 系统视 App 为"使用中"，不再冻结/回收进程
 * - 每 60s 检查监听心跳，过期自动 requestRebind（自愈，无需用户手动点）
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1
        private const val STALE_MS = 4 * 60_000L // 心跳超过 4 分钟视为过期
    }

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            try {
                val hb = PushConfig.listenerHeartbeat(applicationContext)
                val stale = hb == 0L || System.currentTimeMillis() - hb > STALE_MS
                if (stale) {
                    AppLog.w(applicationContext, "保活自愈: 心跳过期(${if (hb > 0) (System.currentTimeMillis() - hb) / 1000 else -1}s)，请求系统重绑")
                    NotificationListenerService.requestRebind(
                        ComponentName(this@KeepAliveService, NotificationLoggerService::class.java)
                    )
                } else {
                    AppLog.d(applicationContext, "保活自愈: 心跳正常，不需重绑")
                }
            } catch (e: Exception) {
                AppLog.e(applicationContext, "保活重绑失败: ${e.message}")
            }
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i(applicationContext, "KeepAliveService onCreate（前台保活启动）")
        createChannel()
        handler.postDelayed(checkRunnable, 60_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.d(applicationContext, "KeepAliveService onDestroy")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "通知监听守护", NotificationManager.IMPORTANCE_MIN)
            ch.description = "保持通知监听与守护进程存活（低优先级，无声音）"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("通知监听守护中")
            .setContentText("保持常驻 · 点击打开")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }
}
