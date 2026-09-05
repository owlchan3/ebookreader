package com.ebookreader.data.importer.mobi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EXTH 元数据（record 0 内、紧跟 MOBI 头之后）。
 * 结构："EXTH"(4) + length(4) + num_items(4) + 若干 TLV 项（id u32 + size u32 + 内容）。
 */
class ExthMetadata(
    val author: String?,
    val description: String?,
    val updatedTitle: String?,
    val coverRecordIndex: Int?,    // EXTH 201：封面记录号（相对 firstResource 的偏移）
    val kf8BoundaryRecord: Int?,   // EXTH 121：KF8 边界记录号（组合 mobi7+mobi8）
) {
    companion object {
        fun parse(record0: ByteArray, offset: Int, length: Int, codepage: Int): ExthMetadata? {
            if (offset < 0 || length < 12 || offset + length > record0.size) return null
            if (record0[offset] != 'E'.code.toByte() || record0[offset + 1] != 'X'.code.toByte() ||
                record0[offset + 2] != 'T'.code.toByte() || record0[offset + 3] != 'H'.code.toByte()
            ) return null

            val buf = ByteBuffer.wrap(record0).order(ByteOrder.BIG_ENDIAN)
            val numItems = buf.i32At(offset + 8)
            var pos = offset + 12
            val end = offset + length
            var author: String? = null
            var description: String? = null
            var updatedTitle: String? = null
            var cover: Int? = null
            var boundary: Int? = null

            var count = 0
            while (pos + 8 <= end && count < numItems) {
                val id = buf.i32At(pos)
                val size = buf.i32At(pos + 4)
                if (size < 8 || pos + size > end) break
                val contentStart = pos + 8
                val contentLen = size - 8
                when (id) {
                    100 -> author = decodeMobiText(record0.copyOfRange(contentStart, contentStart + contentLen), codepage).trim()
                    103 -> description = decodeMobiText(record0.copyOfRange(contentStart, contentStart + contentLen), codepage).trim()
                    503 -> updatedTitle = decodeMobiText(record0.copyOfRange(contentStart, contentStart + contentLen), codepage).trim()
                    201 -> if (contentLen >= 4) cover = buf.i32At(contentStart)
                    121 -> if (contentLen >= 4) boundary = buf.i32At(contentStart)
                }
                pos += size
                count++
            }
            return ExthMetadata(author, description, updatedTitle, cover, boundary)
        }
    }
}
