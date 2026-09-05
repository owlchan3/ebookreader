package com.ebookreader.data.importer.mobi

import com.ebookreader.data.importer.BookImporter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/** 一个拆分后的章节：标题 + 已清洗的正文 HTML（片段，无 <html>/<head>）。 */
data class MobiChapter(val title: String, val bodyHtml: String)

private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

/** <mbp:pagebreak> 的起始字节串（大小写不敏感，含可能的属性/斜杠），用于字节层切章。 */
private const val PAGEBREAK_OPEN = "<mbp:pagebreak"

/** 超过此字符数的单章会按大小切分，避免 Readium 多栏排版卡顿/黑屏。 */
private const val MAX_CHAPTER_CHARS = 20_000

/** 视为「块级」的标签名（转文本时前后换行）。 */
private val BLOCK_TAG_NAMES = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "li", "tr", "table", "hr", "section", "article",
    "mbp:section", "mbp:pagebreak",
)

/** 丢弃 XML 1.0 不允许的控制字符，避免写出的 XHTML 非法。 */
private fun sanitizeHtml(s: String): String {
    if (s.isEmpty()) return s
    val sb = StringBuilder(s.length)
    for (c in s) {
        val cp = c.code
        if (cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D) continue
        sb.append(c)
    }
    return sb.toString()
}

/** 一个原始块：字节区间（相对解压后的正文流，供 filepos 定位）+ 解码后的 HTML。 */
private data class RawChunk(val start: Int, val end: Int, val html: String)

/** 内联目录条目：标题 + filepos 字节偏移。 */
private data class TocEntry(val title: String, val filepos: Int)

/** 在解压后的字节流中定位所有 <mbp:pagebreak> 标签的 [start, end) 字节区间。 */
private fun findPagebreakRanges(raw: ByteArray): List<IntArray> {
    val result = mutableListOf<IntArray>()
    var i = 0
    val n = raw.size
    while (i < n) {
        if (matchesIgnoreCase(raw, i, PAGEBREAK_OPEN)) {
            var j = i
            while (j < n && raw[j] != '>'.code.toByte()) j++
            if (j < n) {
                result.add(intArrayOf(i, j + 1))
                i = j + 1
                continue
            }
        }
        i++
    }
    return result
}

/** ASCII 大小写不敏感匹配（仅处理 A-Z/a-z）。 */
private fun matchesIgnoreCase(raw: ByteArray, at: Int, needle: String): Boolean {
    if (at + needle.length > raw.size) return false
    for (k in needle.indices) {
        val c = needle[k].code.toByte()
        var b = raw[at + k]
        if (b in 'A'.code.toByte()..'Z'.code.toByte()) b = (b + 32).toByte()
        if (b != c) return false
    }
    return true
}

/**
 * 把解压后的 mobi7 原始字节流切分为章节。
 *
 * mobi7 的 `filepos` 是页内跳转的字节偏移，不是切章边界（切它会导致标签被截断）。
 * 这里在**字节层**按 `<mbp:pagebreak>` 切章，并保留每个块的字节区间，据此把内联目录
 * （`<a filepos="N">标题</a>`）的 filepos 映射回章节，从而：①提取真实章节标题；②让书内
 * 目录超链接指向正确章节。无分页符时回退到 TXT 式识别 → h1–h6 → 按大小切分。
 */
fun splitMobiBytes(raw: ByteArray, codepage: Int, images: List<MobiImage>): List<MobiChapter> {
    val imageByResourceIndex = images.associateBy { it.resourceIndex }

    val pagebreaks = findPagebreakRanges(raw)
    if (pagebreaks.isNotEmpty()) {
        val ranges = mutableListOf<IntArray>()
        var start = 0
        for (pb in pagebreaks) {
            if (pb[0] > start) ranges.add(intArrayOf(start, pb[0]))
            start = pb[1]
        }
        if (start < raw.size) ranges.add(intArrayOf(start, raw.size))

        val chunks = ranges.map { r ->
            RawChunk(r[0], r[1], decodeMobiText(raw.copyOfRange(r[0], r[1]), codepage).trim())
        }
        return buildChaptersFromChunks(chunks, imageByResourceIndex)
    }

    // 无分页符：整篇解析后识别章节
    val html = decodeMobiText(raw, codepage)
    return splitMobiHtml(html, images)
}

