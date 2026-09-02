# EBookReader

一款 Android 电子书阅读器，基于 **Kotlin + Jetpack Compose + Material 3** 构建，阅读内核使用 [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) 3.3.0（源码内置，见 `readium-kit/`）。

除了阅读本身，还内置了 **AI 智能助手（基于书籍内容的检索问答）**、**AI 拆书**、**智能推荐**、**阅读统计**、**标签管理**、**数据备份与恢复** 等一整套功能。

---

## 功能特性

### 书库与书架
- 导入本地书籍（系统文件选择器）
- 排序：最近阅读 / 按书名 / 按作者 / 按添加时间
- 关键词搜索（书名或作者）
- 标签布尔检索：`AND` / `OR` / `NO` / 括号，带可视化 Token 编辑器（支持光标移动、删除、清空）
- 把当前标签筛选保存为「命名书架」
- 3 列封面网格（封面 / 书名 / 作者 / 阅读百分比）
- 长按进入多选：全选 / 取消 / 批量加标签 / 批量删除
- 导入去重与结果提示

### 阅读器
- 三种渲染模式：重排版（EPUB）/ 固定版式 / PDF
- 左右翻页 / 上下滚动 两种翻页模式
- 目录抽屉、章节跳转；TXT/DOCX 导入的书籍支持「刷新章节」「合并章节」「删除章节」
- 书签（含页批注）
- 划线：高光 / 横线 / 波浪线，6 种颜色（黄/绿/蓝/粉/橙/紫）
- 批注（笔记），与划线分开管理，可编辑样式/颜色/笔记
- 「批注与划线」列表（Tab 切换、编辑笔记）
- 书籍内全文搜索（命中词高亮）
- 阅读设置：字体大小 0.5–2.5x、亮度 20–100%、主题（默认/棕褐/暗黑）、翻页模式
- 选中文字：复制 / 分享 / 划线 / 批注（翻页 / 滚动模式均可用）
- TTS 听书：本地系统引擎 或 OpenAI 兼容云端合成；面板可拖动，支持上一句/播放暂停/下一句/停止、当前句高亮
- 阅读进度自动保存（每日阅读会话 + 每本书每日阅读记录）

### 书籍详情
- 封面放大、保存到相册、更换封面
- 元数据展示（书名 / 作者 / 格式 / 文件大小 / 累计阅读）
- 继续阅读 / 开始阅读
- AI 生成简介
- 标签添加 / 移除 / 新建
- 相关书籍（搜索多选添加）
- 编辑书籍信息（书名 / 作者 / 简介）
- 删除书籍（不删除源文件）

### AI 智能助手（对话）
- 多会话管理（新建 / 切换 / 删除 / 历史列表）
- 基于书籍内容的检索问答：查询扩展 → BM25 检索 → LLM 重排 → 流式回答
- 流式输出与「思考过程」展示（可展开/收起）
- 跨书导入参考资料（已拆书优先标记）
- 指定章节精确问答（章节多选筛选，绕过检索直接按原文回答）
- 时间类问题按原文顺序排序
- 诊断信息面板（索引状态 / 检索管线 / 分块详情 / tokens 估算）
- 导出对话（系统分享）

### AI 拆书
- 逐章梗概 + 全书总结 + 全书大纲（树形展示）
- 深度模块：人物关系 / 时间线 / 金句 / 人物小传 / 世界观与设定 / 拓展阅读（按书类型调整模块与标题）
- 书籍类型自动识别（长篇网文 / 同人 / 经典名著 / 历史 / 理工科 / 人文社科 等 12 类）
- 断点续传、章节失败重试（重试 3 次后可选「标记失败并继续」）
- 前台服务后台执行 + 通知栏进度
- 拆书结果搜索（章节标题或梗概）、删除拆书内容 / 进度

### 智能推荐
- 本地书推荐（题材 / 同作者 / 随机）
- 联网书推荐（Google Books / Open Library / SaltyLeo / 自定义网文搜索 / Pixiv）
- 下拉刷新、逐条「不感兴趣」
- 联网书外链打开

### 阅读统计
- 阅读概览（藏书 / 总字数 / 已读字数 / 阅读进度 / 总阅读时长）
- 近七天阅读时长曲线图 + 单日详情（当日阅读最久的三本书）
- 格式分布、热门作者 TOP 5、最近添加

### 设置
- **AI 智能助手**：API Key / API 地址 / 模型（预设 + 自定义 + 从 API 拉取）/ 拆书档位
- **语音朗读 (TTS)**：语速 0.5–2.0x、合成方案（系统引擎 / OpenAI 兼容）、TTS 引擎 / API 地址 / Key / 模型 / 音色
- **智能推荐**：偏好题材（权重 + 英文标签）、Google Books 密钥、自定义搜索地址、Pixiv 登录、清空「不感兴趣」记录
- 章节识别自定义正则
- 标签管理（含「已阅」特殊标签）
- 数据管理：存储详情 / 清理存储 / 删除某本书索引 / 清空全部索引 / 导出备份 / 导入备份（导入后自动重启）
- 关于：版本 / 开源许可 / 使用帮助

