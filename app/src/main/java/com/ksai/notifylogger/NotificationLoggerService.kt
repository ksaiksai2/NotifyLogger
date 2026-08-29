package com.ksai.notifylogger

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/**
 * 通知监听服务 v2
 *
 * 设计变更（2026-08-29）：
 * - 全量记录所有通知，不做任何过滤/分类（分类交给 LLM）
 * - 完整抓取：title / text / bigText / subText / ticker + 全部可序列化 extras
 * - 服务重连时补抓当前活跃通知，弥补漏记
 * - 同一条通知内容更新时刷新旧记录（按 pkg + postTime 去重）
 */
class NotificationLoggerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifyLogger"

        /** extras 里这些 key 的 value 无法 JSON 序列化（Bitmap/byte[]/Parcelable），跳过 */
        private val SKIP_EXTRA_KEYS = setOf(
            Notification.EXTRA_SMALL_ICON,
            Notification.EXTRA_LARGE_ICON,
            Notification.EXTRA_PICTURE, "android.people", "android.messaging",
            "android.icon", "android.extraIcon", "android.extraLargeIcon", "android.largeIcon"
        )
    }

    private var db: NotifyDb? = null

    override fun onCreate() {
        super.onCreate()
        db = NotifyDb(applicationContext)
        Log.i(TAG, "NotificationLoggerService started (v2 all-capture)")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected, backfilling active notifications…")
        // 服务重启/重连后，正在显示的通知不会触发 onNotificationPosted，手动补抓一次
        try {
            val active = activeNotifications
            if (active != null) {
                active.forEach { sbn -> capture(sbn, forceRefresh = true) }
                Log.i(TAG, "Backfilled ${active.size} active notifications")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backfill failed", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        capture(sbn, forceRefresh = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知被清除时数据已入库，无需处理
    }

    private fun capture(sbn: StatusBarNotification, forceRefresh: Boolean) {
        val notification = sbn.notification ?: run {
            Log.w(TAG, "Notification is null for ${sbn.packageName}")
            return
        }
        try {
            val extras = notification.extras ?: Bundle.EMPTY
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
            val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: ""
            val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""
            val ticker = notification.tickerText?.toString() ?: ""

            // 很多支付/进度通知正文在 BIG_TEXT 里，EXTRA_TEXT 为空 —— 回退合并
            val effectiveText = when {
                text.isNotBlank() -> text
                bigText.isNotBlank() -> bigText
                else -> ""
            }

            val appName = try {
                val pm = applicationContext.packageManager
                val info = pm.getApplicationInfo(sbn.packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                sbn.packageName
            }

            val record = NotifyRecord(
                timestamp = sbn.postTime,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = effectiveText,
                bigText = bigText,
                subText = subText,
                ticker = ticker,
                extraJson = extrasToJson(extras)
            )

            val inserted = db?.upsert(record) ?: -1
            // CONFLICT_IGNORE 返回 -1 = 已存在但内容可能更新了，forceRefresh 或内容变化时刷新
            if (inserted == -1L || forceRefresh) {
                db?.refresh(record)
            }

            if (title.isNotBlank() || text.isNotBlank()) {
                Log.i(TAG, "📩 ${sbn.packageName}: $title | $effectiveText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing notification from ${sbn.packageName}", e)
        }
    }

    /** 将 extras 序列化为 JSON（只保留可 JSON 化的值，供 LLM 深度分析） */
    private fun extrasToJson(extras: Bundle): String {
        return try {
            val json = JSONObject()
            extras.keySet().forEach { key ->
                if (key in SKIP_EXTRA_KEYS) return@forEach
                val v = extras.get(key) ?: return@forEach
                when (v) {
                    is String -> json.put(key, v)
                    is CharSequence -> json.put(key, v.toString())
                    is Number -> json.put(key, v)
                    is Boolean -> json.put(key, v)
                    is Array<*> -> json.put(key, v.joinToString(" | ") { it?.toString() ?: "" })
                    is ArrayList<*> -> json.put(key, v.joinToString(" | ") { it?.toString() ?: "" })
                    // 其余类型（Parcelable/Bitmap/byte[] 等）跳过
                }
            }
            json.toString()
        } catch (_: Exception) {
            "{}"
        }
    }
}