/** 无分页符时的切分入口（字符串路径）。 */
fun splitMobiHtml(html: String, images: List<MobiImage>): List<MobiChapter> {
    val imageByResourceIndex = images.associateBy { it.resourceIndex }

    // 1) TXT 式章节识别（复用已有正则 + 过滤逻辑）
    val byTxt = splitByTxtRecognition(html, imageByResourceIndex)
    if (byTxt.size >= 2) return byTxt

    // 2) 回退：按 h1–h6 切分 / 整篇
    val byHeadings = splitByHeadingsOrWhole(html, imageByResourceIndex)

    // 3) 仍是大单章则按大小切分，避免排版卡顿
    if (byHeadings.size == 1 && byHeadings[0].bodyHtml.length > MAX_CHAPTER_CHARS) {
        return splitOversizedChapter(byHeadings[0])
    }
    return byHeadings
}

/** 从一块里提取内联目录条目（标题长度 ≥ 2，过滤「◎」「注」等脚注标记）。 */
private fun extractTocEntries(html: String): List<TocEntry> {
    val doc = Jsoup.parse(html)
    val entries = mutableListOf<TocEntry>()
    for (a in doc.select("a[filepos]")) {
        val t = a.text().trim()
        if (t.length < 2) continue
        val fp = a.attr("filepos").trim().toIntOrNull() ?: continue
        entries.add(TocEntry(t, fp))
    }
    return entries
}

/** 分页符切分后的块：定位真实目录块，按 filepos 把标题映射回各章，并重写目录超链接。 */
private fun buildChaptersFromChunks(
    chunks: List<RawChunk>,
    imageByResourceIndex: Map<Int, MobiImage>,
): List<MobiChapter> {
    val tocByChunk = chunks.map { extractTocEntries(it.html) }

    // 目录块 = 含目录条目最多的块（至少 2 条，避免把孤立的脚注链接当成目录）
    val tocIndex = tocByChunk.indices
        .maxByOrNull { tocByChunk[it].size }
        ?.takeIf { tocByChunk[it].size >= 2 }

    if (tocIndex == null) {
        val result = mutableListOf<MobiChapter>()
        for ((i, c) in chunks.withIndex()) {
            if (c.html.isBlank()) continue
            val title = firstHeadingText(c.html) ?: if (i == 0) "正文" else "第${i}部分"
            result.add(MobiChapter(title, cleanChunk(c.html, imageByResourceIndex)))
        }
        return result.filter { it.bodyHtml.isNotBlank() }
    }

    val tocEntries = tocByChunk[tocIndex]

    // 目录块之后的块为正文；每章取「filepos 落在该块字节区间内」的第一个目录条目作为标题
    val contentChunks = mutableListOf<Pair<Int, RawChunk>>()
    for (i in tocIndex + 1 until chunks.size) {
        if (chunks[i].html.isBlank()) continue
        contentChunks.add(i to chunks[i])
    }

    val frontChunks = chunks.subList(0, tocIndex).filter { it.html.isNotBlank() }
    val frontCount = frontChunks.size

    // filepos → 最终章节下标（目录占 1 位），用于目录超链接改写
    val fileposToIndex = mutableMapOf<Int, Int>()
    for ((j, cc) in contentChunks.withIndex()) {
        val index = frontCount + 1 + j
        for (e in tocEntries) {
            if (e.filepos in cc.second.start until cc.second.end) {
                fileposToIndex[e.filepos] = index
            }
        }
    }

    val result = mutableListOf<MobiChapter>()
    frontChunks.forEachIndexed { k, c ->
        val title = firstHeadingText(c.html) ?: if (frontCount == 1) "前言" else "前言 ${k + 1}"
        result.add(MobiChapter(title, cleanChunk(c.html, imageByResourceIndex)))
    }
    result.add(MobiChapter("目录", cleanTocChunk(chunks[tocIndex].html, imageByResourceIndex, fileposToIndex)))
    for ((j, cc) in contentChunks.withIndex()) {
        val title = tocEntries.firstOrNull { it.filepos in cc.second.start until cc.second.end }?.title
            ?: firstHeadingText(cc.second.html)
            ?: "第${j + 1}部分"
        result.add(MobiChapter(title, cleanChunk(cc.second.html, imageByResourceIndex)))
    }
    return result.filter { it.bodyHtml.isNotBlank() }
}

