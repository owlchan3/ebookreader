package com.ebookreader.data.importer.mobi

import java.io.ByteArrayOutputStream

/**
 * MOBI HUFF/CDIC 解压（compression == 0x4448）。
 *
 * 与 KindleUnpack mobi_uncompress.py 一致：HUFF 记录存编码表（dict1 首字节快查表 + mincode/maxcode），
 * CDIC 记录存短语字典；每条正文记录是一段独立 Huffman 编码，共用同一套表 + 字典逐条解压。
 */
internal class HuffCdic(pdb: PdbHeader, bytes: ByteArray, header: MobiHeader) {

    private class Dict1(val codeLen: Int, val terminal: Boolean, val maxCode: Long)
    private class Phrase(val data: ByteArray, val decompressed: Boolean)

    private val dict1 = ArrayList<Dict1>(256)
    private val minCode = LongArray(33)
    private val maxCode = LongArray(33)
    private val dictionary = ArrayList<Phrase?>()

    init {
        loadHuff(pdb, bytes, header)
        loadCdicRecords(pdb, bytes, header)
    }

    /** 解析 HUFF 记录：dict1 首字节快查表 + mincode/maxcode 表。 */
    private fun loadHuff(pdb: PdbHeader, bytes: ByteArray, header: MobiHeader) {
        if (header.huffRecordOffset < 1 || header.huffRecordOffset >= pdb.recordCount) {
            throw MobiException("该文件缺少 Huffman 数据，无法解压")
        }
        val huff = pdb.loadRecord(bytes, header.huffRecordOffset)
        if (huff.size < 16 || !magic(huff, "HUFF", 0x18)) throw MobiException("Huffman 记录头无效")

        val off1 = u32(huff, 8)
        val off2 = u32(huff, 12)
        for (i in 0 until 256) {
            val v = u32(huff, off1 + i * 4)
            val codeLen = v and 0x1F
            if (codeLen == 0) throw MobiException("Huffman 码长非法")
            val terminal = (v and 0x80) != 0
            val maxCode = (((v ushr 8) + 1L) shl (32 - codeLen)) - 1L
            dict1.add(Dict1(codeLen, terminal, maxCode))
        }
        for (codeLen in 1..32) {
            val minRaw = u32(huff, off2 + (codeLen - 1) * 8).toLong() and 0xFFFFFFFFL
            val maxRaw = u32(huff, off2 + (codeLen - 1) * 8 + 4).toLong() and 0xFFFFFFFFL
            minCode[codeLen] = minRaw shl (32 - codeLen)
            maxCode[codeLen] = ((maxRaw + 1L) shl (32 - codeLen)) - 1L
        }
    }

    /** 逐条读取 CDIC 记录，累积短语字典。 */
    private fun loadCdicRecords(pdb: PdbHeader, bytes: ByteArray, header: MobiHeader) {
        val cdicCount = (header.huffRecordCount - 1).coerceAtLeast(0)
        for (i in 1..cdicCount) {
            val idx = header.huffRecordOffset + i
            if (idx >= pdb.recordCount) break
            loadCdic(pdb.loadRecord(bytes, idx))
        }
    }

    private fun loadCdic(cdic: ByteArray) {
        if (cdic.size < 16 || !magic(cdic, "CDIC", 0x10)) throw MobiException("CDIC 记录头无效")
        val phrases = u32(cdic, 8)
        val bits = u32(cdic, 12)
        val capacity = if (bits in 0..62) 1L shl bits else Long.MAX_VALUE
        val n = minOf(capacity, (phrases - dictionary.size).toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        for (k in 0 until n) {
            val off = u16(cdic, 16 + k * 2)
            val blen = u16(cdic, 16 + off)
            val len = blen and 0x7FFF
            val decompressed = (blen and 0x8000) != 0
            val from = 18 + off
            if (from < 0 || from + len > cdic.size) throw MobiException("CDIC 短语越界")
            dictionary.add(Phrase(cdic.copyOfRange(from, from + len), decompressed))
        }
    }

    /** 解压单条正文记录。 */
    fun unpack(data: ByteArray): ByteArray {
        var bitsleft = data.size * 8
        val buf = data.copyOf(data.size + 8)
        val out = ByteArrayOutputStream(data.size.coerceAtLeast(256))
        var pos = 0
        var x = u64(buf, pos)
        var n = 32
        while (true) {
            if (n <= 0) {
                pos += 4
                if (pos + 8 > buf.size) break
                x = u64(buf, pos)
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL
            val e = dict1[(code ushr 24).toInt()]
            var codeLen = e.codeLen
            var mx = e.maxCode
            if (!e.terminal) {
                while (codeLen < 32 && code < minCode[codeLen]) codeLen++
                mx = maxCode[codeLen]
            }
            n -= codeLen
            bitsleft -= codeLen
            if (bitsleft < 0) break
            val r = ((mx - code) ushr (32 - codeLen)).toInt()
            if (r < 0 || r >= dictionary.size) throw MobiException("Huffman 码表索引越界")
            val phrase = dictionary[r] ?: throw MobiException("Huffman 短语递归异常")
            if (!phrase.decompressed) {
                // 短语本身仍是 Huffman 编码：递归解压并缓存，避免重复
                dictionary[r] = null
                val dec = unpack(phrase.data)
                dictionary[r] = Phrase(dec, true)
                out.write(dec)
            } else {
                out.write(phrase.data)
            }
        }
        return out.toByteArray()
    }

    private fun magic(b: ByteArray, sig: String, ver: Int): Boolean {
        if (b.size < 8) return false
        for (i in 0 until 4) if (b[i] != sig[i].code.toByte()) return false
        return u32(b, 4) == ver
    }

    private fun u16(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) throw MobiException("HUFF/CDIC 数据越界")
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun u32(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) throw MobiException("HUFF/CDIC 数据越界")
        return ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    }

    private fun u64(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 8 > b.size) throw MobiException("HUFF/CDIC 数据越界")
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
