package com.ebookreader.data.importer.mobi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Palm Database (PDB) 容器：.mobi/.azw/.azw3 都是 PDB 文件。
 *
 * 结构：78 字节固定头 + `recordCount` 条 8 字节记录信息（4 字节偏移 + 4 字节属性）。
 * 各记录实际字节区间由相邻偏移相减得出。
 */
class PdbHeader private constructor(
    val name: String,
    val type: String,
    val creator: String,
    val recordOffsets: IntArray,
    val fileLength: Int,
) {
    val recordCount: Int get() = recordOffsets.size

    /** 第 [index] 条记录在整文件里的字节区间 [start, end)（end 为开区间）。 */
    fun recordRange(index: Int): IntRange {
        val start = recordOffsets[index]
        val end = if (index + 1 < recordCount) recordOffsets[index + 1] else fileLength
        return start until end
    }

    /** 从整文件字节里截取第 [index] 条记录（完整字节，含最后一个字节）。 */
    fun loadRecord(bytes: ByteArray, index: Int): ByteArray {
        val start = recordOffsets[index]
        val end = if (index + 1 < recordCount) recordOffsets[index + 1] else fileLength
        if (start < 0 || end > bytes.size || start >= end) return ByteArray(0)
        return bytes.copyOfRange(start, end)
    }

    companion object {
        fun parse(bytes: ByteArray): PdbHeader {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val name = buf.asciiAt(0, 32)
            val type = buf.asciiAt(60, 4)
            val creator = buf.asciiAt(64, 4)
            val recordCount = buf.u16At(76)
            val offsets = IntArray(recordCount)
            for (i in 0 until recordCount) {
                offsets[i] = buf.getInt(78 + i * 8)
            }
            return PdbHeader(name, type, creator, offsets, bytes.size)
        }
    }
}