/** 提取块内首个 h1–h6 标题文本（空或过长返回 null）。 */
private fun firstHeadingText(html: String): String? {
    val doc = Jsoup.parse(html)
    return doc.select("h1,h2,h3,h4,h5,h6")
        .firstOrNull { it.text().trim().isNotEmpty() }
        ?.text()?.trim()
        ?.takeIf { it.length <= 100 }
}

/** 解析并清洗一个块：图片重写、Kindle 专用标签清理、剥离 filepos。 */
private fun cleanChunk(html: String, imageByResourceIndex: Map<Int, MobiImage>): String {
    val doc = Jsoup.parse(html)
    doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
    cleanBody(doc, imageByResourceIndex)
    for (a in doc.select("a[filepos]")) a.removeAttr("filepos")
    val body = doc.body() ?: return sanitizeHtml(doc.html())
    return body.html()
}

/** 目录块：把 <a filepos> 按 filepos→章节映射改写为 <a href="cN.xhtml">，其余 filepos 剥离。 */
private fun cleanTocChunk(
    html: String,
    imageByResourceIndex: Map<Int, MobiImage>,
    fileposToIndex: Map<Int, Int>,
): String {
    val doc = Jsoup.parse(html)
    doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
    cleanBody(doc, imageByResourceIndex)
    for (a in doc.select("a[filepos]")) {
        val fp = a.attr("filepos").trim().toIntOrNull()
        a.removeAttr("filepos")
        val idx = fp?.let { fileposToIndex[it] }
        if (idx != null) a.attr("href", "c${idx}.xhtml")
    }
    val body = doc.body() ?: return sanitizeHtml(doc.html())
    return body.html()
}

/** 图片 + Kindle 专用标签清理（pagebreak 已被切分掉，这里只剩 section/nu 与 aid/recindex）。 */
private fun cleanBody(doc: Document, imageByResourceIndex: Map<Int, MobiImage>) {
    for (img in doc.select("img")) {
        val rec = img.attr("recindex").trim()
        val n = rec.toIntOrNull()
        val target = if (n != null) imageByResourceIndex[n - 1] else null
        if (target != null) {
            img.attr("src", "images/${target.fileName}")
            // 细长的装饰分割线（如 720×6 渐变线）会被 Readium 的 img{height:auto}
            // 按宽高比压成 ~3px 细线，几乎不可见。这里保留其像素高度并用
            // object-fit:fill 拉伸到整行，恢复为可见的分割线。
            val h = img.attr("height").toIntOrNull()
            val w = img.attr("width").toIntOrNull()
            if (h != null && w != null && h in 1..24 && w > h * 3) {
                img.attr("style", "height:${h}px;object-fit:fill")
            }
        } else {
            img.remove()
        }
    }
    for (el in doc.allElements) {
        when (el.tagName().lowercase()) {
            "mbp:section", "mbp:nu" -> el.unwrap()
        }
        el.removeAttr("aid")
        el.removeAttr("recindex")
    }
}

