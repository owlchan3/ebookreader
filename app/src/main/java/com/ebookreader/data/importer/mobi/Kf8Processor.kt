package com.ebookreader.data.importer.mobi

import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream

/**
 * KF8（AZW3）专用解析：从 rawML 碎片流重建 XHTML 章节。
 *
 * 算法移植自 KindleUnpack（mobi_index.py / mobi_k8proc.py），并已在 Python 端到端原型上验证：
 * 骨架表(skeltbl) + 碎片表(fragtbl) 经 buildParts 拼回完整 part，再把 kindle 专用标记剥离、
 * 内联 CSS、解析 kindle:embed/kindle:pos:fid 图片与脚注链接。
 */
object Kf8Processor {

    /** 可见文本少于该长度的章节视为“空壳”（如封面图被单独抽出后遗留的空 part），剔除。 */
    private const val MIN_CHAPTER_TEXT = 20

    /**
     * 把 rawML 解析为章节列表。
     * @param images 图片资源（resourceIndex 相对 firstResource 的 0-based 序号）。
     */
    fun parse(
        pdb: PdbHeader,
        bytes: ByteArray,
        rawMl: ByteArray,
        header: MobiHeader,
        images: List<MobiImage>,
    ): List<MobiChapter> {
        val flows = parseFlows(rawMl, pdb, bytes, header)
        val flow0 = flows[0]
        val cssText = concat(flows.subList(1, flows.size))

        val skeltbl = buildSkeletonTable(pdb, bytes, header)
        val fragtbl = buildFragmentTable(pdb, bytes, header)

        val parts = buildParts(flow0, skeltbl, fragtbl)
        val imageByResourceIndex = images.associateBy { it.resourceIndex }

        // 每个 part 在重建全文中的起始字节偏移（kindle:pos:fid 的 pos 是全文偏移）
        val partStarts = IntArray(parts.size + 1)
        var acc = 0
        for (i in parts.indices) {
            partStarts[i] = acc
            acc += parts[i].size
        }
        partStarts[parts.size] = acc
        val anchorsPerPart = parts.map { findAnchors(it) }

        return parts.mapIndexed { i, part ->
            val html = processPart(part, cssText, imageByResourceIndex, fragtbl, partStarts, anchorsPerPart)
            val title = extractTitle(html) ?: "第${i + 1}部分"
            MobiChapter(title, html)
        }
            // 封面图被单独抽到 cover.xhtml 后，原封面所在的 part 只剩空壳（几乎无可见文本），
            // 渲染成一张空白/黑页。剔除这类近空章节（无跨章脚注，索引无需重映射）。
            .filter { Jsoup.parse(it.bodyHtml).text().trim().length >= MIN_CHAPTER_TEXT }
    }

    // ── base32（kindle:embed / kindle:pos:fid 的 id 用 0-9 A-V 字母表，无 WXYZ） ──
    private fun fromBase32(s: String): Int {
        var v = 0
        for (ch in s) {
            v = if (ch in '0'..'9') (v shl 5) + (ch - '0')
            else (v shl 5) + (ch - 'A' + 10)
        }
        return v
    }