### 其他
- 内置开源许可列表（可展开完整许可文本）
- 内置使用帮助
- 深浅色主题（自动跟随系统）

---

## 支持的书籍格式

| 格式 | 说明 |
|---|---|
| EPUB | 重排版 + 固定版式，导入时做兼容性修复（未转义 `&`、DRM 剥离、多看文件名混淆、超大图片降采样等） |
| PDF | 经 Pdfium 渲染 |
| TXT | 自动检测编码（UTF-8/GBK/GB18030/GB2312/Big5 + UTF-16 BOM）→ 章节识别 → 转换为 EPUB 阅读 |
| DOCX | 直接解析 `word/document.xml` 提取文本 → 转换为 EPUB 阅读（无 Apache POI 依赖） |
| `.doc` | ❌ 不支持，提示转成 `.docx` / `.txt` |

> 注：`BookImporter` 的格式识别虽枚举了 `CBZ` / `MOBI` / `AZW` 扩展名，但应用仅集成了 Readium 的重排版 / 固定版式 / Pdfium 三个导航器，未集成对应解析适配器，这些格式实际无法被打开。

---

## 技术栈

| 类别 | 组件 | 版本 |
|---|---|---|
| 语言 | Kotlin | 2.3.20 |
| UI | Jetpack Compose（Material 3） | Compose 1.10.5 / Material3 1.4.0 |
| 阅读内核 | Readium Kotlin Toolkit | 3.3.0 |
| 架构组件 | Navigation Compose / Lifecycle | 2.9.7 / 2.10.0 |
| 数据库 | Room（KSP） | 2.8.4 |
| 异步 | Kotlin Coroutines | 1.10.2 |
| 序列化 | kotlinx.serialization / kotlinx.datetime | 1.10.0 / 0.7.1 |
| 存储 | DataStore Preferences | 1.2.1 |
| 图片 | Coil | 2.7.0 |
| 网络 | OkHttp | 4.12.0 |
| HTML 解析 | Jsoup | 1.22.2 |
| 日志 | Timber | 5.0.1 |
| 其他 | AndroidPdfViewer（PDF 渲染） / desugar_jdk_libs | 3.2.8 / 2.1.5 |

构建工具链：Gradle 9.1.0 / AGP 9.0.0 / KSP 2.3.4 / JDK 17。

---

## 架构

采用经典 **三层架构 + 手动依赖注入**（未使用 Hilt / Koin）：

```
┌─────────────────────────────────────────────┐
│ UI 层   ui/*Screen  ui/*ViewModel  ui/*Engine │
│         通过 Injector 获取 Repository / DAO   │
├─────────────────────────────────────────────┤
│ Domain 层  domain/model（纯 Kotlin 领域模型） │
│            domain/repository（接口，不依赖 Room）│
├─────────────────────────────────────────────┤
│ Data 层   data/local（Entity + DAO + AppDatabase）│
│           data/mapper（Entity ↔ Domain 映射）│
│           data/repository（Repository 实现）  │
│           data/network（外部服务客户端）        │
│           data/importer / data/backup         │
└─────────────────────────────────────────────┘
```

- 依赖注入：`Injector` 为 Kotlin `object`（进程级服务定位器），`init(context)` 一次性装配数据库、5 个 Repository 与 3 个网络客户端。
- 列表类查询普遍返回 `Flow<List<T>>`，写操作与单条查询用 `suspend`。
- DAO 中的 3 类数据（拆书结果、每日阅读统计）由 UI 层直接经 `Injector.appDatabase().xxxDao()` 访问，未封装 Repository。

## 目录结构

