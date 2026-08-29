package com.ksai.notifylogger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 通知记录 v2
 *
 * 设计变更（2026-08-29）：
 * - 不再做任何过滤/分类，全量记录所有通知
 * - 保留完整字段供 LLM 深度分析（title/text/bigText/subText/ticker + 完整 extras JSON）
 * - dedup_key 去重：同一条通知（pkg + notificationId）内容更新时刷新旧记录而非新增
 */
data class NotifyRecord(
    val id: Long = 0,
    val timestamp: Long,
    val packageName: String,
    val appName: String,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val subText: String = "",
    val ticker: String = "",
    val extraJson: String = ""
)

class NotifyDb(context: Context) : SQLiteOpenHelper(context, "notify.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                title TEXT DEFAULT '',
                text TEXT DEFAULT '',
                big_text TEXT DEFAULT '',
                sub_text TEXT DEFAULT '',
                ticker TEXT DEFAULT '',
                extra_json TEXT DEFAULT '',
                dedup_key TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ts ON notifications(timestamp)")
        db.execSQL("CREATE INDEX idx_pkg ON notifications(package_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 的过滤/分类模型已废弃，直接重建
            db.execSQL("DROP TABLE IF EXISTS notifications")
            onCreate(db)
        }
    }

    /** 插入或按 dedup_key 更新（同一条通知内容变化时刷新快照） */
    fun upsert(record: NotifyRecord): Long {
        val cv = ContentValues().apply {
            put("timestamp", record.timestamp)
            put("package_name", record.packageName)
            put("app_name", record.appName)
            put("title", record.title)
            put("text", record.text)
            put("big_text", record.bigText)
            put("sub_text", record.subText)
            put("ticker", record.ticker)
            put("extra_json", record.extraJson)
            put("dedup_key", dedupKey(record.packageName, record.timestamp))
        }
        return writableDatabase.insertWithOnConflict("notifications", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** 服务重启后补抓活跃通知时，需要绕过 dedup_key 差异强制刷新 */
    fun refresh(record: NotifyRecord) {
        val db = writableDatabase
        db.delete("notifications", "dedup_key = ?", arrayOf(dedupKey(record.packageName, record.timestamp)))
        upsert(record)
    }

    /** 最新记录（倒序分页） */
    fun queryRecent(limit: Int, offset: Int): List<NotifyRecord> {
        val list = mutableListOf<NotifyRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM notifications ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())
        )
        cursor.use {
            while (it.moveToNext()) list.add(cursorToRecord(it))
        }
        return list
    }

    /** 时间区间内全部记录（导出用，正序） */
    fun queryBetween(startTime: Long, endTime: Long): List<NotifyRecord> {
        val list = mutableListOf<NotifyRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM notifications WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp ASC",
            arrayOf(startTime.toString(), endTime.toString())
        )
        cursor.use {
            while (it.moveToNext()) list.add(cursorToRecord(it))
        }
        return list
    }

    /** 全部记录（导出用，正序） */
    fun queryAll(): List<NotifyRecord> {
        val list = mutableListOf<NotifyRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM notifications ORDER BY timestamp ASC",
            null
        )
        cursor.use {
            while (it.moveToNext()) list.add(cursorToRecord(it))
        }
        return list
    }

    fun countTotal(): Int = countWhere(null, null)

    fun countSince(startTime: Long): Int = countWhere("timestamp >= ?", arrayOf(startTime.toString()))

    fun countPackages(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(DISTINCT package_name) FROM notifications", null)
        cursor.use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    private fun countWhere(where: String?, args: Array<String>?): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM notifications" + (if (where != null) " WHERE $where" else ""),
            args
        )
        cursor.use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    companion object {
        /** 同一条通知 = 包名 + 通知 id 的时间戳（postTime），内容更新时复用该 key 做 upsert */
        fun dedupKey(packageName: String, postTime: Long): String = "$packageName|$postTime"

        fun cursorToRecord(cursor: android.database.Cursor): NotifyRecord {
            return NotifyRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                appName = cursor.getString(cursor.getColumnIndexOrThrow("app_name")) ?: "",
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")) ?: "",
                text = cursor.getString(cursor.getColumnIndexOrThrow("text")) ?: "",
                bigText = cursor.getString(cursor.getColumnIndexOrThrow("big_text")) ?: "",
                subText = cursor.getString(cursor.getColumnIndexOrThrow("sub_text")) ?: "",
                ticker = cursor.getString(cursor.getColumnIndexOrThrow("ticker")) ?: "",
                extraJson = cursor.getString(cursor.getColumnIndexOrThrow("extra_json")) ?: ""
            )
        }
    }
}