    // ── 大端无符号读（记录内字节，非 ByteBuffer） ──
    private fun u32(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    private fun u16(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

    private fun u8(d: ByteArray, o: Int): Int = d[o].toInt() and 0xFF

    private fun concat(list: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (b in list) out.write(b)
        return out.toByteArray()
    }

    // ── FDST 流表 ──
    private fun parseFlows(rawMl: ByteArray, pdb: PdbHeader, bytes: ByteArray, header: MobiHeader): List<ByteArray> {
        val fdst = pdb.loadRecord(bytes, header.fdst)
        if (fdst.size < 4 || fdst[0] != 'F'.code.toByte() || fdst[1] != 'D'.code.toByte() ||
            fdst[2] != 'S'.code.toByte() || fdst[3] != 'T'.code.toByte()
        ) {
            throw MobiException("KF8 流表(FDST)缺失，无法解析")
        }
        val numFlows = u32(fdst, 8)
        val starts = IntArray(numFlows) { u32(fdst, 12 + it * 8) }
        val flows = ArrayList<ByteArray>(numFlows)
        for (i in 0 until numFlows) {
            val s = starts[i]
            val e = if (i + 1 < numFlows) starts[i + 1] else rawMl.size
            flows.add(rawMl.copyOfRange(s, e))
        }
        if (flows.isEmpty()) throw MobiException("KF8 正文流为空")
        return flows
    }

    // ── 变长整数（vwi）：最高位 0x80 为末字节，且该字节计入值 ──
    private fun vwi(d: ByteArray, offset: Int): Pair<Int, Int> {
        var value = 0
        var consumed = 0
        while (true) {
            val v = d[offset + consumed].toInt() and 0xFF
            consumed++
            value = (value shl 7) or (v and 0x7F)
            if (v and 0x80 != 0) break
        }
        return consumed to value
    }

    // ── MobiIndex（骨架/碎片表共用，移植 mobi_index.py） ──
    private data class TagX(val tag: Int, val valuesPerEntry: Int, val mask: Int, val endFlag: Int)
    private data class TagVal(val tag: Int, val valueCount: Int?, val valueBytes: Int?, val valuesPerEntry: Int)
    private data class IndxHeader(
        val len: Int, val type: Int, val gen: Int, val start: Int, val count: Int,
        val code: Int, val lng: Int, val total: Int, val ordt: Int, val ligt: Int,
        val nligt: Int, val nctoc: Int,
    )

    private fun countSetBits(value: Int): Int {
        var v = value
        var c = 0
        repeat(8) {
            if (v and 1 != 0) c++
            v = v shr 1
        }
        return c
    }

    private fun parseIndxHeader(d: ByteArray): Triple<IndxHeader, ByteArray?, ShortArray?> {
        val vals = IntArray(13) { u32(d, 4 + it * 4) }
        val h = IndxHeader(
            vals[0], vals[2], vals[3], vals[4], vals[5],
            vals[6], vals[7], vals[8], vals[9], vals[10], vals[11], vals[12],
        )
        var ordt1: ByteArray? = null
        var ordt2: ShortArray? = null
        if (d.size >= 0xa4 + 20) {
            val ocnt = u32(d, 0xa4)
            val oentries = u32(d, 0xa4 + 4)
            val op1 = u32(d, 0xa4 + 8)
            val op2 = u32(d, 0xa4 + 12)
            if (h.code == 0xfdea || ocnt != 0 || oentries > 0) {
                ordt1 = d.copyOfRange(op1 + 4, op1 + 4 + oentries)
                ordt2 = ShortArray(oentries) { u16(d, op2 + 4 + it * 2).toShort() }
            }
        }
        return Triple(h, ordt1, ordt2)
    }

    private fun readTagSection(d: ByteArray, start: Int): Pair<Int, List<TagX>> {
        var controlByteCount = 0
        val tags = mutableListOf<TagX>()
        if (start + 4 <= d.size && d[start] == 'T'.code.toByte() && d[start + 1] == 'A'.code.toByte() &&
            d[start + 2] == 'G'.code.toByte() && d[start + 3] == 'X'.code.toByte()
        ) {
            val firstEntryOffset = u32(d, start + 4)
            controlByteCount = u32(d, start + 8)
            var i = 12
            while (i < firstEntryOffset) {
                val p = start + i
                tags.add(TagX(u8(d, p), u8(d, p + 1), u8(d, p + 2), u8(d, p + 3)))
                i += 4
            }
        }
        return controlByteCount to tags
    }

    private fun getTagMap(
        controlByteCount: Int,
        tagTable: List<TagX>,
        d: ByteArray,
        startPos: Int,
        endPos: Int,
    ): Map<Int, List<Int>> {
        val tags = mutableListOf<TagVal>()
        var controlByteIndex = 0
        var dataStart = startPos + controlByteCount
        for (tx in tagTable) {
            var mask = tx.mask
            if (tx.endFlag == 0x01) {
                controlByteIndex++
                continue
            }
            val cbyte = u8(d, startPos + controlByteIndex)
            var value = cbyte and mask
            if (value != 0) {
                if (value == mask) {
                    if (countSetBits(mask) > 1) {
                        val (consumed, v) = vwi(d, dataStart)
                        dataStart += consumed
                        tags.add(TagVal(tx.tag, null, v, tx.valuesPerEntry))
                    } else {
                        tags.add(TagVal(tx.tag, 1, null, tx.valuesPerEntry))
                    }
                } else {
                    while (mask and 1 == 0) {
                        mask = mask shr 1
                        value = value shr 1
                    }
                    tags.add(TagVal(tx.tag, value, null, tx.valuesPerEntry))
                }
            }
        }
        val tagMap = HashMap<Int, List<Int>>()
        for (tv in tags) {
            val values = mutableListOf<Int>()
            if (tv.valueCount != null) {
                repeat(tv.valueCount) {
                    repeat(tv.valuesPerEntry) {
                        val (consumed, v) = vwi(d, dataStart)
                        dataStart += consumed
                        values.add(v)
                    }
                }
            } else {
                var totalConsumed = 0
                val vb = tv.valueBytes ?: 0
                while (totalConsumed < vb) {
                    val (consumed, v) = vwi(d, dataStart)
                    dataStart += consumed
                    totalConsumed += consumed
                    values.add(v)
                }
            }
            tagMap[tv.tag] = values
        }
        return tagMap
    }

    private fun readCtoc(d: ByteArray): Map<Int, ByteArray> {
        val ctoc = HashMap<Int, ByteArray>()
        var offset = 0
        while (offset < d.size) {
            if (d[offset] == 0.toByte()) break
            val idx = offset
            val (pos, ilen) = vwi(d, offset)
            offset += pos
            ctoc[idx] = d.copyOfRange(offset, offset + ilen)
            offset += ilen
        }
        return ctoc
    }

    private data class IndexData(
        val entries: List<Pair<ByteArray, Map<Int, List<Int>>>>,
        val ctoc: Map<Int, ByteArray>,
    )

    private fun getIndexData(pdb: PdbHeader, bytes: ByteArray, idx: Int): IndexData {
        val entries = mutableListOf<Pair<ByteArray, Map<Int, List<Int>>>>()
        val ctocText = HashMap<Int, ByteArray>()
        var d = pdb.loadRecord(bytes, idx)
        val (idxhdr, _, _) = parseIndxHeader(d)
        val indexCount = idxhdr.count
        var recOff = 0
        val off = idx + indexCount + 1
        for (j in 0 until idxhdr.nctoc) {
            val cdata = pdb.loadRecord(bytes, off + j)
            for ((k, v) in readCtoc(cdata)) ctocText[k + recOff] = v
            recOff += 0x10000
        }
        val (controlByteCount, tagTable) = readTagSection(d, idxhdr.len)
        for (i in idx + 1 until idx + 1 + indexCount) {
            d = pdb.loadRecord(bytes, i)
            val (hdrinfo, _, ordt2) = parseIndxHeader(d)
            val idxtPos = hdrinfo.start
            val entryCount = hdrinfo.count
            val idxPositions = IntArray(entryCount + 1)
            for (j in 0 until entryCount) idxPositions[j] = u16(d, idxtPos + 4 + 2 * j)
            idxPositions[entryCount] = idxtPos
            for (j in 0 until entryCount) {
                val startPos = idxPositions[j]
                val endPos = idxPositions[j + 1]
                val textLength = u8(d, startPos)
                var text = d.copyOfRange(startPos + 1, startPos + 1 + textLength)
                if (ordt2 != null) {
                    val mapped = ByteArray(text.size)
                    for (x in text.indices) mapped[x] = ordt2[u8(text, x)].toByte()
                    text = mapped
                }
                val tagMap = getTagMap(controlByteCount, tagTable, d, startPos + 1 + textLength, endPos)
                entries.add(text to tagMap)
            }
        }
        return IndexData(entries, ctocText)
    }

    // ── 骨架表 / 碎片表 ──
    private data class Skeleton(val fragcnt: Int, val skelpos: Int, val skellen: Int)
    private data class Fragment(val insertpos: Int, val filenum: Int, val seqnum: Int, val startpos: Int, val length: Int)

    private fun buildSkeletonTable(pdb: PdbHeader, bytes: ByteArray, header: MobiHeader): List<Skeleton> {
        return getIndexData(pdb, bytes, header.skelidx).entries.map { (_, tagMap) ->
            Skeleton(
                fragcnt = tagMap[1]?.getOrNull(0) ?: 0,
                skelpos = tagMap[6]?.getOrNull(0) ?: 0,
                skellen = tagMap[6]?.getOrNull(1) ?: 0,
            )
        }
    }

    private fun buildFragmentTable(pdb: PdbHeader, bytes: ByteArray, header: MobiHeader): List<Fragment> {
        return getIndexData(pdb, bytes, header.fragidx).entries.map { (text, tagMap) ->
            Fragment(
                insertpos = String(text, Charsets.US_ASCII).trim().toIntOrNull() ?: 0,
                filenum = tagMap[3]?.getOrNull(0) ?: 0,
                seqnum = tagMap[4]?.getOrNull(0) ?: 0,
                startpos = tagMap[6]?.getOrNull(0) ?: 0,
                length = tagMap[6]?.getOrNull(1) ?: 0,
            )
        }
    }

    // ── buildParts：骨架壳 + 碎片拼回（copyOfRange 上界按 Python 切片语义 clamp，避免越界崩溃） ──
    private fun buildParts(flow0: ByteArray, skeltbl: List<Skeleton>, fragtbl: List<Fragment>): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        var fragptr = 0
        for (sk in skeltbl) {
            var baseptr = sk.skelpos + sk.skellen
            var skeleton = flow0.copyOfRange(sk.skelpos, minOf(baseptr, flow0.size))
            repeat(sk.fragcnt) {
                val fr = fragtbl[fragptr]
                val slice = flow0.copyOfRange(baseptr, minOf(baseptr + fr.length, flow0.size))
                val rel = fr.insertpos - sk.skelpos
                skeleton = skeleton.copyOfRange(0, minOf(rel, skeleton.size)) + slice +
                    skeleton.copyOfRange(minOf(rel, skeleton.size), skeleton.size)
                baseptr += fr.length
                fragptr++
            }
            parts.add(skeleton)
        }
        return parts
    }

    // ── 单个 part 处理：剥离 Kindle 专用标记、内联 CSS、解析图片/脚注 ──
    private val linkRe = Regex("""(?i)<link\b[^>]*kindle:flow:[^>]*>""")
    private val embedRe = Regex("""kindle:embed:([0-9A-V]+)""")
    private val posfidRe = Regex("""kindle:pos:fid:([0-9A-V]+):off:([0-9A-V]+)""")
    private val fontUrlCommaRe = Regex("""(?s),\s*url\(kindle:embed:[0-9A-V]+\)""")
    private val fontUrlRe = Regex("""url\(kindle:embed:[0-9A-V]+\)""")
    private val aidRe = Regex("""(?i)\s+aid\s*=\s*["'][^"']*["']""")
    // 真实 MOBI 字体 url 形如 url(res:///...)、url(OEBPS/Fonts/...)、url(/mnt/...)，
    // 而非上面两个正则匹配的 url(kindle:embed:...)，剥不干净。这里直接整段去掉
    // @font-face（字体文件本就不在 EPUB 里），并清掉 Kindle 私有 duokan-* 声明与
    // .imgh 的整页高浮动（height:400% 会破坏 Readium 多栏分页，产生空白栏）。
    private val fontFaceBlockRe = Regex("""(?s)@font-face\s*\{.*?\}""")
    private val duokanDeclRe = Regex("""(?i)\s*[-a-z]*duokan[-a-z]*\s*:\s*[^;}]+;?""")
    private val height400Re = Regex("""(?i)height\s*:\s*400%\s*;?""")

    // ── 浅色主题下提亮书内深色文字 ──
    // 书籍 CSS 常给强调文字定义很深的彩色（如 #221630 深紫、#343 深绿）。
    // 浅色主题（白底/棕褐底，正文近黑）下它们与正文几乎同色、看不出色彩；暗黑主题正文是
    // 浅色，深色强调文字自然可见。这里为每个“过深且带色彩”的 color 追加一条仅在浅色主题
    // 生效的覆盖规则（按 HSL 把亮度提到 40%、色相不变）；暗黑主题（背景 #000000）不受影响。
    private val cssBlockRe = Regex("""(?s)([^{}]+)\{([^{}]*)\}""")
    private val colorValueRe = Regex("""(?i)(?:^|[;}])\s*color\s*:\s*([^;}]+)""")
    private val hexColorRe = Regex("""#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})""")
    private val rgbColorRe = Regex("""(?i)rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)""")

    private data class Rgb(val r: Int, val g: Int, val b: Int)

    private fun appendLightThemeColorOverrides(css: String): String {
        val overrides = StringBuilder()
        for (block in cssBlockRe.findAll(css)) {
            val selector = block.groupValues[1].trim()
            if (selector.isEmpty() || selector.startsWith("@")) continue
            val body = block.groupValues[2]
            for (c in colorValueRe.findAll(body)) {
                val raw = c.groupValues[1].trim().substringBefore("!important").trim()
                val rgb = parseColor(raw) ?: continue
                val (h, s, l) = rgbToHsl(rgb)
                if (l >= 0.35 || s <= 0.06) continue
                val lifted = hslToRgb(h, s, 0.40)
                val parts = selector.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                val prefixed = parts.joinToString(", ") {
                    ":root:not([style*=\"--RS__backgroundColor: #000000\"]) $it"
                }
                overrides.append(prefixed).append("{color:").append(toHex(lifted)).append("!important;}\n")
            }
        }
        if (overrides.isEmpty()) return css
        return css + "\n" + overrides
    }

    /** 剥离 Kindle 专属 CSS：@font-face（字体文件不存在）、duokan-* 私有声明、整页高浮动。 */
    private fun cleanCss(css: String): String {
        var c = fontFaceBlockRe.replace(css, "")
        c = duokanDeclRe.replace(c, "")
        c = height400Re.replace(c, "height:auto")
        return c
    }

    private fun parseColor(raw: String): Rgb? {
        hexColorRe.matchEntire(raw)?.let { m ->
            var v = m.groupValues[1]
            if (v.length == 3) v = "" + v[0] + v[0] + v[1] + v[1] + v[2] + v[2]
            return Rgb(v.substring(0, 2).toInt(16), v.substring(2, 4).toInt(16), v.substring(4, 6).toInt(16))
        }
        rgbColorRe.matchEntire(raw)?.let { m ->
            return Rgb(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        return null
    }

    private fun rgbToHsl(c: Rgb): Triple<Double, Double, Double> {
        val r = c.r / 255.0
        val g = c.g / 255.0
        val b = c.b / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2.0
        if (max == min) return Triple(0.0, 0.0, l)
        val d = max - min
        val s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6.0 else 0.0)) / 6.0
            g -> ((b - r) / d + 2.0) / 6.0
            else -> ((r - g) / d + 4.0) / 6.0
        }
        return Triple(h, s, l)
    }