/** 无分页符回退：整篇解析，按 h1–h6 标题切分，不足则整篇一章。 */
private fun splitByHeadingsOrWhole(html: String, imageByResourceIndex: Map<Int, MobiImage>): List<MobiChapter> {
    val doc = Jsoup.parse(html)
    doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
    cleanBody(doc, imageByResourceIndex)
    val body = doc.body() ?: return listOf(MobiChapter("正文", sanitizeHtml(doc.html())))

    val headings = body.select("h1,h2,h3,h4,h5,h6").filter {
        val t = it.text().trim(); t.isNotEmpty() && t.length <= 100
    }
    val chapters = if (headings.size >= 2) splitByHeadings(body) else listOf(MobiChapter("正文", body.html()))
    return chapters.map { MobiChapter(it.title, sanitizeHtml(it.bodyHtml)) }
        .filter { it.bodyHtml.isNotBlank() }
        .ifEmpty { listOf(MobiChapter("正文", sanitizeHtml(body.html()))) }
}

/** 展平 DOM：标题元素作为独立 chunk，其余整体作为 chunk；跳过 mbp:pagebreak。 */
private fun linearize(root: Node, chunks: MutableList<Node>) {
    for (child in root.childNodes()) {
        when {
            child is Element && child.tagName().lowercase() in HEADING_TAGS -> chunks.add(child)
            child is Element && child.tagName().lowercase() == "mbp:pagebreak" -> {}
            child is Element && child.select("h1,h2,h3,h4,h5,h6").isNotEmpty() -> linearize(child, chunks)
            else -> chunks.add(child)
        }
    }
}

/** 按标题切分：每个 h1–h6 作为新章开头，标题之前的正文作为「前言」。 */
private fun splitByHeadings(body: Element): List<MobiChapter> {
    val chunks = mutableListOf<Node>()
    linearize(body, chunks)

    val chapters = mutableListOf<MobiChapter>()
    val preamble = StringBuilder()
    var inPreamble = true
    var currentTitle: String? = null
    val current = StringBuilder()

    fun flushCurrent() {
        val h = current.toString().trim()
        if (currentTitle != null && h.isNotEmpty()) chapters.add(MobiChapter(currentTitle!!, h))
        currentTitle = null
        current.setLength(0)
    }

    for (chunk in chunks) {
        if (chunk is Element && chunk.tagName().lowercase() in HEADING_TAGS) {
            val t = chunk.text().trim()
            if (t.isEmpty() || t.length > 100) {
                if (inPreamble) preamble.append(chunk.outerHtml()) else current.append(chunk.outerHtml())
                continue
            }
            if (inPreamble) {
                val p = preamble.toString().trim()
                if (p.isNotEmpty()) chapters.add(MobiChapter("前言", p))
                inPreamble = false
            } else {
                flushCurrent()
            }
            currentTitle = t
            current.append(chunk.outerHtml())
        } else {
            val h = chunk.outerHtml()
            if (inPreamble) preamble.append(h) else current.append(h)
        }
    }
    flushCurrent()
    if (inPreamble) {
        val p = preamble.toString().trim()
        if (p.isNotEmpty()) chapters.add(0, MobiChapter("前言", p))
    }
    return chapters
}

// ── 无分页符/无标题时的 TXT 式识别（复用 BookImporter.detectChapters）──────────

/** 位置保持地把 HTML 转成纯文本（块级标签 → '\n'，展开常见实体），返回文本和「文本下标→HTML 下标」映射。 */
private fun htmlToTextWithOffsets(html: String): Pair<String, IntArray> {
    val text = StringBuilder(html.length)
    val map = ArrayList<Int>(html.length + 1)
    var i = 0
    val n = html.length
    while (i < n) {
        val c = html[i]
        when {
            c == '<' -> {
                val close = html.indexOf('>', i)
                if (close < 0) { i = n; break }
                val inner = html.substring(i + 1, close).trim()
                val name = inner.substringBefore(' ').lowercase().trimEnd('/')
                if (name == "br" || name in BLOCK_TAG_NAMES) {
                    text.append('\n'); map.add(i)
                }
                i = close + 1
            }
            c == '&' -> {
                val semi = html.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 9)) {
                    val decoded = decodeEntity(html.substring(i + 1, semi))
                    if (decoded != null) {
                        text.append(decoded); map.add(i); i = semi + 1
                    } else { text.append(c); map.add(i); i++ }
                } else { text.append(c); map.add(i); i++ }
            }
            else -> { text.append(c); map.add(i); i++ }
        }
    }
    map.add(n)
    return text.toString() to map.toIntArray()
}

