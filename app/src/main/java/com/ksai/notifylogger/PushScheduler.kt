package com.ksai.notifylogger

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
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
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_FILTER_APPS = "filter_apps"
    private const val KEY_LLM_MODE = "llm_mode"
    private const val KEY_LLM_URL = "llm_url"
    private const val KEY_LLM_KEY = "llm_key"
    private const val KEY_LLM_MODEL = "llm_model"
    private const val KEY_LISTENER_HEARTBEAT = "listener_heartbeat"

    const val FILTER_ALL = "all"
    const val FILTER_BLACKLIST = "blacklist"
    const val FILTER_WHITELIST = "whitelist"

    /** AI 分析模式：直调云端 LLM / 走 OpenClaw 网关 */
    const val LLM_MODE_DIRECT = "direct"
    const val LLM_MODE_OPENCLAW = "openclaw"
    const val DEFAULT_LLM_MODEL = "deepseek-chat"

    /** 默认推送地址留空，用户在设置页填写（发布版不含个人服务器地址） */
    const val DEFAULT_URL = ""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun url(ctx: Context): String = prefs(ctx).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun token(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, "") ?: ""

    fun isConfigured(ctx: Context): Boolean = url(ctx).isNotBlank() && token(ctx).isNotBlank()

    fun save(ctx: Context, url: String, token: String) {
        prefs(ctx).edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    // ---------- 通知过滤（v2.0.1 新增） ----------

    fun filterMode(ctx: Context): String = prefs(ctx).getString(KEY_FILTER_MODE, FILTER_ALL) ?: FILTER_ALL

    fun filterApps(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_FILTER_APPS, emptySet()) ?: emptySet()

    fun saveFilter(ctx: Context, mode: String, apps: Set<String>) {
        prefs(ctx).edit()
            .putString(KEY_FILTER_MODE, mode)
            .putStringSet(KEY_FILTER_APPS, apps)
            .apply()
    }

    /** 通知监听服务调用：这条通知是否允许入库 */
    fun isAllowed(ctx: Context, packageName: String): Boolean = when (filterMode(ctx)) {
        FILTER_BLACKLIST -> packageName !in filterApps(ctx)
        FILTER_WHITELIST -> packageName in filterApps(ctx)
        else -> true
    }

    // ---------- AI 分析直调配置（v2.0.3 新增） ----------

    fun llmMode(ctx: Context): String =
        prefs(ctx).getString(KEY_LLM_MODE, LLM_MODE_OPENCLAW) ?: LLM_MODE_OPENCLAW

    /** OpenAI 兼容 chat/completions 完整端点，例如 https://api.deepseek.com/v1/chat/completions */
    fun llmUrl(ctx: Context): String = prefs(ctx).getString(KEY_LLM_URL, "") ?: ""

    fun llmKey(ctx: Context): String = prefs(ctx).getString(KEY_LLM_KEY, "") ?: ""

    fun llmModel(ctx: Context): String =
        prefs(ctx).getString(KEY_LLM_MODEL, DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL

    fun isLlmDirectConfigured(ctx: Context): Boolean =
        llmUrl(ctx).isNotBlank() && llmKey(ctx).isNotBlank() && llmModel(ctx).isNotBlank()

    fun saveLlm(ctx: Context, mode: String, url: String, key: String, model: String) {
        prefs(ctx).edit()
            .putString(KEY_LLM_MODE, mode)
            .putString(KEY_LLM_URL, url.trim())
            .putString(KEY_LLM_KEY, key.trim())
            .putString(KEY_LLM_MODEL, model.trim())
            .apply()
    }

    // ---------- 监听服务心跳（v2.0.4） ----------

    /** 监听服务最近一次心跳时间，用于检测服务是否存活/被系统挂起 */
    fun listenerHeartbeat(ctx: Context): Long = prefs(ctx).getLong(KEY_LISTENER_HEARTBEAT, 0L)

    /** 监听服务每次心跳时调用（服务内 60s 定时刷新） */
    fun touchListenerHeartbeat(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_LISTENER_HEARTBEAT, System.currentTimeMillis()).apply()
    }
}

/** 每日 23:00 定时推送调度 */
object PushScheduler {
    private const val WORK_NAME = "daily_notify_push"
    const val TEST_WORK_NAME = "notify_test_send"

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

    /** 立即发送（设置页测试用）；用 unique work 便于 UI 观察结果，返回 request 供调用方匹配任务 ID */
    fun sendNow(context: Context): OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<NotificationPushWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TEST_WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
        return request
    }
}