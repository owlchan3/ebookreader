package org.readium.navigator.web.internals.webapi

import android.webkit.WebView

public class ReadiumCssApi(
    private val webView: WebView,
) {

    public fun setProperties(properties: Map<String, String?>) {
        val values = buildString {
            append("[")
            for ((k, v) in properties.entries) {
                append("""["${k.escapeJsString()}", "${v.orEmpty().escapeJsString()}"],""")
            }
            append("]")
        }
        val script = "readiumcss.setProperties(new Map($values));"
        webView.evaluateJavascript(script) {}
    }
}

/** 转义 [String]，使其可安全嵌入 JS 字符串字面量（双引号 + 反斜杠）。 */
private fun String.escapeJsString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
