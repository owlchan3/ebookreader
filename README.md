[README.md](https://github.com/user-attachments/files/31637903/README.md)
# EBookReader

一款 Android 电子书阅读器，基于 **Kotlin + Jetpack Compose + Material 3** 构建，阅读内核使用 [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit)（源码内置，见 `readium-kit/`）。

## 功能特性

- 📖 支持 EPUB（重排版）与 PDF（固定版式）阅读
- 📄 翻页 / 滚动两种阅读模式
- 🔖 书签管理
- ✍️ 划线 / 批注（高亮、下划线、波浪线，多种颜色）
- 🔊 听书（TTS 朗读）
- ✨ 智能推荐
- 💾 数据备份与恢复（导出 / 导入 zip）
- 📚 书籍导入、搜索、标签分类

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- Readium Kotlin Toolkit 3.3.0（BSD 协议）
- Room 数据库
- Kotlin Coroutines / Flow
- DataStore、Coil、OkHttp、Jsoup

## 目录结构

```
.
├── app/             # Android 应用（Kotlin + Compose）
├── readium-kit/     # Readium Kotlin Toolkit 源码（BSD 协议，内置）
├── gradle/          # Gradle Wrapper 与版本目录
├── build.gradle.kts
└── settings.gradle.kts
```

> 应用通过 composite build（`includeBuild("readium-kit")`）直接依赖内置的 Readium 源码。

## 构建环境

- JDK 17
- Android SDK：compileSdk 36 / minSdk 26 / targetSdk 36
- 使用仓库自带的 Gradle Wrapper（Gradle 9.1.0），无需单独安装

## 构建

```bash
# Debug（可直接安装测试）
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk

# Release
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

> 没有密钥时，`assembleDebug` 不受影响；`assembleRelease` 会因无签名而失败。

## 下载

预构建的 APK 见 [Releases](https://github.com/owlchan3/EBookReader/releases)。

## 许可

- `readium-kit/` 下的 Readium Kotlin Toolkit 采用 [BSD 许可证](readium-kit/LICENSE)。
- 应用代码（`app/`）目前未指定许可证，如需开源请自行添加 `LICENSE` 文件。
