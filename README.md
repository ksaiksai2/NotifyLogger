# NotifyLogger - 通知记事本

> Android 全量通知记录器：后台自动记录**所有**通知（不过滤、不分类），导出完整数据供 LLM 深度分析（消费记账 / 生活洞察），支持每日定时推送到 OpenClaw 等 AI 网关。

## 特性

- 🔔 **全量记录**：基于 `NotificationListenerService` 捕获所有通知，条目级去重，服务重启自动补抓活跃通知
- 📦 **完整字段**：title / text / bigText / subText / ticker + 全部可序列化 extras（JSON），为 LLM 分析保留完整语料
- 📤 **三种导出**：CSV（Excel 直开）/ JSON（含全部字段）/ 文本分享，写入公共下载目录 `Download/NotifyLogger/`
- 🤖 **每日定时推送**：WorkManager 每日 23:00 自动导出当日记录，POST 到配置的 OpenAI 兼容端点（如 OpenClaw Gateway `/v1/chat/completions`），配合 LLM 做统计分析
- 🎨 **Material 3 UI**：亮/暗双主题、统计卡片（今日/累计/应用数）、卡片列表、空状态引导、触底加载更多
- 🔒 **隐私设计**：数据全本地存储（SQLite），推送地址/Token 仅存本机，不进 APK

## 截图

_（待补充）_

## 快速开始

1. 安装 APK（`app/build/outputs/apk/debug/app-debug.apk` 或 Release 资产）
2. 打开 app → 授权「通知使用权」
3. 所有通知自动开始记录
4. 需要时点右下角 FAB 导出；可选配置「每日推送」把记录发给你自己的 AI 网关

## 推送配置

FAB → 每日推送设置：

| 字段 | 说明 |
|------|------|
| 推送地址 | OpenAI 兼容 `chat/completions` 端点 URL（如 `http://host:port/v1/chat/completions`） |
| Token | Bearer Token，仅保存在本机 SharedPreferences |

默认每天 23:00 触发；「立即发送测试」可手动验证链路。

> 例：配合 [OpenClaw Gateway](https://docs.openclaw.ai) 的 `gateway.http.endpoints.chatCompletions` 端点，可把每日通知记录直接交给 AI 归档并生成消费简报。

## 技术栈

- Kotlin · minSdk 26 (Android 8.0) · targetSdk 35
- AndroidX：AppCompat / RecyclerView / SwipeRefreshLayout / Material 3 / WorkManager
- 存储：SQLite（schema v2，`dedup_key` 去重 upsert）

## 项目结构

```
app/src/main/
├── java/com/ksai/notifylogger/
│   ├── MainActivity.kt              # M3 主界面：统计 + 列表 + 导出/分享/推送设置
│   ├── NotificationLoggerService.kt # 通知监听：全量捕获 + 补抓 + extras 序列化
│   ├── NotifyDb.kt                  # SQLite 存储（v2）
│   ├── NotifyJson.kt                # 共享 JSON/CSV 生成
│   ├── PushScheduler.kt             # WorkManager 每日 23:00 调度 + 推送配置
│   └── NotificationPushWorker.kt    # 每日推送 Worker（导出 → HTTP POST）
└── res/
    ├── layout/        # activity_main / item_notification / sheet_export
    ├── mipmap-anydpi-v26/  # 自适应图标（蓝渐变 + 玻璃四线）
    └── values(-night)/  # M3 主题与配色
```

## 数据字段

| 字段 | 来源 |
|------|------|
| timestamp | 通知发布时间 (postTime) |
| package_name / app_name | 来源包名 / 应用名 |
| title / text | EXTRA_TITLE / EXTRA_TEXT（空时回退 BIG_TEXT） |
| big_text / sub_text / ticker | EXTRA_BIG_TEXT / EXTRA_SUB_TEXT / tickerText |
| extra_json | 全部可序列化 extras |

## Roadmap

- [ ] 应用内筛选（应用 / 关键字 / 日期）
- [ ] 金额提取可视化预览
- [ ] WebDAV / HTTP 同步到 NAS
- [ ] Material You 动态取色

## 说明

- 分类 / 记账逻辑刻意不做在 app 内：原始数据交给 LLM 分析更灵活、更准确
- 国产 ROM 建议在系统设置中允许自启动 / 后台运行，保证定时任务稳定触发