/** 展开常见 HTML 实体（含十进制/十六进制数值实体）；无法识别返回 null。 */
private fun decodeEntity(e: String): String? {
    if (e.isEmpty()) return null
    return when (e) {
        "nbsp" -> " "
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> {
            if (e[0] != '#') return null
            val num = e.substring(1)
            val cp = if (num.startsWith("x", ignoreCase = true)) num.substring(1).toIntOrNull(16)
                else num.toIntOrNull()
            cp?.takeIf { it in 0x20..0xD7FF || it in 0xE000..0xFFFD }?.toChar()?.toString()
        }
    }
}

/** 复用已有 TXT 章节识别切分 HTML 正文；识别不足 2 章返回空（由调用方回退）。 */
private fun splitByTxtRecognition(html: String, imageByResourceIndex: Map<Int, MobiImage>): List<MobiChapter> {
    val doc = Jsoup.parse(html)
    doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
    cleanBody(doc, imageByResourceIndex)
    val body = doc.body() ?: return emptyList()
    val bodyHtml = body.html()

    val (text, map) = htmlToTextWithOffsets(bodyHtml)
    val detected = BookImporter.detectChapters(text)
    if (detected.size < 2) return emptyList()

    val chapters = mutableListOf<MobiChapter>()
    // 第一个标题之前的正文作「前言」
    val firstHtmlStart = map.getOrElse(detected.first().startIndex) { 0 }
    if (firstHtmlStart > 0) {
        val preamble = bodyHtml.substring(0, firstHtmlStart).trim()
        if (preamble.isNotBlank()) chapters.add(MobiChapter("前言", sanitizeHtml(preamble)))
    }
    for (i in detected.indices) {
        val start = map.getOrElse(detected[i].startIndex) { 0 }
        val end = if (i + 1 < detected.size) map.getOrElse(detected[i + 1].startIndex) { bodyHtml.length } else bodyHtml.length
        if (end <= start) continue
        val content = bodyHtml.substring(start, end).trim()
        if (content.isNotBlank()) chapters.add(MobiChapter(detected[i].title, sanitizeHtml(content)))
    }
    return chapters.filter { it.bodyHtml.isNotBlank() }
}

/** 把超长单章按块级边界切成接近 MAX_CHAPTER_CHARS 的多章。 */
private fun splitOversizedChapter(chapter: MobiChapter): List<MobiChapter> {
    val html = chapter.bodyHtml
    val (text, map) = htmlToTextWithOffsets(html)
    val total = text.length
    if (total <= MAX_CHAPTER_CHARS) return listOf(chapter)

    val count = (total + MAX_CHAPTER_CHARS - 1) / MAX_CHAPTER_CHARS
    val target = total / count

    val result = mutableListOf<MobiChapter>()
    var startText = 0
    var idx = 1
    while (startText < total) {
        var endText = (startText + target).coerceAtMost(total)
        if (endText < total) {
            val nl = text.indexOf('\n', endText)
            if (nl in (endText + 1)..(endText + 2000)) endText = nl
        }
        val content = html.substring(map[startText], map[endText]).trim()
        if (content.isNotBlank()) result.add(MobiChapter("第${idx}部分", sanitizeHtml(content)))
        startText = endText
        idx++
    }
    return result.ifEmpty { listOf(chapter) }
}