    private fun hslToRgb(h0: Double, s: Double, l: Double): Rgb {
        fun hue2rgb(p: Double, q: Double, t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / 6.0 -> p + (q - p) * 6 * t
                t < 1.0 / 2.0 -> q
                t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6
                else -> p
            }
        }
        if (s == 0.0) {
            val v = (l * 255 + 0.5).toInt().coerceIn(0, 255)
            return Rgb(v, v, v)
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        return Rgb(
            (hue2rgb(p, q, h0 + 1.0 / 3.0) * 255 + 0.5).toInt().coerceIn(0, 255),
            (hue2rgb(p, q, h0) * 255 + 0.5).toInt().coerceIn(0, 255),
            (hue2rgb(p, q, h0 - 1.0 / 3.0) * 255 + 0.5).toInt().coerceIn(0, 255),
        )
    }

    private fun toHex(c: Rgb): String {
        val hex = "0123456789ABCDEF"
        fun b(v: Int): String = "" + hex[(v.coerceIn(0, 255) shr 4)] + hex[(v.coerceIn(0, 255) and 15)]
        return "#" + b(c.r) + b(c.g) + b(c.b)
    }

    // ── 脚注精确锚点：把 kindle:pos:fid 解析到最近的 id/name，而非整章 ──
    private data class Anchor(val offset: Int, val name: String)

