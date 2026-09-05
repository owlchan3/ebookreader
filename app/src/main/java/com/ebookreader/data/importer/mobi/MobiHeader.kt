package com.ebookreader.data.importer.mobi

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** 按 MOBI 头 codepage 字段映射字符集；未知返回 null（由 [decodeMobiText] 兜底）。 */
internal fun charsetForCodepage(codepage: Int): Charset? = when (codepage) {
    1252 -> Charset.forName("windows-1252")
    65001 -> Charsets.UTF_8
    936 -> Charset.forName("GBK")
    54936 -> Charset.forName("GB18030")
    950 -> Charset.forName("Big5")
    949 -> Charset.forName("EUC-KR")
    932 -> Charset.forName("Shift_JIS")
    1200 -> Charsets.UTF_16LE
    1201 -> Charsets.UTF_16BE
    else -> null
}

/** 是否为 UTF-8 续字节（0x80–0xBF）。 */
private fun isUtf8Continuation(b: Byte): Boolean = (b.toInt() and 0xC0) == 0x80

/**
 * 手动 UTF-8 解码：非法字节序列按 CP1252 单字节兜底，而不是替换成 U+FFFD。
 * 用于恢复「整体 UTF-8、但夹带个别 CP1252 高位字节（弯引号等）」的散落乱码。
 */
private fun decodeUtf8WithCp1252Fallback(bytes: ByteArray): String {
    val cp1252 = Charset.forName("windows-1252")
    val sb = StringBuilder(bytes.size)
    var i = 0
    fun single() { sb.append(String(bytes, i, 1, cp1252)); i += 1 }
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        when {
            b0 < 0x80 -> { sb.append(b0.toChar()); i += 1 }
            b0 < 0xC2 -> single()
            b0 < 0xE0 -> {
                if (i + 1 < bytes.size && isUtf8Continuation(bytes[i + 1])) {
                    val cp = ((b0 and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)
                    sb.append(cp.toChar()); i += 2
                } else single()
            }
            b0 < 0xF0 -> {
                if (i + 2 < bytes.size && isUtf8Continuation(bytes[i + 1]) && isUtf8Continuation(bytes[i + 2])) {
                    val cp = ((b0 and 0x0F) shl 12) or ((bytes[i + 1].toInt() and 0x3F) shl 6) or
                        (bytes[i + 2].toInt() and 0x3F)
                    sb.append(cp.toChar()); i += 3
                } else single()
            }
            b0 < 0xF5 -> {
                if (i + 3 < bytes.size && isUtf8Continuation(bytes[i + 1]) && isUtf8Continuation(bytes[i + 2]) &&
                    isUtf8Continuation(bytes[i + 3])
                ) {
                    val cp = ((b0 and 0x07) shl 18) or ((bytes[i + 1].toInt() and 0x3F) shl 12) or
                        ((bytes[i + 2].toInt() and 0x3F) shl 6) or (bytes[i + 3].toInt() and 0x3F)
                    sb.append(String(Character.toChars(cp))); i += 4
                } else single()
            }
            else -> single()
        }
    }
    return sb.toString()
}

/**
 * 解码 MOBI 正文/元数据字节流：优先 UTF-8（绝大多数中文 MOBI），出现替换字符时依次回退到
 * codepage 指定字符集、CP1252 兜底，最大限度消除「内含问号的菱形」。
 */
internal fun decodeMobiText(bytes: ByteArray, codepage: Int): String {
    val utf8 = String(bytes, Charsets.UTF_8)
    if (!utf8.contains('�')) return utf8
    val alt = charsetForCodepage(codepage)
    if (alt != null && alt != Charsets.UTF_8) {
        val s = String(bytes, alt)
        if (!s.contains('�')) return s
    }
    return decodeUtf8WithCp1252Fallback(bytes)
}

/**
 * MOBI / PalmDOC 头（record 0 的前若干字节）。
 * 字段偏移均为相对 record 0 起始（含 16 字节 PalmDOC 头），与 KindleUnpack mobi_header.py 一致。
 */
