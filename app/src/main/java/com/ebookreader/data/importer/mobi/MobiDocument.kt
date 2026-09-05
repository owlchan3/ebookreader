package com.ebookreader.data.importer.mobi

import java.io.File

/** MOBI/AZW3 转换过程中的专用异常，消息会直接展示给用户。 */
class MobiException(message: String) : Exception(message)

/** 一张内联/封面图片（来自图片资源记录）。 */
data class MobiImage(
    val recordIndex: Int,     // PDB 记录绝对下标
    val resourceIndex: Int,   // 相对 firstResource 的 0-based 序号（== recindex - 1）
    val fileName: String,     // EPUB 内文件名，如 image00012.jpg
    val bytes: ByteArray,
    val mime: String,
)

/**
 * 解析后的 MOBI/AZW3 文档：mobi7 原始 HTML + 元数据 + 图片资源（含封面）。
 */
data class MobiDocument(
    val chapters: List<MobiChapter>, // 切分后的章节（标题 + 正文 HTML）
    val title: String,
    val author: String?,
    val description: String?,
    val cover: MobiImage?,          // 封面图片（EXTH 201）
    val images: List<MobiImage>,    // 全部内联图片（按 record index 升序）
    val isKf8: Boolean,             // 是否为组合 mobi7+mobi8（后续阶段用）
)

/** 每条记录尾「trailer」数据项的大小：读末尾 4 字节的变长 7bit 整数。 */
private fun trailingDataEntrySize(data: ByteArray): Int {
    var num = 0
    val start = (data.size - 4).coerceAtLeast(0)
    for (i in start until data.size) {
        val v = data[i].toInt() and 0xFF
        if (v and 0x80 != 0) num = 0
        num = (num shl 7) or (v and 0x7F)
    }
    return num
}

/** 去掉每条正文记录末尾的额外数据（trailers + multibyte）。 */
private fun trimTrailingData(data: ByteArray, header: MobiHeader): ByteArray {
    var out = data
    repeat(header.trailers) {
        val num = trailingDataEntrySize(out)
        if (num in 1..out.size) out = out.copyOf(out.size - num)
    }
    if (header.multibyte && out.isNotEmpty()) {
        val num = (out[out.size - 1].toInt() and 3) + 1
        if (num in 1..out.size) out = out.copyOf(out.size - num)
    }
    return out
}

/** 识别图片资源类型（JPEG/GIF/PNG），非图片返回 null。 */
private fun detectImageType(data: ByteArray): Pair<String, String>? {
    if (data.size < 4) return null
    return when {
        data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg" to "jpg"
        data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() ->
            "image/gif" to "gif"
        data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() &&
            data[3] == 0x47.toByte() -> "image/png" to "png"
        else -> null
    }
}

/**
 * 解析 .mobi / .azw / .azw3 文件，返回 mobi7 正文、元数据与图片资源（含封面）。
 * 阶段范围：不支持纯 KF8（version>=8）、DRM。
 */
fun parseMobi(file: File): MobiDocument {
    val bytes = file.readBytes()
    val pdb = PdbHeader.parse(bytes)
    val record0 = pdb.loadRecord(bytes, 0)
    if (record0.isEmpty()) throw MobiException("文件结构异常，无法解析")

    val fallbackTitle = pdb.name.ifBlank { file.nameWithoutExtension }
    val header = MobiHeader.parse(record0, fallbackTitle)

    if (header.cryptoType == 2) {
        throw MobiException("该文件受 DRM 保护，无法转换")
    }

    val exth = if (header.hasExth) {
        ExthMetadata.parse(record0, header.exthOffset, header.exthLength, header.codepage)
    } else null

    val isPureKf8 = header.version >= 8
    val isKf8 = isPureKf8 || exth?.kf8BoundaryRecord != null
    if (isPureKf8) {
        return parseKf8(pdb, bytes, header, exth, fallbackTitle)
    }

    // 解压正文记录（mobi7）
    val raw = decompressTextRecords(pdb, bytes, header)

    // 提取图片资源（含封面）
    val images = ArrayList<MobiImage>()
    var cover: MobiImage? = null
    val coverOffset = exth?.coverRecordIndex?.takeIf { it >= 0 }
    if (header.firstResource in 1 until pdb.recordCount) {
        for (i in header.firstResource until pdb.recordCount) {
            val rec = pdb.loadRecord(bytes, i)
            val info = detectImageType(rec) ?: continue
            val img = MobiImage(
                recordIndex = i,
                resourceIndex = i - header.firstResource,
                fileName = "image${i.toString().padStart(5, '0')}.${info.second}",
                bytes = rec,
                mime = info.first,
            )
            images.add(img)
            if (coverOffset != null && img.resourceIndex == coverOffset) cover = img
        }
    }

    // 按内联目录（filepos 字节偏移）/ 标题切分章节
    val chapters = splitMobiBytes(raw, header.codepage, images)

    val title = (exth?.updatedTitle?.takeIf { it.isNotBlank() } ?: header.title).ifBlank { fallbackTitle }
    return MobiDocument(chapters, title, exth?.author, exth?.description, cover, images, isKf8)
}

