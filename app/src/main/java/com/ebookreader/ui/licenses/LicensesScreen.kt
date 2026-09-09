package com.ebookreader.ui.licenses

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 开源许可：纯文本展示（可长按选中复制），无卡片、无分割线。

private val licenseListing = """
开源许可

EBookReader 使用了以下开源软件与数据资源，特此致谢。
各软件完整许可文本见文末附录，最终以各自官方许可文件为准。

【Readium ToolKit · BSD-3-Clause 许可证】
readium-shared —— Readium 共享核心库
readium-streamer —— 出版物解析与流式传输
readium-navigator —— 阅读导航器核心
readium-navigator-web-reflowable —— Web 重排式阅读导航器
readium-navigator-web-fixedlayout —— Web 固定布局阅读导航器
readium-adapter-pdfium —— PDFium 适配器（文档与导航器）
readium-opds —— OPDS 目录解析
readium-lcp —— LCP DRM 支持

【AndroidX 与 Jetpack · Apache License 2.0】
androidx-core-ktx —— Android 核心扩展库
androidx-activity-compose —— Activity Compose 集成
androidx-lifecycle —— 生命周期管理（运行时与 ViewModel）
androidx-navigation-compose —— Compose 导航组件
androidx-room —— SQLite 对象映射库（运行时、KTX 与编译器）
androidx-datastore-preferences —— 键值对数据持久化

【Compose UI · Apache License 2.0】
compose-runtime —— Compose 运行时
compose-ui —— Compose UI 核心
compose-ui-graphics —— Compose 图形
compose-foundation —— Compose 基础布局与手势
compose-animation —— Compose 动画
compose-material —— Material Design 2 组件库
compose-material3 —— Material Design 3 组件库
compose-material-icons-extended —— Material 图标扩展集

【Kotlin 生态 · Apache License 2.0】
kotlin-stdlib —— Kotlin 标准库
kotlinx-coroutines —— Kotlin 协程库（Core 与 Android）
kotlinx-serialization-json —— Kotlin JSON 序列化
kotlinx-datetime —— Kotlin 多平台日期时间

【第三方库】
OkHttp（Apache License 2.0）—— HTTP 客户端，用于 AI API 通信
Okio（Apache License 2.0）—— OkHttp / Coil 依赖的 I/O 库
Coil（Apache License 2.0）—— Compose 图片加载库
Jsoup（MIT）—— HTML 解析器，用于网页抓取
Timber（Apache License 2.0）—— 日志工具库
AndroidPdfViewer（Apache License 2.0）—— PDF 阅读器组件
PdfiumAndroid（Apache License 2.0）—— PDFium 的 Android 原生封装
PDFium（BSD-3-Clause）—— Chromium 的 PDF 渲染引擎
ONNX Runtime（MIT）—— 端侧向量嵌入与语义检索
Apache POI（Apache License 2.0）—— .doc 老格式二进制提取文字
OpenCC4j（Apache License 2.0）—— 词级繁简转换
desugar_jdk_libs（GPL-2.0 with Classpath Exception）—— Java 8+ API 支持库

【内置模型与数据资源】
BGE 中文嵌入模型（bge-small-zh）—— MIT —— 端侧语义检索
jieba 词典（jieba_dict / jieba_idf）—— MIT —— 中文分词与关键词提取
ECDICT 英汉词典（ecdict.jsonl）—— MIT —— 离线英汉查询
萌典（moedict.jsonl，教育部國語辭典）—— 以教育部授权为准 —— 离线中文查询
停用词表（百度 / 哈工大 / 四川大学）、THUOCL 词表 —— 公开词表，出处各异 —— 停用词过滤与新词发现
Xiu2 书源（official_sources.json / xiu2_shuyuan.json）—— 来源各异（Legado GPL-3.0）—— 网文搜索
""".trimIndent()

private val licenseAppendix = buildString {
    appendLine("【附录：完整许可文本】")
    appendLine()
    appendLine("【Apache License 2.0】")
    appendLine(APACHE2_LICENSE)
    appendLine()
    appendLine("【MIT License】")
    appendLine(MIT_LICENSE)
    appendLine()
    appendLine("【BSD 3-Clause License】")
    appendLine(BSD3_LICENSE)
    appendLine()
    appendLine("【GNU General Public License v2.0，with Classpath Exception】")
    appendLine(GPL2_CLASSPATH_LICENSE)
}

private val licensePlainText = licenseListing + "\n\n" + licenseAppendix

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开源许可") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        SelectionContainer {
            Text(
                text = licensePlainText,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

// License full texts
private const val APACHE2_LICENSE = """Apache License 2.0

Copyright (c) The respective authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License."""

private const val MIT_LICENSE = """MIT License

Copyright (c) The respective authors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

private const val BSD3_LICENSE = """BSD 3-Clause License

Copyright (c) the respective copyright holders. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE."""

private const val GPL2_CLASSPATH_LICENSE = """GNU General Public License v2.0, with Classpath Exception

This program is free software; you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the Free
Software Foundation; version 2 of the License
(see http://www.gnu.org/licenses/old-licenses/gpl-2.0.html).

This library is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE.

Linking this library statically or dynamically with other modules is making a
combined work based on this library. Thus, the terms and conditions of the
GNU General Public License cover the whole combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent modules, and
to copy and distribute the resulting executable under terms of your choice,
provided that you also meet, for each linked independent module, the terms and
conditions of the license of that module. An independent module is a module
which is not derived from or based on this library. If you modify this library,
you may extend this exception to your version of the library, but you are not
obligated to do so. If you do not wish to do so, delete this exception
statement from your version."""