class MobiHeader(
    val compression: Int,       // 1=无 2=PalmDOC LZ77 0x4448=Huffman/CDIC
    val textRecordCount: Int,   // 正文记录数（PalmDOC 0x08）
    val cryptoType: Int,        // 0=无 1=旧加密 2=Mobipocket(DRM)
    val codec: Charset,         // codepage 对应的「最佳猜测」字符集
    val codepage: Int,          // 原始 codepage 字段（供 decodeMobiText 回退）
    val version: Int,
    val title: String,
    val hasExth: Boolean,
    val exthOffset: Int,        // EXTH 在 record 0 内的绝对偏移（== headerLength + 16）
    val exthLength: Int,
    val isMobi: Boolean,        // 含 "MOBI" 魔数；false = 纯 PalmDOC(TEXtREAd)
    val multibyte: Boolean,     // 每条记录尾带变长「多字节」数据（traildata_flags bit0）
    val trailers: Int,          // 每条记录尾带多少个变长「trailer」数据项
    val firstResource: Int,     // 首个资源（图片/字体）记录号
    val fdst: Int,              // KF8：FDST 流表记录号（资源上界）
    val fdstCount: Int,         // KF8：流数量
    val fcis: Int,              // KF8：FCIS 记录号
    val flis: Int,              // KF8：FLIS 记录号
    val srcs: Int,              // KF8：SRCS 记录号
    val ncx: Int,               // KF8：NCX 记录号
    val fragidx: Int,           // KF8：碎片表索引记录号
    val skelidx: Int,           // KF8：骨架表索引记录号
    val guideidx: Int,          // KF8：导航索引记录号
    val huffRecordOffset: Int,  // Huffman：HUFF 记录号（0x70）
    val huffRecordCount: Int,   // Huffman：HUFF+CDIC 记录总数（0x74）
) {
    companion object {
        fun parse(record0: ByteArray, fallbackTitle: String): MobiHeader {
            val buf = ByteBuffer.wrap(record0).order(ByteOrder.BIG_ENDIAN)
            val compression = buf.u16At(0x00)
            val textRecordCount = buf.u16At(0x08)
            val cryptoType = buf.u16At(0x0C)
            val isMobi = record0.size > 20 &&
                record0[16] == 'M'.code.toByte() && record0[17] == 'O'.code.toByte() &&
                record0[18] == 'B'.code.toByte() && record0[19] == 'I'.code.toByte()

            if (!isMobi) {
                // 纯 PalmDOC（TEXtREAd）：无 MOBI 头，只有 PalmDOC 头
                return MobiHeader(
                    compression, textRecordCount, cryptoType, Charsets.UTF_8,
                    codepage = 65001, version = 0, title = fallbackTitle,
                    hasExth = false, exthOffset = 0, exthLength = 0,
                    isMobi = false, multibyte = false, trailers = 0,
                    firstResource = textRecordCount + 1,
                    fdst = 0, fdstCount = 0, fcis = 0, flis = 0, srcs = 0,
                    ncx = 0, fragidx = 0, skelidx = 0, guideidx = 0,
                    huffRecordOffset = 0, huffRecordCount = 0,
                )
            }

            val headerLength = buf.i32At(0x14)
            val codepage = buf.i32At(0x1C)
            val version = buf.i32At(0x24)
            val codec = charsetForCodepage(codepage) ?: Charsets.UTF_8
            val huffRecordOffset = if (record0.size > 0x70 + 4) buf.u32At(0x70).toInt() else 0
            val huffRecordCount = if (record0.size > 0x74 + 4) buf.u32At(0x74).toInt() else 0

            // 首个资源记录号；0x6C 为 0xFFFFFFFF 时取「正文记录之后」
            var firstResource = textRecordCount + 1
            if (record0.size > 0x6C + 4) {
                val resc = buf.u32At(0x6C)
                if (resc != 0xFFFFFFFFL) firstResource = resc.toInt()
            }

            // KF8 索引指针；0xFFFFFFFF 表示不存在，记 0
            fun idxAt(offset: Int): Int {
                if (record0.size <= offset + 4) return 0
                val v = buf.u32At(offset)
                return if (v == 0xFFFFFFFFL) 0 else v.toInt()
            }
            val fdst = idxAt(0xC0)
            val fdstCount = idxAt(0xC4)
            val fcis = idxAt(0xC8)
            val flis = idxAt(0xD0)
            val srcs = idxAt(0xE0)
            val ncx = idxAt(0xF4)
            val fragidx = idxAt(0xF8)
            val skelidx = idxAt(0xFC)
            val guideidx = idxAt(0x104)

            // 标题（MOBI 头内的 title offset/length）
            val titleOffset = buf.i32At(0x54)
            val titleLength = buf.i32At(0x58)
            var title = fallbackTitle
            if (titleOffset in 1 until record0.size && titleLength > 0 &&
                titleOffset + titleLength <= record0.size
            ) {
                title = decodeMobiText(record0.copyOfRange(titleOffset, titleOffset + titleLength), codepage)
                    .trim().ifBlank { fallbackTitle }
            }

            // EXTH（紧跟 MOBI 头之后）
            val exthFlags = buf.i32At(0x80)
            val hasExth = (exthFlags and 0x40) != 0
            val exthOffset = headerLength + 16
            var exthLength = 0
            if (hasExth && exthOffset + 4 <= record0.size) {
                exthLength = buf.i32At(exthOffset + 4)
                exthLength = ((exthLength + 3) shr 2) shl 2 // 对齐到 4 字节边界
            }

            // 每条正文记录尾的额外数据（traildata_flags）；版本判断用 min_version(0x68)，与 KindleUnpack 一致
            var multibyte = false
            var trailers = 0
            if (headerLength >= 0xE4 && record0.size > 0x68 + 4) {
                val minVersion = buf.i32At(0x68)
                if (minVersion >= 5) {
                    val flags = buf.u16At(0xF2)
                    multibyte = (flags and 1) != 0
                    var f = flags
                    while (f > 1) {
                        if ((f and 2) != 0) trailers++
                        f = f shr 1
                    }
                }
            }

            return MobiHeader(
                compression, textRecordCount, cryptoType, codec, codepage, version, title,
                hasExth, exthOffset, exthLength, isMobi = true, multibyte, trailers, firstResource,
                fdst, fdstCount, fcis, flis, srcs, ncx, fragidx, skelidx, guideidx,
                huffRecordOffset = huffRecordOffset, huffRecordCount = huffRecordCount,
            )
        }
    }
}