/** 解压并拼接正文记录（mobi7 / KF8 共用）。 */
private fun decompressTextRecords(pdb: PdbHeader, bytes: ByteArray, header: MobiHeader): ByteArray {
    // HUFF/CDIC：每条正文记录是一段独立 Huffman 编码，共用同一套 HUFF 码表 + CDIC 短语字典
    val huffcdic = if (header.compression == 0x4448) HuffCdic(pdb, bytes, header) else null

    val decompressed = ArrayList<ByteArray>(header.textRecordCount)
    for (i in 1..header.textRecordCount) {
        if (i >= pdb.recordCount) break
        val rec = pdb.loadRecord(bytes, i)
        val trimmed = trimTrailingData(rec, header)
        val out = when (header.compression) {
            1 -> trimmed
            2 -> PalmDocLz77.decompress(trimmed)
            0x4448 -> huffcdic!!.unpack(trimmed)
            else -> throw MobiException("不支持的压缩类型：${header.compression}")
        }
        decompressed.add(out)
    }
    val total = decompressed.sumOf { it.size }
    val raw = ByteArray(total)
    var off = 0
    for (d in decompressed) {
        System.arraycopy(d, 0, raw, off, d.size)
        off += d.size
    }
    return raw
}

/** 解析纯 KF8（AZW3）：碎片重建 + 图片 + 封面，输出完整 XHTML 章节。 */
private fun parseKf8(
    pdb: PdbHeader,
    bytes: ByteArray,
    header: MobiHeader,
    exth: ExthMetadata?,
    fallbackTitle: String,
): MobiDocument {
    if (header.fdst == 0 || header.skelidx == 0 || header.fragidx == 0) {
        throw MobiException("该 AZW3 缺少 KF8 结构信息，无法解析")
    }
    val rawMl = decompressTextRecords(pdb, bytes, header)

    // 提取图片（资源范围 [firstResource, fdst)），跳过字体等非图片记录
    val images = ArrayList<MobiImage>()
    var cover: MobiImage? = null
    val coverOffset = exth?.coverRecordIndex?.takeIf { it >= 0 }
    if (header.firstResource in 1 until header.fdst && header.fdst <= pdb.recordCount) {
        for (i in header.firstResource until header.fdst) {
            val rec = pdb.loadRecord(bytes, i)
            val info = detectImageType(rec) ?: continue
            val img = MobiImage(
                recordIndex = i,
                resourceIndex = i - header.firstResource,
                fileName = "image${i.toString().padStart(5, '0')}.${info.second}",
                bytes = rec,
                mime = info.first,
            )
            images.add(img)
            if (coverOffset != null && img.resourceIndex == coverOffset) cover = img
        }
    }

    val chapters = Kf8Processor.parse(pdb, bytes, rawMl, header, images)
    if (chapters.isEmpty()) throw MobiException("无法从该文件重建章节内容")

    val title = (exth?.updatedTitle?.takeIf { it.isNotBlank() } ?: header.title).ifBlank { fallbackTitle }
    return MobiDocument(chapters, title, exth?.author, exth?.description, cover, images, isKf8 = true)
}
