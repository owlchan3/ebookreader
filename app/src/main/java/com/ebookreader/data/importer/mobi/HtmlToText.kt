package com.ebookreader.data.importer.mobi

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 视为「块级」的标签：前后换行，保留段落结构供章节识别。 */
private val BLOCK_TAGS = setOf(
    "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "li", "tr", "table", "hr", "section", "article",
    "mbp:pagebreak", "mbp:section",
)

/**
 * 把 MOBI 原始 HTML 转成带段落换行的纯文本（阶段1 用，供 detectChapters 识别章节）。
 */
fun htmlToPlainText(html: String): String {
    val doc = Jsoup.parse(html)
    val sb = StringBuilder()

    fun walk(node: Node) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> sb.append(child.text())
                is Element -> {
                    val tag = child.tagName().lowercase()
                    if (tag in BLOCK_TAGS) {
                        sb.append('\n')
                        walk(child)
                        sb.append('\n')
                    } else {
                        walk(child)
                    }
                }
            }
        }
    }

    walk(doc.body())
    return sb.toString()
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("(\\s*\\n){3,}"), "\n\n")
        .trim()
}
