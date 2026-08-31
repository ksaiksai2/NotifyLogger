package com.ksai.notifylogger

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志（v2.0.6）
 *
 * 写 logcat，同时落盘到 filesDir/logs/notify.log（环形，保留最近 MAX_LINES 行）。
 * 目的：ColorOS 杀后台/冻结时主线程定时心跳会停，但日志文件仍保留最近记录，
 * 供「导出运行日志」一键打包发回排查监听是否存活、有没有在收通知。
 */
object AppLog {
    const val TAG = "NotifyLogger"
    private const val MAX_LINES = 600
    private const val TRIM_BYTES = 256 * 1024
    private var logFile: File? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)

    private fun file(ctx: Context): File {
        var f = logFile
        if (f == null) {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            f = File(dir, "notify.log")
            logFile = f
        }
        return f
    }

    @Synchronized
    private fun append(ctx: Context, level: Char, msg: String) {
        try {
            val th = Thread.currentThread().name ?: "?"
            val line = "${sdf.format(Date())} $level ${th.take(20)} | $msg"
            val f = file(ctx)
            f.appendText("$line\n")
            if (f.length() > TRIM_BYTES) trim(ctx)
        } catch (_: Exception) { /* 日志失败不打扰主流程 */ }
    }

    @Synchronized
    private fun trim(ctx: Context) {
        try {
            val f = file(ctx)
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    fun i(ctx: Context, msg: String) { Log.i(TAG, msg); append(ctx, 'I', msg) }
    fun d(ctx: Context, msg: String) { Log.d(TAG, msg); append(ctx, 'D', msg) }
    fun w(ctx: Context, msg: String) { Log.w(TAG, msg); append(ctx, 'W', msg) }
    fun e(ctx: Context, msg: String, tr: Throwable? = null) {
        Log.e(TAG, msg, tr)
        append(ctx, 'E', msg + (tr?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    /** 读取日志文本（倒序，最新在前），供界面展示/导出 */
    fun dump(ctx: Context, maxLines: Int): String {
        return try {
            val f = file(ctx)
            if (!f.exists()) return "(暂无运行日志)"
            f.readLines().takeLast(maxLines).asReversed().joinToString("\n")
        } catch (_: Exception) { "(读取日志失败)" }
    }
}