    private val idNameRe = Regex("""(?i)\b(?:id|name)\s*=\s*["']([^"']*)["']""")

    /** 从原始 part 字节提取所有 id/name 锚点及其字节偏移（ISO-8859-1 使字节偏移==字符偏移）。 */
    private fun findAnchors(part: ByteArray): List<Anchor> {
        val latin = String(part, Charsets.ISO_8859_1)
        return idNameRe.findAll(latin).map { m ->
            val raw = m.groupValues[1]
            val name = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            Anchor(m.groups[1]!!.range.first, name)
        }.toList()
    }

    /** 把重建全文字节偏移 pos 解析为 (part 序号, 该处最近的后续锚点名)；找不到返回 null。 */
    private fun resolveAnchor(
        pos: Int,
        partStarts: IntArray,
        anchorsPerPart: List<List<Anchor>>,
    ): Pair<Int, String>? {
        if (pos < 0 || pos >= partStarts.last()) return null
        var lo = 0
        var hi = anchorsPerPart.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (partStarts[mid] <= pos) lo = mid else hi = mid - 1
        }
        val local = pos - partStarts[lo]
        val anchor = anchorsPerPart[lo].firstOrNull { it.offset >= local } ?: return null
        return lo to anchor.name
    }

    private fun processPart(
        part: ByteArray,
        cssText: ByteArray,
        imageByResourceIndex: Map<Int, MobiImage>,
        fragtbl: List<Fragment>,
        partStarts: IntArray,
        anchorsPerPart: List<List<Anchor>>,
    ): String {
        var s = part.toString(Charsets.UTF_8)
        s = linkRe.replace(s, "")

        val css = cssText.toString(Charsets.UTF_8)
        val adjustedCss = appendLightThemeColorOverrides(cleanCss(css))
        val style = "<style type=\"text/css\">\n$adjustedCss\nimg{max-width:100%;height:auto;}\n</style>"
        s = s.replaceFirst("</head>", style + "</head>")

        s = embedRe.replace(s) { m ->
            val idx = fromBase32(m.groupValues[1]) - 1
            val img = imageByResourceIndex[idx]
            if (img != null) "images/${img.fileName}" else m.value
        }
        s = posfidRe.replace(s) { m ->
            val fid = fromBase32(m.groupValues[1])
            val off = fromBase32(m.groupValues[2])
            if (fid in fragtbl.indices) {
                val pos = fragtbl[fid].insertpos + off
                val r = resolveAnchor(pos, partStarts, anchorsPerPart)
                if (r != null) "c${r.first}.xhtml#${r.second}" else "c${fragtbl[fid].filenum}.xhtml"
            } else m.value
        }
        // Kindle 私有字体（FONT 格式）无法嵌入标准 EPUB：剥离 @font-face 的 url 及其前导逗号
        s = fontUrlCommaRe.replace(s, "")
        s = fontUrlRe.replace(s, "")
        s = aidRe.replace(s, "")
        return sanitizeXml(s)
    }

    private fun extractTitle(html: String): String? {
        val h1 = Jsoup.parse(html).selectFirst("h1") ?: return null
        val t = h1.text().trim()
        return t.takeIf { it.isNotEmpty() && it.length <= 100 }
    }

    /** 丢弃 XML 1.0 不允许的控制字符，避免写出的 XHTML 非法。 */
    private fun sanitizeXml(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            val cp = c.code
            if (cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D) continue
            sb.append(c)
        }
        return sb.toString()
    }
}
