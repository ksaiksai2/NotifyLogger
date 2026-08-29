package com.ksai.notifylogger

import android.content.ContentValues
import android.content.Intent
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var db: NotifyDb
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var permissionCard: MaterialCardView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    private val adapter = NotifyAdapter()
    private var listReachedEnd = false
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = NotifyDb(this)

        // Android 15+ 强制 edge-to-edge：手动处理系统栏 insets，避免内容被状态栏/导航栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        permissionCard = findViewById(R.id.permissionCard)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener { loadStats(); loadMore(reset = true); swipeRefresh.isRefreshing = false }

        // 触底自动加载更多
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (!isLoading && !listReachedEnd && lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    loadMore(reset = false)
                }
            }
        })

        findViewById<View>(R.id.btnGrant).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        findViewById<View>(R.id.fabExport).setOnClickListener { showExportSheet() }

        // 调度每日推送（23:00 自动导出并发送到 KAVIS）
        PushScheduler.scheduleDailyPush(this)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        checkPermission()
        loadStats()
        loadMore(reset = true)
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun checkPermission() {
        val enabled = isListenerEnabled()
        permissionCard.visibility = if (enabled) View.GONE else View.VISIBLE
        statusDot.visibility = if (enabled) View.VISIBLE else View.GONE
        statusText.text = if (enabled) "监听运行中 · 全量记录所有通知" else "通知监听未开启"
    }

    private fun loadStats() {
        val cal = CalendarStartOfToday()
        val today = db.countSince(cal)
        val total = db.countTotal()
        val apps = db.countPackages()

        findViewById<TextView>(R.id.statToday).text = today.toString()
        findViewById<TextView>(R.id.statTotal).text = total.toString()
        findViewById<TextView>(R.id.statApps).text = apps.toString()
    }

    private fun loadMore(reset: Boolean) {
        if (isLoading) return
        isLoading = true
        val offset = if (reset) 0 else adapter.itemCount
        val items = db.queryRecent(PAGE_SIZE, offset)
        if (reset) adapter.submit(items) else adapter.append(items)
        listReachedEnd = items.size < PAGE_SIZE
        emptyView.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        recyclerView.visibility = if (adapter.itemCount == 0) View.GONE else View.VISIBLE
        isLoading = false
    }

    // ---------------- 导出 ----------------

    private fun showExportSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_export, null)
        view.findViewById<View>(R.id.optCsv).setOnClickListener { dialog.dismiss(); exportCsv() }
        view.findViewById<View>(R.id.optJson).setOnClickListener { dialog.dismiss(); exportJson() }
        view.findViewById<View>(R.id.optShare).setOnClickListener { dialog.dismiss(); shareRecords() }
        view.findViewById<View>(R.id.optPush).setOnClickListener { dialog.dismiss(); showPushSettings() }
        dialog.setContentView(view)
        dialog.show()
    }

    /** 每日推送设置：地址 + token，可立即发送测试 */
    private fun showPushSettings() {
        val urlInput = EditText(this).apply {
            setText(PushConfig.url(this@MainActivity))
            hint = "推送地址（默认 Tailscale）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val tokenInput = EditText(this).apply {
            setText(PushConfig.token(this@MainActivity))
            hint = "Gateway Bearer Token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val dp = resources.displayMetrics.density
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(urlInput)
            val gap = View(context)
            gap.layoutParams = LinearLayout.LayoutParams(1, (12 * dp).toInt())
            addView(gap)
            addView(tokenInput)
        }
        AlertDialog.Builder(this)
            .setTitle("每日推送设置")
            .setMessage("每天 23:00 自动把当日通知记录发送给 KAVIS（归档 + QQ 简报）。\n\n地址默认走 Tailscale（仅你的设备可达）；token 是电脑 openclaw.json 里 gateway.auth.token 的值，填一次本地保存。")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                PushConfig.save(this, urlInput.text.toString(), tokenInput.text.toString())
                PushScheduler.scheduleDailyPush(this)
                Toast.makeText(this, "已保存，每日 23:00 自动推送", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("立即发送测试") { _, _ ->
                PushConfig.save(this, urlInput.text.toString(), tokenInput.text.toString())
                PushScheduler.scheduleDailyPush(this)
                Toast.makeText(this, "已触发发送，结果见系统日志", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportCsv() {
        val records = db.queryAll()
        if (records.isEmpty()) {
            Toast.makeText(this, "还没有记录到任何通知", Toast.LENGTH_SHORT).show()
            return
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val content = buildString {
            append("时间,包名,应用,标题,内容,大文本,子文本,ticker\n")
            records.forEach { r ->
                append("${sdf.format(Date(r.timestamp))},")
                append("${csvCell(r.packageName)},")
                append("${csvCell(r.appName)},")
                append("${csvCell(r.title)},")
                append("${csvCell(r.text)},")
                append("${csvCell(r.bigText)},")
                append("${csvCell(r.subText)},")
                append("${csvCell(r.ticker)}\n")
            }
        }
        val name = "notify_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())}.csv"
        writeToDownloads(name, "text/csv", content) { uri ->
            Toast.makeText(this, "已导出 CSV：${uriToDisplay(uri)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportJson() {
        val records = db.queryAll()
        if (records.isEmpty()) {
            Toast.makeText(this, "还没有记录到任何通知", Toast.LENGTH_SHORT).show()
            return
        }
        val content = buildNotifyJson(records)
        val name = "notify_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())}.json"
        writeToDownloads(name, "application/json", content) { uri ->
            Toast.makeText(this, "已导出 JSON：${uriToDisplay(uri)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareRecords() {
        val records = db.queryAll().takeLast(200)
        if (records.isEmpty()) {
            Toast.makeText(this, "还没有记录到任何通知", Toast.LENGTH_SHORT).show()
            return
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val text = buildString {
            appendLine("📩 通知记录 ${records.size} 条（最近 200 条预览）")
            appendLine()
            records.forEach { r ->
                appendLine("${sdf.format(Date(r.timestamp))} [${r.appName}]")
                if (r.title.isNotBlank()) appendLine("  ${r.title}")
                if (r.text.isNotBlank()) appendLine("  ${r.text}")
            }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "分享通知记录"))
    }

    /** 写入公共 Downloads/NotifyLogger 目录（Android 10+ 走 MediaStore，用户文件管理器直接可见） */
    private fun writeToDownloads(fileName: String, mimeType: String, content: String, onDone: (Uri) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NotifyLogger")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                contentResolver.openOutputStream(uri).use { out ->
                    out?.write(content.toByteArray(Charsets.UTF_8)) ?: throw IllegalStateException("openOutputStream failed")
                }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                onDone(uri)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NotifyLogger")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileWriter(file).use { it.write(content) }
                onDone(Uri.fromFile(file))
            }
        } catch (e: Exception) {
            LogToast("导出失败：${e.message}")
        }
    }

    private fun uriToDisplay(uri: Uri): String {
        val file = uri.lastPathSegment ?: uri.toString()
        return "下载/NotifyLogger/$file"
    }

    private fun LogToast(msg: String) {
        android.util.Log.e("NotifyLogger", msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun csvEscape(s: String): String = csvCell(s)

    companion object {
        private const val PAGE_SIZE = 50
    }
}

private fun CalendarStartOfToday(): Long {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

class NotifyAdapter : RecyclerView.Adapter<NotifyAdapter.VH>() {

    private val items = mutableListOf<NotifyRecord>()
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun submit(data: List<NotifyRecord>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    fun append(data: List<NotifyRecord>) {
        val start = items.size
        items.addAll(data)
        notifyItemRangeInserted(start, data.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.appName.text = r.appName
        holder.timeText.text = sdf.format(Date(r.timestamp))

        val title = r.title.ifBlank { r.appName }
        holder.titleText.text = title
        holder.contentText.text = r.text
        holder.contentText.visibility = if (r.text.isBlank()) View.GONE else View.VISIBLE

        // 应用图标（带圆角背景）
        val icon = try {
            holder.itemView.context.packageManager.getApplicationIcon(r.packageName)
        } catch (_: Exception) {
            ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_notification) as Drawable?
        } ?: ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_notification) as Drawable?
        holder.iconView.setImageDrawable(icon)
    }

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.appIcon)
        val appName: TextView = view.findViewById(R.id.appName)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val titleText: TextView = view.findViewById(R.id.titleText)
        val contentText: TextView = view.findViewById(R.id.contentText)
    }
}