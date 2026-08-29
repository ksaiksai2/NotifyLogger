package com.ksai.notifylogger

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 推送配置（本地 SharedPreferences，token 不落 APK） */
object PushConfig {
    private const val PREFS = "push_config"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"

    /** 默认推送地址留空，用户在设置页填写（发布版不含个人服务器地址） */
    const val DEFAULT_URL = ""

    fun url(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""

    fun isConfigured(ctx: Context): Boolean = url(ctx).isNotBlank() && token(ctx).isNotBlank()

    fun save(ctx: Context, url: String, token: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }
}

/** 每日 23:00 定时推送调度 */
object PushScheduler {
    private const val WORK_NAME = "daily_notify_push"

    fun scheduleDailyPush(context: Context) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var next = cal.timeInMillis
        if (next <= now) next += TimeUnit.DAYS.toMillis(1)
        val initialDelay = next - now

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<NotificationPushWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    /** 立即发送（设置页测试用） */
    fun sendNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<NotificationPushWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}