package com.ebookreader.data.importer.mobi

/**
 * PalmDOC LZ77 解压（compression = 2）。
 * 精确移植 KindleUnpack 的 `PalmdocReader.unpack` 算法。
 */
object PalmDocLz77 {

    fun decompress(input: ByteArray): ByteArray {
        var buf = ByteArray(maxOf(4096, input.size * 2))
        var size = 0

        fun ensure(extra: Int) {
            if (size + extra > buf.size) {
                val nb = ByteArray(maxOf(buf.size * 2, size + extra))
                System.arraycopy(buf, 0, nb, 0, size)
                buf = nb
            }
        }
        fun write(b: Int) { ensure(1); buf[size++] = b.toByte() }
        fun writeBytes(src: ByteArray, off: Int, len: Int) {
            ensure(len); System.arraycopy(src, off, buf, size, len); size += len
        }

        var p = 0
        val n = input.size
        while (p < n) {
            var c = input[p].toInt() and 0xFF
            p++
            if (c in 1..8) {
                val len = (p + c).coerceAtMost(n) - p
                writeBytes(input, p, len)
                p += len
            } else if (c < 128) {
                // 0x00 或 0x09..0x7F：单字面量
                write(c)
            } else if (c >= 192) {
                // 空格 + 字面量字节（c ^ 0x80）
                write(' '.code)
                write(c xor 128)
            } else {
                // 0x80..0xBF：回引（长度-距离对）
                if (p < n) {
                    c = (c shl 8) or (input[p].toInt() and 0xFF)
                    p++
                    val m = (c shr 3) and 0x07FF   // 距离（11 位）
                    val len = (c and 7) + 3        // 长度 3..10
                    if (m > len) {
                        // 非重叠：整体复制 len 字节（源区间在已写区间内，不与目标重叠）
                        writeBytes(buf, size - m, len)
                    } else {
                        // 重叠：逐字节复制，边写边从增长的输出里取
                        val base = size
                        for (k in 0 until len) {
                            val idx = when (m) {
                                0 -> 0
                                1 -> base - 1
                                else -> base - m + k
                            }
                            if (idx >= 0 && idx < size) write(buf[idx].toInt() and 0xFF)
                        }
                    }
                }
            }
        }
        return buf.copyOf(size)
    }
}
