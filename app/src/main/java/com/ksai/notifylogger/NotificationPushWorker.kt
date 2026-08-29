package com.ksai.notifylogger

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日 23:00 任务：导出当日全部通知记录 JSON，作为一条消息推送到 KAVIS（gateway chatCompletions 端点）。
 * 路径：WorkManager → HTTP POST → Tailscale serve → gateway → KAVIS agent run → 归档 + QQ 简报
 */
class NotificationPushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NotifyPush"
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!PushConfig.isConfigured(ctx)) {
            Log.w(TAG, "推送未配置（缺地址或 token），跳过")
            return Result.failure()
        }

        val db = NotifyDb(ctx)
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val records = db.queryBetween(startOfToday, now)

        if (records.isEmpty()) {
            Log.i(TAG, "今日无记录，跳过推送")
            return Result.success()
        }

        val json = buildNotifyJson(records)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))
        val content = buildString {
            appendLine("【NotifyLogger 每日自动推送】日期 $day · 共 ${records.size} 条通知记录")
            appendLine("这是手机通知记录 app 的定时推送。请执行：")
            appendLine("1) 将下方通知记录 JSON 归档保存到 workspace/notify/ 目录，文件名 ${day}.json")
            appendLine("2) 分析记录（重点：消费/支付类、日程类），整理一份简洁报表")
            appendLine("3) 用 message 工具把报表发到 QQ 私聊 openid 194430DDDEB7735E1D9AD7A019AE7B7D")
            appendLine("4) 简短确认归档完成")
            appendLine("---- 通知记录 JSON ----")
            append(json)
        }

        val body = JSONObject()
            .put("model", "openclaw/kavis")
            .put("user", "notify-daily")
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content))
            )

        val result = withContext(Dispatchers.IO) {
            try {
                val conn = URL(PushConfig.url(ctx)).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer ${PushConfig.token(ctx)}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 180_000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val resp = try {
                    conn.inputStream?.bufferedReader()?.readText()
                        ?: conn.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) {
                    ""
                }
                Log.i(TAG, "推送响应 HTTP $code: ${resp.take(300)}")

                conn.disconnect()
                if (code in 200..299) Result.success() else Result.retry()
            } catch (e: Exception) {
                Log.e(TAG, "推送失败", e)
                Result.retry()
            }
        }
        return result
    }
}