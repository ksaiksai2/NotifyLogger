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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
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
        view.findViewById<View>(R.id.optAi).setOnClickListener { dialog.dismiss(); showAiSettings() }
        view.findViewById<View>(R.id.optFilter).setOnClickListener { dialog.dismiss(); showFilterSettings() }
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
                val req = PushScheduler.sendNow(this)
                observeTestSendResult(req.id)
                Toast.makeText(this, "已触发发送，结果稍后弹出", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 通知过滤设置（v2.0.2 修复交互）
     * 模式：全部记录 / 仅记录所选 / 排除所选；勾选应用列表
     * 修复：RadioButton 必须设唯一 id（RadioGroup 靠 id 管理选中，NO_ID 会导致切不回其他模式）；
     *       选「记录全部」时应用列表禁用灰化。
     */
    private fun showFilterSettings() {
        val apps = loadLauncherApps()
        val currentMode = PushConfig.filterMode(this)
        val selected = PushConfig.filterApps(this).toMutableSet()

        val dp = resources.displayMetrics.density
        val padding = (16 * dp).toInt()

        // 模式单选（RadioButton 必须设唯一 id，否则 RadioGroup 选中逻辑失效）
        val idAll = View.generateViewId()
        val idWhitelist = View.generateViewId()
        val idBlacklist = View.generateViewId()
        val rbAll = RadioButton(this).apply { id = idAll; text = "记录全部通知" }
        val rbWhitelist = RadioButton(this).apply { id = idWhitelist; text = "仅记录所选应用" }
        val rbBlacklist = RadioButton(this).apply { id = idBlacklist; text = "排除所选应用" }

        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbAll)
            addView(rbWhitelist)
            addView(rbBlacklist)
            check(
                when (currentMode) {
                    PushConfig.FILTER_WHITELIST -> idWhitelist
                    PushConfig.FILTER_BLACKLIST -> idBlacklist
                    else -> idAll
                }
            )
        }

        // 应用列表（滚动 + 复选）
        val checkBoxes = mutableMapOf<String, CheckBox>()
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps.forEach { (pkg, label) ->
            val cb = CheckBox(this).apply {
                text = label
                isChecked = pkg in selected
            }
            checkBoxes[pkg] = cb
            listLayout.addView(cb)
        }
        val scroll = ScrollView(this).apply {
            addView(listLayout)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * dp).toInt()
            )
        }

        // 模式联动：选「记录全部」时禁用整个应用列表，勾了白勾的问题消除
        fun updateListEnabled() {
            val enabled = modeGroup.checkedRadioButtonId != idAll
            checkBoxes.values.forEach { it.isEnabled = enabled }
            scroll.isEnabled = enabled
        }
        modeGroup.setOnCheckedChangeListener { _, _ -> updateListEnabled() }
        updateListEnabled()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(modeGroup)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * dp).toInt()
            ).apply { topMargin = (12 * dp).toInt() })
        }

        AlertDialog.Builder(this)
            .setTitle("通知过滤")
            .setMessage("过滤后这些应用的通知不会入库，也不参与推送。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val mode = when (modeGroup.checkedRadioButtonId) {
                    idWhitelist -> PushConfig.FILTER_WHITELIST
                    idBlacklist -> PushConfig.FILTER_BLACKLIST
                    else -> PushConfig.FILTER_ALL
                }
                val chosen = checkBoxes.filterValues { it.isChecked }.keys.toSet()
                PushConfig.saveFilter(this, mode, chosen)
                val summary = when (mode) {
                    PushConfig.FILTER_ALL -> "记录全部"
                    PushConfig.FILTER_WHITELIST -> "仅记录 ${chosen.size} 个应用"
                    else -> "排除 ${chosen.size} 个应用"
                }
                Toast.makeText(this, "过滤已保存：$summary", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * AI 分析设置（v2.0.3）：选择直调云端 LLM 或走 OpenClaw 网关
     * - 直调：填 OpenAI 兼容 chat/completions 端点 + key + 模型，App 直接请求并弹窗展示分析结果
     * - OpenClaw：复用现有推送配置（地址+token），把记录发给 KAVIS 分析
     */
    private fun showAiSettings() {
        val dp = resources.displayMetrics.density
        val padding = (16 * dp).toInt()

        val currentMode = PushConfig.llmMode(this)

        // 模式单选（必须设唯一 id，RadioGroup 靠 id 管理选中态）
        val idDirect = View.generateViewId()
        val idOpenclaw = View.generateViewId()
        val rbDirect = RadioButton(this).apply { id = idDirect; text = "直调云端 LLM" }
        val rbOpenclaw = RadioButton(this).apply { id = idOpenclaw; text = "通过 OpenClaw（KAVIS）" }
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbDirect)
            addView(rbOpenclaw)
            check(if (currentMode == PushConfig.LLM_MODE_DIRECT) idDirect else idOpenclaw)
        }

        // 直调参数输入区
        val urlInput = EditText(this).apply {
            setText(PushConfig.llmUrl(this@MainActivity))
            hint = "端点 URL，如 https://api.deepseek.com/v1/chat/completions"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val keyInput = EditText(this).apply {
            setText(PushConfig.llmKey(this@MainActivity))
            hint = "LLM API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelInput = EditText(this).apply {
            setText(PushConfig.llmModel(this@MainActivity))
            hint = "模型，如 deepseek-chat"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val paramContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlInput)
            addView(keyInput)
            addView(modelInput)
        }

        val openclawHint = TextView(this).apply {
            text = "复用「每日推送设置」里的地址+token，把今日记录发给 KAVIS 分析。"
            textSize = 12f
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        val modeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeGroup)
            addView(paramContainer)
            addView(openclawHint)
        }

        // 切换模式时显示/隐藏直调参数
        fun updateVisibility() {
            val direct = modeGroup.checkedRadioButtonId == idDirect
            paramContainer.visibility = if (direct) View.VISIBLE else View.GONE
            openclawHint.visibility = if (direct) View.GONE else View.VISIBLE
        }
        modeGroup.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        updateVisibility()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(modeContainer)
        }

        AlertDialog.Builder(this)
            .setTitle("AI 分析")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val mode = if (modeGroup.checkedRadioButtonId == idDirect) {
                    PushConfig.LLM_MODE_DIRECT
                } else {
                    PushConfig.LLM_MODE_OPENCLAW
                }
                PushConfig.saveLlm(
                    this,
                    mode,
                    urlInput.text.toString(),
                    keyInput.text.toString(),
                    modelInput.text.toString()
                )
                Toast.makeText(this, "AI 分析设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("立即分析") { _, _ ->
                val mode = if (modeGroup.checkedRadioButtonId == idDirect) {
                    PushConfig.LLM_MODE_DIRECT
                } else {
                    PushConfig.LLM_MODE_OPENCLAW
                }
                PushConfig.saveLlm(
                    this,
                    mode,
                    urlInput.text.toString(),
                    keyInput.text.toString(),
                    modelInput.text.toString()
                )
                if (mode == PushConfig.LLM_MODE_DIRECT) {
                    if (!PushConfig.isLlmDirectConfigured(this)) {
                        Toast.makeText(this, "请先填写完整的直调参数（URL / Key / 模型）", Toast.LENGTH_LONG).show()
                    } else {
                        runDirectLlmAnalysis()
                    }
                } else {
                    if (!PushConfig.isConfigured(this)) {
                        Toast.makeText(this, "请先到「每日推送设置」填写地址和 token", Toast.LENGTH_LONG).show()
                    } else {
                        val req = PushScheduler.sendNow(this)
                        observeTestSendResult(req.id)
                        Toast.makeText(this, "已发送给 KAVIS 分析，结果稍后弹出", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 直调云端 LLM：收集今日记录 → POST chat/completions → 弹窗展示分析结果 */
    private fun runDirectLlmAnalysis() {
        val db = NotifyDb(this)
        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val records = db.queryBetween(startOfToday, System.currentTimeMillis())
        if (records.isEmpty()) {
            Toast.makeText(this, "今日暂无通知记录", Toast.LENGTH_SHORT).show()
            return
        }

        val prompt = buildString {
            appendLine("你是数据分析助手。以下是 Android 通知记录 JSON（字段：time/package/app/title/text/bigText/subText/extra）。")
            appendLine("请分析并输出简洁中文报告：")
            appendLine("1) 消费/支付类汇总（金额、商家）")
            appendLine("2) 日程/提醒/待办类")
            appendLine("3) 其他值得注意的通知")
            appendLine("4) 异常或可疑条目")
            appendLine("报告 600 字以内，用 markdown。")
            appendLine("---- 记录 ----")
            append(buildNotifyJson(records))
        }

        Toast.makeText(this, "AI 分析中…（${records.size} 条记录）", Toast.LENGTH_SHORT).show()
        Thread {
            val result = directLlmCall(prompt)
            runOnUiThread {
                if (result.startsWith("ERROR:")) {
                    Toast.makeText(this, result.removePrefix("ERROR:"), Toast.LENGTH_LONG).show()
                } else {
                    showAnalysisResult(result)
                }
            }
        }.start()
    }

    /** OpenAI 兼容 chat/completions 直调；返回正文或 ERROR: 前缀错误 */
    private fun directLlmCall(prompt: String): String {
        val urlStr = PushConfig.llmUrl(this)
        val apiKey = PushConfig.llmKey(this)
        val model = PushConfig.llmModel(this)
        return try {
            val body = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val respText = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            } catch (_: Exception) {
                ""
            }
            conn.disconnect()
            if (code !in 200..299) {
                "ERROR:❌ HTTP $code：${respText.take(200)}"
            } else {
                val json = JSONObject(respText)
                json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            }
        } catch (e: Exception) {
            "ERROR:❌ 请求失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 弹窗展示分析结果，支持复制 */
    private fun showAnalysisResult(content: String) {
        val dp = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = content
            textSize = 14f
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        scroll.addView(tv)

        AlertDialog.Builder(this)
            .setTitle("AI 分析结果")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("AI 分析", content))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 已安装的可启动应用（包名 → 应用名），用于过滤设置 */
    private fun loadLauncherApps(): List<Pair<String, String>> {
        return try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    val label = try {
                        info.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    pkg to label
                }
                .distinctBy { it.first }
                .sortedBy { it.second }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 观察「立即发送测试」的任务结果并 Toast 展示（v2.0.1）；只匹配本次 requestId */
    private fun observeTestSendResult(requestId: java.util.UUID) {
        val wm = WorkManager.getInstance(this)
        val liveData = wm.getWorkInfosForUniqueWorkLiveData(PushScheduler.TEST_WORK_NAME)
        lateinit var observer: Observer<List<WorkInfo>>
        observer = Observer { infos ->
            val info = infos.firstOrNull { it.id == requestId } ?: return@Observer
            if (info.state.isFinished) {
                val msg = info.outputData.getString(NotificationPushWorker.KEY_RESULT_MSG)
                    ?: "任务完成（无详细信息）"
                Toast.makeText(this@MainActivity, "发送结果：$msg", Toast.LENGTH_LONG).show()
                liveData.removeObserver(observer)
            }
        }
        liveData.observe(this, observer)
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