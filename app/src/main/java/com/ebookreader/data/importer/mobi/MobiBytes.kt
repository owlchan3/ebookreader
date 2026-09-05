package com.ebookreader.data.importer.mobi

import java.nio.ByteBuffer

/**
 * 无符号大端读取辅助（MOBI/PDB 头均为大端）。
 * 用绝对 getter（getShort/getInt(index)）不移动 position，便于按固定偏移读字段。
 */
internal fun ByteBuffer.u16At(offset: Int): Int = getShort(offset).toInt() and 0xFFFF
internal fun ByteBuffer.i32At(offset: Int): Int = getInt(offset)
internal fun ByteBuffer.u32At(offset: Int): Long = getInt(offset).toLong() and 0xFFFFFFFFL

/** 读固定偏移处的 ASCII 字符串（去尾部 NUL 与空白）。 */
internal fun ByteBuffer.asciiAt(offset: Int, len: Int): String {
    val b = ByteArray(len)
    get(offset, b)
    val nul = 0.toChar()
    return b.toString(Charsets.US_ASCII).trimEnd(nul).trim()
}
