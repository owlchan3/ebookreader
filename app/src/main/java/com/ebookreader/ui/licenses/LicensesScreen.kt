package com.ebookreader.ui.licenses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class LicenseItem(
    val name: String,
    val description: String,
    val license: String,
    val licenseText: String,
)

data class LicenseGroup(
    val category: String,
    val items: List<LicenseItem>,
)

val licenseGroups = listOf(
    LicenseGroup("Readium ToolKit", listOf(
        LicenseItem("readium-shared", "Readium 共享核心库", "BSD-3-Clause", BSD3_LICENSE),
        LicenseItem("readium-streamer", "出版物解析与流式传输", "BSD-3-Clause", BSD3_LICENSE),
        LicenseItem("readium-navigator", "阅读导航器核心", "BSD-3-Clause", BSD3_LICENSE),
        LicenseItem("readium-navigator-web-reflowable", "Web 重排式阅读导航器", "BSD-3-Clause", BSD3_LICENSE),
        LicenseItem("readium-navigator-web-fixedlayout", "Web 固定布局阅读导航器", "BSD-3-Clause", BSD3_LICENSE),
        LicenseItem("readium-adapter-pdfium", "PDFium 适配器（文档+导航器）", "BSD-3-Clause", BSD3_LICENSE),
        LicenseItem("readium-opds", "OPDS 目录解析", "BSD-3-Clause", BSD3_LICENSE),
        LicenseItem("readium-lcp", "LCP DRM 支持", "BSD-3-Clause", BSD3_LICENSE),
    )),
    LicenseGroup("AndroidX & Jetpack", listOf(
        LicenseItem("androidx-core-ktx", "Android 核心扩展库", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("androidx-activity-compose", "Activity Compose 集成", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("androidx-lifecycle", "生命周期管理（运行时 + ViewModel）", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("androidx-navigation-compose", "Compose 导航组件", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("androidx-room", "SQLite 对象映射库（运行时 + KTX + 编译器）", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("androidx-datastore-preferences", "键值对数据持久化", "Apache 2.0", APACHE2_LICENSE),
    )),
    LicenseGroup("Compose UI", listOf(
        LicenseItem("compose-ui", "Compose UI 核心（动画/基础/材质/UI/图形）", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("compose-material3", "Material Design 3 组件库", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("compose-material-icons-extended", "Material 图标扩展集", "Apache 2.0", APACHE2_LICENSE),
    )),
    LicenseGroup("Kotlin 生态", listOf(
        LicenseItem("kotlin-stdlib", "Kotlin 标准库", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("kotlinx-coroutines", "Kotlin 协程库（Core + Android）", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("kotlinx-serialization-json", "Kotlin JSON 序列化", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("kotlinx-datetime", "Kotlin 多平台日期时间", "Apache 2.0", APACHE2_LICENSE),
    )),
    LicenseGroup("第三方库", listOf(
        LicenseItem("OkHttp", "HTTP 客户端，用于 AI API 通信", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("Okio", "OkHttp/Coil 依赖的 I/O 库", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("Coil", "Compose 图片加载库", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("Jsoup", "HTML 解析器，用于网页抓取", "MIT", MIT_LICENSE),
        LicenseItem("Timber", "日志工具库", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("AndroidPdfViewer", "PDF 阅读器组件", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("PdfiumAndroid", "PDFium 的 Android 原生封装", "Apache 2.0", APACHE2_LICENSE),
        LicenseItem("PDFium", "Chromium 的 PDF 渲染引擎", "BSD-3-Clause", BSD3_LICENSE),
        LicenseItem("desugar_jdk_libs", "Java 8+ API 支持库（Desugar）", "GPL-2.0 with Classpath Exception", GPL2_CLASSPATH_LICENSE),
    )),
)

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
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            licenseGroups.forEach { group ->
                item(key = "header_${group.category}") {
                    Text(
                        group.category,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
                items(group.items, key = { "${group.category}_${it.name}" }) { item ->
                    LicenseCard(item)
                }
            }
            item {
                Spacer(Modifier.height(32.dp))
                Text(
                    "以上为 EBookReader 使用的主要开源组件。如有遗漏，请以各组件官方许可文件为准。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun LicenseCard(item: LicenseItem) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Description, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(item.description, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                Text(
                    item.license, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "展开/收起",
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            if (expanded) {
                HorizontalDivider()
                Text(
                    item.licenseText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(12.dp),
                )
            }
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
