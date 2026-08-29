package com.ksai.notifylogger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 生成与导出一致的 JSON（含完整字段，供 KAVIS/LLM 分析），MainActivity 与推送 Worker 共用 */
fun buildNotifyJson(records: List<NotifyRecord>): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA)
    return buildString {
        append("[\n")
        records.forEachIndexed { i, r ->
            append("  {")
            append("\"time\":\"${sdf.format(Date(r.timestamp))}\",")
            append("\"package\":${js(r.packageName)},")
            append("\"app\":${js(r.appName)},")
            append("\"title\":${js(r.title)},")
            append("\"text\":${js(r.text)},")
            append("\"bigText\":${js(r.bigText)},")
            append("\"subText\":${js(r.subText)},")
            append("\"extra\":${r.extraJson.ifBlank { "{}" }}")
            append("}${if (i < records.size - 1) "," else ""}\n")
        }
        append("]\n")
    }
}

/** CSV 单元格转义 */
fun csvCell(s: String): String = "\"${s.replace("\"", "\"\"")}\""

/** JSON 字符串转义 */
fun js(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""