```
ebook-reader/
├── app/
│   └── src/main/
│       ├── java/com/ebookreader/
│       │   ├── data/
│       │   │   ├── local/          # Room：entity / dao / AppDatabase
│       │   │   ├── repository/     # Repository 接口实现
│       │   │   ├── network/        # 外部服务客户端（AI / 推荐 / TTS / Pixiv / 网文）
│       │   │   ├── importer/       # BookImporter（导入、TXT/DOCX→EPUB、EPUB 修复）
│       │   │   ├── backup/         # BackupManager（导出/导入备份）
│       │   │   └── mapper/         # Entity ↔ Domain 映射
│       │   ├── domain/
│       │   │   ├── model/          # 纯 Kotlin 领域模型
│       │   │   └── repository/     # Repository 接口
│       │   ├── di/                 # Injector
│       │   └── ui/
│       │       ├── bookshelf/      # 书库（我的书架）
│       │       ├── reader/         # 阅读器 + TtsPlaybackService + 划线样式
│       │       ├── detail/         # 书籍详情
│       │       ├── settings/       # 设置
│       │       ├── recommend/      # 智能推荐
│       │       ├── chat/           # AI 对话
│       │       ├── decompose/      # AI 拆书
│       │       ├── stats/          # 阅读统计
│       │       ├── tags/           # 书架/标签管理
│       │       ├── navigation/     # 路由与导航图
│       │       ├── common/         # 通用组件（滚动条等）
│       │       ├── theme/          # 主题
│       │       ├── licenses/       # 开源许可
│       │       └── help/           # 使用帮助
│       ├── MainActivity.kt
│       ├── EBookReaderApp.kt
│       ├── res/
│       └── AndroidManifest.xml
├── readium-kit/        # Readium Kotlin Toolkit 3.3.0 源码（BSD，内置）
├── gradle/             # Gradle Wrapper 与版本目录（libs.versions.toml）
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

> 应用通过 composite build（`settings.gradle.kts` 中的 `includeBuild("readium-kit")`）直接依赖内置的 Readium 源码，而非发布版构件。

---

## 数据模型（Room）

数据库名 `ebook_reader.db`，当前版本 **19**。共 14 张表：

| 表 | 用途 |
|---|---|
| `books` | 书籍元数据与阅读进度（标题/作者/简介/封面/文件路径/格式/当前页/定位器/累计时长等） |
| `tags` | 标签（名称唯一） |
| `book_tag_cross_ref` | 书 ↔ 标签 多对多关联 |
| `book_relation_cross_ref` | 相关书籍关联（自动/手动/屏蔽） |
| `tag_groups` | 标签分组（命名书架，含逻辑类型与查询串） |
| `bookmarks` | 书签（Locator JSON 存储） |
| `annotations` | 划线/批注（独立于书签，含样式/颜色/笔记） |
| `chapters` | 用户自定义/识别出的章节 |
| `conversations` | AI 对话会话 |
| `chat_messages` | 聊天消息 |
| `book_chunks` | 正文切块（RAG 检索用，含字符偏移与事件摘要） |
| `daily_reading_sessions` | 每日阅读总时长 |
| `daily_book_reading` | 每本书每日阅读时长 |
| `book_decomposition` | AI 拆书结果（逐章梗概/全书总结/大纲/深度模块等，JSON 存储） |

---

## 外部服务与 API 依赖

以下功能依赖外部服务，均可在「设置」中配置，未配置时不启用：

| 功能 | 对接方式 |
|---|---|
| AI 对话 / 拆书 | OpenAI 兼容 `POST /chat/completions`，默认 `https://api.deepseek.com/v1`、模型 `deepseek-chat`（支持 `deepseek-reasoner` 的思维链；预设另含 claude-sonnet-5 / claude-opus-5 / gpt-4o / gpt-4o-mini） |
| 云端 TTS | OpenAI 兼容 `/audio/speech`（兼容 SiliconFlow / Moonshot / 火山 Doubao / 小米 MiMo 等） |
| 出版书推荐 | Google Books API、Open Library、SaltyLeo（豆瓣数据） |
| 网文搜索 | 解析笔趣阁类站点搜索页（默认 `https://www.52bqg.net`，可自定义） |
| Pixiv 小说 | Pixiv App API（需用户填写 refresh_token） |

推荐功能返回的都是**真实联网数据源**的书，不依赖 LLM 生成条目。

---

## 构建环境

- JDK 17
- Android SDK：`compileSdk 36` / `minSdk 26`（Android 8.0）/ `targetSdk 36`
- 使用仓库自带 Gradle Wrapper（Gradle 9.1.0），无需单独安装

## 构建

```bash
# Debug（可直接安装测试）
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk

# Release（需配置签名，见下）
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

## Release 签名

正式版 APK 需要签名，仓库**不包含**签名密钥（安全考虑）。你需要自备：

1. 在项目根目录创建 `keystore.properties`：
   ```properties
   storeFile=keystore/release.jks
   storePassword=你的密码
   keyAlias=你的别名
   keyPassword=你的密码
   ```
2. 把签名文件放到 `keystore/release.jks`。

> `keystore.properties`、`keystore/`、`*.jks`、`*.keystore` 已在 `.gitignore` 中忽略，不会被提交。没有密钥时 `assembleDebug` 不受影响；`assembleRelease` 会因无签名而失败。

## 下载

预构建的 APK 见 [Releases](https://github.com/owlchan3/EBookReader/releases)。

## 已知限制 / 注意事项

- 章节的「刷新 / 合并 / 删除」仅对 **TXT / DOCX 导入**（已转换为 EPUB）的书籍可用；原生 EPUB / PDF 不支持。
- 选中文字（复制 / 分享 / 划线 / 批注）在 **翻页、滚动** 两种模式下均可用；翻页模式的跨栏选择闪烁已通过触控边缘钳制修复。
- 拆书档位参数（精简/标准/深度）已定义，但当前拆书引擎固定按「深度」档运行。

## 许可

- `readium-kit/` 下的 Readium Kotlin Toolkit 采用 [BSD 许可证](readium-kit/LICENSE)。