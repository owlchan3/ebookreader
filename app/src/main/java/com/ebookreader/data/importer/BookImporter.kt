package com.ebookreader.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookImporter(private val context: Context) {

    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val pdfDocumentFactory = PdfiumDocumentFactory(context)
    private val publicationParser =
        DefaultPublicationParser(context, httpClient, assetRetriever, pdfDocumentFactory)
    private val publicationOpener = PublicationOpener(publicationParser)
    private val prefs = context.getSharedPreferences("importer_prefs", Context.MODE_PRIVATE)

    data class ImportedBook(val book: com.ebookreader.domain.model.Book, val coverPath: String?)

    suspend fun importFromUri(uri: Uri): Result<ImportedBook> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri) ?: "unknown.epub"
            val internalDir = File(context.filesDir, "books")
            internalDir.mkdirs()
            val destFile = File(internalDir, "${System.currentTimeMillis()}_$fileName")
            val extension = destFile.extension.lowercase()

            // Copy file to internal storage
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(Exception("无法读取文件"))

            // Track original format before potential TXT→EPUB conversion
            val originalExtension = extension

            // Handle DOCX: extract text → save as .txt → import through TXT pipeline
            val bookFile = if (extension == "docx") {
                val epubFile = convertDocxToTxt(destFile)
                // Delete the original .docx copy, keep only the generated files
                destFile.delete()
                epubFile
            } else if (extension == "txt") {
                // 保留源 .txt 文件：刷新目录 / 合并章节（reconvertTxt）需要它作为章节再识别的依据。
                convertTxtToEpub(destFile)
            } else if (extension == "epub") {
                val fixed = sanitizeEpubXhtml(destFile)
                // 生成修复副本后删除原 EPUB，只保留修复版，避免双份占用
                if (fixed != destFile) {
                    destFile.delete()
                    // sanitizeEpubXhtml 已含 stripTrailingAfterHtml，标记避免打开时重复扫描
                    markTrailingStripped(fixed)
                }
                fixed
            } else if (extension == "doc") {
                // Legacy .doc not supported without Apache POI
                return@withContext Result.failure(Exception("暂不支持 .doc 格式，请转换为 .docx 或 .txt 后再导入"))
            } else {
                destFile
            }

            // Parse with Readium (now with PDF support via PdfiumDocumentFactory)
            val url = bookFile.toUrl(isDirectory = false)
            val asset = assetRetriever.retrieve(url).getOrElse {
                return@withContext Result.failure(Exception("无法解析文件"))
            }
            val publication = publicationOpener.open(asset, allowUserInteraction = false).getOrElse {
                asset.close()
                return@withContext Result.failure(Exception("不支持的格式: ${bookFile.extension}"))
            }

            val rawTitle = publication.metadata.title ?: bookFile.nameWithoutExtension
            // Strip leading timestamp prefix (e.g. "1723123456789_mybook" -> "mybook")
            val title = rawTitle.replaceFirst(Regex("""^\d{10,}_"""), "")
            val author = publication.metadata.authors.joinToString(", ") { it.name }
            val description = publication.metadata.description ?: ""
            val format = detectFormat(originalExtension)  // Use original ext, not converted one
            // Compute accurate page count using positions (same as ReaderViewModel)
            val positionsList = publication.positionsByReadingOrder()
            val totalPositions = positionsList.sumOf { it.size }
            val totalPages = maxOf(totalPositions, publication.readingOrder.size)
            val fileSize = bookFile.length()

            // Extract cover (downscaled to a thumbnail so the bookshelf scrolls smoothly)
            val coverPath = try {
                val coverBitmap = publication.cover()
                if (coverBitmap != null) {
                    val coverDir = File(context.filesDir, "covers"); coverDir.mkdirs()
                    val coverFile = File(coverDir, "${bookFile.nameWithoutExtension}.jpg")
                    val maxDim = 400
                    val scaled = if (coverBitmap.width > maxDim || coverBitmap.height > maxDim) {
                        val ratio = minOf(maxDim.toFloat() / coverBitmap.width, maxDim.toFloat() / coverBitmap.height)
                        Bitmap.createScaledBitmap(
                            coverBitmap,
                            (coverBitmap.width * ratio).toInt().coerceAtLeast(1),
                            (coverBitmap.height * ratio).toInt().coerceAtLeast(1),
                            true
                        )
                    } else coverBitmap
                    FileOutputStream(coverFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (scaled !== coverBitmap) scaled.recycle()
                    coverFile.absolutePath
                } else null
            } catch (_: Exception) { null }

            val book = com.ebookreader.domain.model.Book(
                title = title, author = author.ifEmpty { "未知作者" },
                description = description, coverPath = coverPath,
                filePath = bookFile.absolutePath, format = format,
                totalPages = totalPages, currentPage = 0, currentLocator = null,
                totalReadingTime = 0, addedTimestamp = System.currentTimeMillis(),
                lastReadTimestamp = 0, fileSize = fileSize,
            )

            publication.close(); asset.close()
            Result.success(ImportedBook(book, coverPath))
        } catch (e: Exception) {
            Timber.e(e, "Import failed")
            Result.failure(e)
        }
    }

    // ── Encoding detection ──────────────────────────────────────────────

    /**
     * Detects the most likely charset for a TXT file.
     *
     * Strategy: read first 132 KiB → try every candidate charset with a lenient
     * decoder → score by (replacement chars, CJK density) → pick the best fit.
     *
     * This replaces the old strict-UTF-8-validator approach, which could reject
     * valid UTF-8 files that contain 4-byte sequences, unusual Unicode blocks,
     * or boundary-truncated multi-byte characters.
     *
     * Only reads the first 132 KiB — fast even for 100+ MB novels.
     */
    private fun detectCharset(file: File): Charset {
        val sampleSize = minOf(file.length(), 135168L).toInt() // 132 KiB
        val raw = ByteArray(sampleSize)
        file.inputStream().use { stream ->
            var offset = 0
            while (offset < sampleSize) {
                val n = stream.read(raw, offset, sampleSize - offset)
                if (n < 0) break
                offset += n
            }
        }

        // BOM detection — unambiguous, always trust
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte())
            return Charsets.UTF_8
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte())
            return Charset.forName("UTF-16BE")
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte())
            return Charset.forName("UTF-16LE")

        // All candidate charsets (UTF-8 first so it wins ties)
        val candidates = listOf("UTF-8", "GBK", "GB18030", "GB2312", "Big5")

        data class CharsetScore(val name: String, val replacements: Int, val cjkCount: Int)

        val scores = candidates.map { encName ->
            val charset = try { Charset.forName(encName) } catch (_: Exception) { return@map null }
            val text = String(raw, charset)
            val replacements = text.count { it == '�' }
            val cjk = text.count { ch -> ch in '一'..'鿿' || ch in '㐀'..'䶿' }
            CharsetScore(encName, replacements, cjk)
        }.filterNotNull()

        // Best = 0 replacements + most CJK chars (tie → earlier in candidates list wins, i.e. UTF-8)
        val best = scores
            .filter { it.replacements == 0 }
            .maxByOrNull { it.cjkCount }

        if (best != null && best.cjkCount > 0) return Charset.forName(best.name)

        // No encoding produced clean CJK text → pick the one with fewest replacement chars
        return scores.minByOrNull { it.replacements }?.let { Charset.forName(it.name) } ?: Charsets.UTF_8
    }

    // ── Chapter detection ────────────────────────────────────────────────

    /** Internal chapter data used during EPUB generation. */
    private data class EpubChapter(val title: String, val content: String)

    /**
     * A detected chapter heading in the TXT content.
     * @param title the chapter heading text (trimmed)
     * @param startIndex character index in the full text where chapter content begins
     */
    internal data class DetectedChapter(val title: String, val startIndex: Int)

    companion object {
        /**
         * EPUB 生成器版本号。每当 TXT→EPUB 的生成逻辑有影响输出的改动（如新增字符清洗、
         * 章节识别规则）时自增。用于在打开旧书时识别「由旧版本生成的 EPUB」并自动重新转换。
         */
        const val EPUB_GEN_VERSION = 3

        /** 清洗逻辑版本：升级时递增，触发旧版导入的 EPUB 在打开时重新做轻量清洗。 */
        const val SANITIZE_VERSION = 2

        private fun epubGenMarker(epubFile: File): File =
            File(epubFile.parentFile, "${epubFile.name}.genv")

        /** Built-in regex patterns for detecting chapter / section headings. */
        private val builtinChapterPatterns = listOf(
            // 第X章, 第X节, 第X回, 第X卷, 第X部  (锚定行首，后不能紧跟文字/数字，避免「第一节课」误判)
            Regex("""^\s*(第[零一二三四五六七八九十百千万\d]+[章节回卷部集篇])(?![\p{L}\p{N}])\s*.*$"""),
            // 第X话 (「话」是明确的章节标记，允许书名前缀或 < > 包裹，如「< 第2话 >」)
            Regex("""(第[零一二三四五六七八九十百千万\d]+话)(?![\p{L}\p{N}])\s*.*$"""),
            // Chapter X / Ch. X
            Regex("""^\s*[Cc]hapter\s+\d+.*$"""),
            // 序章 / 楔子 / 前言 / 后记 / 尾声 / 番外 / 附录
            Regex("""^\s*(序章|楔子|前言|后记|尾声|番外[一二三四五六七八九十\d]*|附录[一二三四五六七八九十\d]*|尾声|终章|引子|写在前面)\s*.*$"""),
            // Prologue / Epilogue / Preface / Foreword / Afterword
            Regex("""^\s*(Prologue|Epilogue|Preface|Foreword|Afterword|Acknowledgments?)\s*.*$"""),
            // Part X / Book X / Volume X
            Regex("""^\s*[Pp]art\s+\d+.*$"""),
            Regex("""^\s*[Bb]ook\s+\d+.*$"""),
            Regex("""^\s*[Vv]olume\s+\d+.*$"""),
        )
    }

    /**
     * Scans the full text line by line and returns a list of detected chapter boundaries.
     * @param text the full book text
     * @param extraPatterns additional user-defined regex patterns (compiled, validated strings)
     * If no chapters are found, returns an empty list (caller should treat the whole file as one chapter).
     */
    internal fun detectChapters(text: String, extraPatterns: List<String> = emptyList()): List<DetectedChapter> {
        val userPatterns = extraPatterns.mapNotNull { p ->
            try { Regex(p) } catch (_: Exception) { null }
        }
        val chapterPatterns = builtinChapterPatterns + userPatterns

        // 第一遍：找出所有标题（暂不去重）
        val rawChapters = mutableListOf<DetectedChapter>()
        var charOffset = 0
        val lines = text.split("\n")
        for (line in lines) {
            for (pattern in chapterPatterns) {
                val match = pattern.find(line)
                if (match != null) {
                    rawChapters.add(DetectedChapter(match.value.trim(), charOffset))
                    break
                }
            }
            charOffset += line.length + 1 // +1 for \n
        }

        // 第二遍：过滤「伪章节」——标题到下一个标题之间的内容不足 100 字，通常是目录条目
        // 或叙述里提到的「第X话」；再按标题去重，保留第一个「真实」章节（目录里的同名标题
        // 内容短被跳过，正文里的真章节被保留）。
        val minChapterChars = 100
        val chapters = mutableListOf<DetectedChapter>()
        for (i in rawChapters.indices) {
            val ch = rawChapters[i]
            val end = if (i + 1 < rawChapters.size) rawChapters[i + 1].startIndex else text.length
            if (end - ch.startIndex < minChapterChars) continue
            if (chapters.none { it.title == ch.title }) {
                chapters.add(ch)
            }
        }

        // Filter out false positives: if a "chapter" appears only once and there are 50+ candidates,
        // many of them are likely paragraph numbers, not chapters.
        return if (chapters.size > 30) {
            chapters.filter { ch ->
                val t = ch.title
                t.contains("章") || t.contains("回") || t.contains("卷") ||
                    t.contains("节") || t.contains("部") || t.contains("篇") ||
                    t.contains("话") ||
                    t.startsWith("序") || t.startsWith("楔") || t.startsWith("前言") ||
                    t.startsWith("后记") || t.startsWith("尾声") || t.startsWith("番外") ||
                    t.startsWith("附录") || t.startsWith("终章") || t.startsWith("引子") ||
                    t.startsWith("Prologue") || t.startsWith("Epilogue") ||
                    t.startsWith("Chapter")
            }
        } else {
            chapters
        }
    }

    // ── TXT → EPUB conversion ───────────────────────────────────────────

    /**
     * Ensures an EPUB file exists for a given TXT file.
     * If the EPUB is already present and newer than the TXT source, returns it directly.
     * Otherwise converts the TXT to EPUB (detecting encoding once).
     * Use this at book-open time so encoding is detected only on first open.
     */
    fun ensureEpub(txtFile: File): File {
        val epubFile = File(txtFile.parentFile, "${txtFile.nameWithoutExtension}.epub")
        val marker = epubGenMarker(epubFile)
        val markerCurrent = marker.exists() && runCatching {
            marker.readText().trim() == EPUB_GEN_VERSION.toString()
        }.getOrDefault(false)
        if (epubFile.exists() && markerCurrent && epubFile.lastModified() >= txtFile.lastModified()) {
            return epubFile
        }
        return convertTxtToEpub(txtFile)
    }

    internal fun convertTxtToEpub(txtFile: File): File {
        // Auto-detect encoding and read
        val charset = detectCharset(txtFile)
        val text = String(txtFile.readBytes(), charset).sanitizeForXml().stripWatermark().stripGarbled()
        val title = txtFile.nameWithoutExtension.replaceFirst(Regex("""^\d{10,}_"""), "")
        val epubFile = File(txtFile.parentFile, "${txtFile.nameWithoutExtension}.epub")

        // Detect chapters
        val detectedChapters = detectChapters(text)

        // Build EpubChapter list
        val chapters = if (detectedChapters.isNotEmpty()) {
            val list = mutableListOf<EpubChapter>()
            for (i in detectedChapters.indices) {
                val ch = detectedChapters[i]
                val start = ch.startIndex
                val end = if (i + 1 < detectedChapters.size) detectedChapters[i + 1].startIndex else text.length
                val content = text.substring(start, end).trim()
                if (content.isNotBlank()) {
                    list.add(EpubChapter(ch.title, content))
                }
            }
            // Prepend any text before the first chapter heading
            val firstStart = detectedChapters.first().startIndex
            if (firstStart > 0) {
                val preamble = text.substring(0, firstStart).trim()
                if (preamble.isNotBlank()) {
                    list.add(0, EpubChapter(title, preamble))
                }
            }
            list
        } else {
            listOf(EpubChapter(title, text))
        }

        if (chapters.isEmpty()) {
            return convertSimpleTxt(txtFile, title, text)
        }

        writeEpubWithChapters(epubFile, title, chapters)
        return epubFile
    }

    /** Fallback: one spine item for the entire text — no chapter detection. */
    private fun convertSimpleTxt(txtFile: File, title: String, text: String): File {
        val epubFile = File(txtFile.parentFile, "${txtFile.nameWithoutExtension}.epub")

        ZipOutputStream(FileOutputStream(epubFile)).use { zip ->
            val mimetypeBytes = "application/epub+zip".toByteArray()
            val mimetypeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                crc = CRC32().also { it.update(mimetypeBytes) }.value
            }
            zip.putNextEntry(mimetypeEntry); zip.write(mimetypeBytes); zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" unique-identifier="book-id" xmlns="http://www.idpf.org/2007/opf">
  <metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">${title.xmlEscape()}</dc:title>
    <dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">zh-CN</dc:language></metadata>
  <manifest>
    <item id="p0" href="p0.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="p0"/>
  </spine>
</package>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/p0.xhtml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${title.xmlEscape()}</title>
<style>body{font-family:serif;font-size:1em;line-height:1.8;padding:1em;white-space:pre-wrap;}</style></head>
<body>${text.xmlEscape()}</body></html>""".toByteArray())
            zip.closeEntry()
        }
        epubGenMarker(epubFile).writeText(EPUB_GEN_VERSION.toString())
        return epubFile
    }

    /**
     * Regenerates an EPUB from its source TXT file with updated chapter detection and filtering.
     * Used by the "refresh chapters" and "delete chapter" features in the reader.
     *
     * @param epubFilePath path to the existing EPUB file
     * @param customPatterns user-defined regex patterns for chapter detection
     * @param hiddenTitles chapter titles to exclude from the regenerated EPUB
     * @return the regenerated EPUB file + the set of hidden titles that were actually merged
     */
    fun reconvertTxt(
        epubFilePath: String,
        customPatterns: List<String> = emptyList(),
        hiddenTitles: Set<String> = emptySet(),
    ): ReconvertResult {
        val epubFile = File(epubFilePath)
        val txtFile = File(epubFile.parentFile, "${epubFile.nameWithoutExtension}.txt")
        if (!txtFile.exists()) {
            throw Exception("无法找到源文本文件，仅支持从 TXT/DOCX 导入的书籍")
        }

        val charset = detectCharset(txtFile)
        val text = String(txtFile.readBytes(), charset).sanitizeForXml().stripWatermark().stripGarbled()
        val title = epubFile.nameWithoutExtension.replaceFirst(Regex("""^\d{10,}_"""), "")
        val detected = detectChapters(text, customPatterns)
        val matchedTitles = mutableSetOf<String>()

        // Build chapters from detected boundaries.
        // Hidden chapters are merged into the previous chapter (content preserved,
        // chapter boundary removed) instead of being dropped entirely.
        val chapters = if (detected.isNotEmpty()) {
            // First pass: absorb hidden chapters into their predecessor by
            // removing the boundary — the previous chapter's range will extend
            // to the next visible chapter's start.
            val merged = mutableListOf<DetectedChapter>()
            for (ch in detected) {
                if (ch.title in hiddenTitles && merged.isNotEmpty()) {
                    // Skip this boundary → its content merges into the previous chapter
                    matchedTitles.add(ch.title)
                    continue
                }
                merged.add(ch)
            }

            val list = mutableListOf<EpubChapter>()
            for (i in merged.indices) {
                val ch = merged[i]
                val start = ch.startIndex
                val end = if (i + 1 < merged.size) merged[i + 1].startIndex else text.length
                val content = text.substring(start, end).trim()
                if (content.isNotBlank()) {
                    list.add(EpubChapter(ch.title, content))
                }
            }
            val firstStart = detected.first().startIndex
            if (firstStart > 0) {
                val preamble = text.substring(0, firstStart).trim()
                if (preamble.isNotBlank()) {
                    list.add(0, EpubChapter(title, preamble))
                }
            }
            list
        } else {
            listOf(EpubChapter(title, text))
        }

        if (chapters.isEmpty()) {
            return ReconvertResult(convertSimpleTxt(txtFile, title, text), emptySet())
        }

        // Regenerate EPUB in-place (overwrites the existing file)
        writeEpubWithChapters(epubFile, title, chapters)
        return ReconvertResult(epubFile, matchedTitles)
    }

    /** reconvertTxt 的结果：重新生成的 EPUB 文件 + 本次实际被合并（隐藏）的章节标题。 */
    data class ReconvertResult(
        val file: File,
        val mergedHiddenTitles: Set<String>,
    )

    /** Writes an EPUB file with one spine item per chapter. */
    private fun writeEpubWithChapters(epubFile: File, title: String, chapters: List<EpubChapter>) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(epubFile)).use { zip ->
            val mimetypeBytes = "application/epub+zip".toByteArray()
            val mimetypeEntry = java.util.zip.ZipEntry("mimetype").apply {
                method = java.util.zip.ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                crc = java.util.zip.CRC32().also { it.update(mimetypeBytes) }.value
            }
            zip.putNextEntry(mimetypeEntry); zip.write(mimetypeBytes); zip.closeEntry()

            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray())
            zip.closeEntry()

            val navOl = StringBuilder()
            for ((ci, ch) in chapters.withIndex()) {
                navOl.append("        <li><a href=\"c${ci}.xhtml\">${ch.title.xmlEscape()}</a></li>\n")
            }
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/nav.xhtml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${title.xmlEscape()} - 目录</title></head>
<body><nav epub:type="toc"><h1>目录</h1><ol>
$navOl        </ol></nav></body></html>""".toByteArray())
            zip.closeEntry()

            val manifest = StringBuilder()
            val spine = StringBuilder()
            manifest.append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            for (ci in chapters.indices) {
                val id = "c$ci"
                manifest.append("    <item id=\"$id\" href=\"$id.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
                spine.append("    <itemref idref=\"$id\"/>\n")
            }
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/content.opf"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" unique-identifier="book-id" xmlns="http://www.idpf.org/2007/opf">
  <metadata>
    <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">${title.xmlEscape()}</dc:title>
    <dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">zh-CN</dc:language>
  </metadata>
  <manifest>$manifest  </manifest>
  <spine>$spine  </spine>
</package>""".toByteArray())
            zip.closeEntry()

            for ((ci, ch) in chapters.withIndex()) {
                zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/c${ci}.xhtml"))
                zip.write("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${ch.title.xmlEscape()}</title>
<style>body{font-family:serif;font-size:1em;line-height:1.8;padding:1em;white-space:pre-wrap;}</style></head>
<body>${ch.content.xmlEscape()}</body></html>""".toByteArray())
                zip.closeEntry()
            }
        }
        epubGenMarker(epubFile).writeText(EPUB_GEN_VERSION.toString())
    }

    /** 解析 href 相对 OPF 目录的 ZIP 条目路径。 */
    private fun resolvePath(baseDir: String, href: String): String {
        if (href.startsWith("/")) return href.removePrefix("/")
        val parts = baseDir.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in href.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** 提取 HTML 的 <body> 内部内容。 */
    private fun extractBodyContent(html: String): String {
        val bodyOpen = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE).find(html) ?: return ""
        val start = bodyOpen.range.last + 1
        val bodyClose = Regex("""</body\s*>""", RegexOption.IGNORE_CASE).find(html, start)
            ?: return html.substring(start)
        return html.substring(start, bodyClose.range.first)
    }

    /**
     * 从生成的 EPUB 反推源文本：按 spine 顺序拼接各章 <body> 正文（反转 xmlEscape），
     * 写到同名 .txt 文件。用于旧版导入的 TXT/DOCX 书籍（源 .txt 已丢失）的「刷新目录」：
     * 先恢复源文本，再用当前章节识别规则重新生成 EPUB。
     */
    fun reconstructSourceTxt(epubFile: File): File {
        val txtFile = File(epubFile.parentFile, "${epubFile.nameWithoutExtension}.txt")

        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipFile(epubFile).use { zin ->
            val e = zin.entries()
            while (e.hasMoreElements()) {
                val en = e.nextElement()
                if (!en.isDirectory) entries[en.name] = zin.getInputStream(en).readBytes()
            }
        }

        val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: throw Exception("EPUB 缺少 container.xml")
        var opfPath = Regex("""full-path="([^"]+)"""").find(containerXml)?.groupValues?.get(1)
        if (opfPath == null) {
            opfPath = entries.keys.firstOrNull { it.endsWith(".opf") } ?: throw Exception("找不到 OPF 文件")
        }
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = entries[opfPath]?.toString(Charsets.UTF_8) ?: throw Exception("找不到 OPF 内容")

        // manifest: id -> href
        val idToHref = HashMap<String, String>()
        for (tag in Regex("""<item\b[^>]*>""").findAll(opf)) {
            val id = Regex("""\bid="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: continue
            val href = Regex("""\bhref="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: continue
            idToHref[id] = href
        }
        val spineIds = Regex("""<itemref\b[^>]*\bidref="([^"]*)"""").findAll(opf)
            .map { it.groupValues[1] }.toList()

        fun zipPath(href: String): String {
            val decoded = percentDecode(href).substringBefore('#').substringBefore('?')
            return if (decoded.startsWith("/")) decoded.removePrefix("/")
            else resolvePath(opfDir, decoded)
        }

        val sb = StringBuilder()
        for (id in spineIds) {
            val href = idToHref[id] ?: continue
            val html = entries[zipPath(href)]?.toString(Charsets.UTF_8) ?: continue
            val body = extractBodyContent(html).xmlUnescape().trim()
            if (body.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(body)
            }
        }

        val text = sb.toString()
        if (text.isBlank()) throw Exception("无法从 EPUB 恢复源文本")
        txtFile.writeText(text, Charsets.UTF_8)
        return txtFile
    }

    // ── DOCX → TXT → EPUB conversion ────────────────────────────────────

    /**
     * Extracts plain text from a .docx file, writes it to a .txt file,
     * then converts it to EPUB using the existing, well-tested TXT pipeline.
     */
    private fun convertDocxToTxt(docxFile: File): File {
        val paragraphs = readDocx(docxFile)
        if (paragraphs.isEmpty()) throw Exception("无法从 DOCX 文件中提取文本内容")
        val fullText = paragraphs.joinToString("\n\n")
        val txtFile = File(docxFile.parentFile, "${docxFile.nameWithoutExtension}.txt")
        txtFile.writeText(fullText, Charsets.UTF_8)
        return convertTxtToEpub(txtFile)
    }

    /**
     * Read text from a .docx file by directly parsing the ZIP-contained XML.
     * DOCX is a ZIP archive; text lives in word/document.xml as <w:t> elements.
     * No POI dependency needed — uses Android's built-in XmlPullParser.
     */
    private fun readDocx(file: File): List<String> {
        val paragraphs = mutableListOf<String>()
        try {
            val zipFile = java.util.zip.ZipFile(file)
            val entry = zipFile.getEntry("word/document.xml")
                ?: throw Exception("word/document.xml not found in DOCX archive")
            val inputStream = zipFile.getInputStream(entry)

            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            // XML namespace used by Word Open XML documents
            val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

            val currentParagraph = StringBuilder()
            var inParagraph = false
            var inText = false
            var eventType = parser.eventType

            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        val ns = parser.namespace ?: ""
                        when {
                            parser.name == "p" && ns.startsWith("http://schemas.openxmlformats.org/wordprocessingml/") -> {
                                currentParagraph.clear()
                                inParagraph = true
                            }
                            parser.name == "t" && ns.startsWith("http://schemas.openxmlformats.org/wordprocessingml/") -> {
                                inText = true
                            }
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> {
                        if (inParagraph && inText) {
                            currentParagraph.append(parser.text)
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        when {
                            parser.name == "t" -> inText = false
                            parser.name == "p" -> {
                                if (inParagraph) {
                                    val text = currentParagraph.toString().trim()
                                    if (text.isNotEmpty()) paragraphs.add(text)
                                    inParagraph = false
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            inputStream.close()
            zipFile.close()
        } catch (e: Exception) {
            Timber.e(e, "Failed to read DOCX file")
            throw Exception("无法读取 DOCX 文件: ${e.message}")
        }
        return paragraphs
    }

    // ── XML helpers ──────────────────────────────────────────────────────

    /**
     * Escapes a string for safe inclusion in XML/XHTML.
     * Also handles supplementary Unicode characters (emoji, U+10000+)
     * by converting them to `&#xHHHH;` numeric character references,
     * since XML 1.0 does not allow raw surrogate pairs.
     *
     * Uses a fast path for the common case (no special chars, no emoji).
     */
    private fun String.xmlEscape(): String {
        // Fast path: scan for any character that needs escaping, or is an invalid XML char
        var needsEscape = false
        for (ch in this) {
            when (ch) {
                '&', '<', '>', '"', '\'' -> { needsEscape = true; break }
            }
            if (ch.isHighSurrogate() || (ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r')) {
                needsEscape = true; break
            }
        }
        if (!needsEscape) return this

        // Slow path: build escaped output, dropping invalid XML 1.0 control chars
        val sb = StringBuilder(length + 32)
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            val charCount = Character.charCount(cp)
            val isInvalidControl = cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp == '\''.code -> sb.append("&apos;")
                cp > 0xFFFF -> sb.append("&#x${cp.toString(16).uppercase()};")
                isInvalidControl -> { /* 丢弃非法控制字符 */ }
                else -> sb.append(this, i, i + charCount)
            }
            i += charCount
        }
        return sb.toString()
    }

    /** 反转 [xmlEscape]：把命名实体与数字字符引用还原为原始字符，用于从生成的 EPUB 恢复源文本。 */
    private fun String.xmlUnescape(): String {
        var result = this
        result = Regex("""&#x([0-9A-Fa-f]+);""").replace(result) { m ->
            String(Character.toChars(m.groupValues[1].toInt(16)))
        }
        result = Regex("""&#(\d+);""").replace(result) { m ->
            String(Character.toChars(m.groupValues[1].toInt()))
        }
        return result
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /**
     * 对解码后的整段文本做一次 XML 安全清洗，在章节切分之前执行：
     * - 去除 BOM（U+FEFF，UTF-16 解码时 Java 不会自动剥掉）
     * - 丢弃 XML 1.0 不允许的控制字符（0x00-0x08、0x0B、0x0C、0x0E-0x1F）
     * - 把孤立代理项替换为 U+FFFD
     *
     * 这是从根上保证生成的 EPUB/XHTML 不含非法字符，避免依赖每章写入时的 xmlEscape。
     */
    private fun String.sanitizeForXml(): String {
        if (isEmpty()) return this
        val sb = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            when {
                c.code == 0xFEFF -> { /* 跳过 BOM */ }
                c.code == 0xFFFE || c.code == 0xFFFF -> { /* 丢弃非字符 */ }
                c.code in 0xFDD0..0xFDEF -> { /* 丢弃非字符 */ }
                c.code in 0xE000..0xF8FF -> { /* 丢弃私用区（正文不会出现） */ }
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> { /* 丢弃控制字符 */ }
                c.isHighSurrogate() -> {
                    if (i + 1 < length && this[i + 1].isLowSurrogate()) {
                        sb.append(c).append(this[i + 1]); i++
                    } else {
                        sb.append('�') // 孤立高代理项
                    }
                }
                c.isLowSurrogate() -> sb.append('�') // 孤立低代理项
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 去除 TXT 中常见的「水印」段：盗版网站常在正文里插入一段用相反字节序（如 UTF-16BE）
     * 编码的推广语，按正文的 UTF-16LE 解码后会变成 U+0A00（古吉拉特文）+ 一段乱码，一直持续到换行。
     * U+0A00 在中文正文里永不出现，故以其为标记，删除从它到下一个换行之间的内容。
     */
    private fun String.stripWatermark(): String {
        if (indexOf('਀') < 0) return this
        val sb = StringBuilder(length)
        var i = 0
        while (i < length) {
            if (this[i] == '਀') {
                // 跳到下一个换行（保留换行本身）
                while (i < length && this[i] != '\n') i++
            } else {
                sb.append(this[i])
                i++
            }
        }
        return sb.toString()
    }

    /** 判断一个字符是否属于中文小说正文里几乎不会出现的「可疑」区块（韩文、假名、私用区、CJK扩展A 等）。 */
    private fun isSuspiciousChar(c: Char): Boolean {
        val code = c.code
        return when {
            code in 0x0020..0x007E -> false                     // ASCII
            code == 0x00A0 || code == 0x00B7 || code == 0x00D7 || code == 0x00F7 -> false // NBSP · × ÷
            code in 0x2013..0x2014 || code in 0x2018..0x201D || code == 0x2026 -> false  // – — ‘ ’ “ ” …
            code in 0x3000..0x303F -> false                     // CJK 标点
            code in 0x4E00..0x9FFF -> false                     // CJK 汉字
            code in 0xFF00..0xFFEF -> false                     // 全角
            code == 0x09 || code == 0x0A || code == 0x0D -> false
            else -> true
        }
    }

    /**
     * 用滑动窗口去除「乱码段」：反字节序（UTF-16BE 水印按 LE 解码）产生的乱码会在正常文字里
     * 形成一段高密度可疑字符，把这种段整段删除，避免正文里混入韩文/私用区等乱码。
     */
    private fun String.stripGarbled(): String {
        val n = length
        if (n < 6) return this
        val suspicious = BooleanArray(n)
        var hasAny = false
        for (i in 0 until n) {
            suspicious[i] = isSuspiciousChar(this[i])
            if (suspicious[i]) hasAny = true
        }
        if (!hasAny) return this
        val remove = BooleanArray(n)
        val window = 6
        val threshold = 2
        for (i in 0..n - window) {
            var cnt = 0
            for (k in 0 until window) if (suspicious[i + k]) cnt++
            if (cnt >= threshold) {
                for (k in 0 until window) remove[i + k] = true
            }
        }
        val sb = StringBuilder(n)
        for (i in 0 until n) if (!remove[i]) sb.append(this[i])
        return sb.toString()
    }

    /**
     * 修复 EPUB 中的兼容性问题（通用，不针对具体书）：
     * 1. XHTML 里未转义的 `&`（EntityRef 错误）与非法控制字符；
     * 2. 不受支持的 DRM（如多看 DuoKan 的 rsa+aes 加密）：剥离 encryption.xml 与被加密资源；
     * 3. 多看 DuoKan 的混淆文件名（`*`/`:` 组成，会破坏 URL 解析）：按 manifest 的 id 重命名为干净文件名；
     * 4. 超大尺寸图片（超高长图导致只渲染顶部）：降采样到 2000px 以内。
     * 仅在检测到问题时生成一份修复后的副本，否则原样返回原文件。
     */
    private fun sanitizeEpubXhtml(epubFile: File): File {
        val badAmp = Regex("""&(?!#\d+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*;)""")
        val sanitizedFile = File(epubFile.parentFile, "${epubFile.nameWithoutExtension}_fixed.epub")
        var changed = false
        return try {
            // ── 第一遍：收集 DRM 加密资源 + DuoKan 文件名混淆映射 ──
            val skipNames = mutableSetOf<String>()
            val obfuscationMap = mutableMapOf<String, String>() // 混淆 basename -> 干净 basename
            java.util.zip.ZipFile(epubFile).use { zin ->
                val encEntry = zin.getEntry("META-INF/encryption.xml")
                if (encEntry != null) {
                    val encXml = zin.getInputStream(encEntry).readBytes().toString(Charsets.UTF_8)
                    if (encXml.contains("EncryptedKey")) {
                        skipNames.add("META-INF/encryption.xml")
                        for (m in Regex("""URI="([^"]+)"""").findAll(encXml)) {
                            val uri = m.groupValues[1]
                            skipNames.add(uri)
                            skipNames.add(uri.substringAfterLast('/'))
                        }
                    }
                }
                // 定位 OPF
                var opfPath: String? = null
                val container = zin.getEntry("META-INF/container.xml")
                if (container != null) {
                    val cxml = zin.getInputStream(container).readBytes().toString(Charsets.UTF_8)
                    opfPath = Regex("""full-path="([^"]+)"""").find(cxml)?.groupValues?.get(1)
                }
                if (opfPath == null) {
                    val e = zin.entries()
                    while (e.hasMoreElements()) {
                        val n = e.nextElement().name
                        if (n.endsWith(".opf")) { opfPath = n; break }
                    }
                }
                // 解析 manifest 建立混淆映射（仅当文件名含 * 或 : 时）
                val opfEntry = opfPath?.let { zin.getEntry(it) }
                if (opfEntry != null) {
                    val opf = zin.getInputStream(opfEntry).readBytes().toString(Charsets.UTF_8)
                    for (itemTag in Regex("""<item\b[^>]*>""").findAll(opf)) {
                        val tag = itemTag.value
                        val id = Regex("""id="([^"]+)"""").find(tag)?.groupValues?.get(1)
                        val href = Regex("""href="([^"]+)"""").find(tag)?.groupValues?.get(1)
                        if (id != null && href != null) {
                            val decoded = percentDecode(href)
                            val basename = decoded.substringAfterLast('/')
                            if (basename.contains('*') || basename.contains(':')) {
                                val ext = basename.substringAfterLast('.', "")
                                obfuscationMap[basename] = if (ext.isNotEmpty()) "$id.$ext" else id
                            }
                        }
                    }
                }
            }
            // ── 第二遍：重写 ──
            java.util.zip.ZipFile(epubFile).use { zin ->
                ZipOutputStream(FileOutputStream(sanitizedFile)).use { zout ->
                    val entries = zin.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (skipNames.contains(entry.name) || skipNames.contains(entry.name.substringAfterLast('/'))) {
                            changed = true
                            continue
                        }
                        // 重命名混淆文件名
                        val rawBasename = entry.name.substringAfterLast('/')
                        val cleanBasename = obfuscationMap[rawBasename]
                        val newName = if (cleanBasename != null) {
                            changed = true
                            val dir = entry.name.substringBeforeLast('/', "")
                            (if (dir.isEmpty()) "" else "$dir/") + cleanBasename
                        } else entry.name

                        var data = zin.getInputStream(entry).readBytes()
                        val lower = entry.name.lowercase()
                        val isHtml = lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
                        val isText = isHtml || lower.endsWith(".opf") || lower.endsWith(".ncx") || lower.endsWith(".css") || lower.endsWith(".xml")
                        if (isText) {
                            var text = String(data, Charsets.UTF_8)
                            if (isHtml) {
                                text = badAmp.replace(text, "&amp;").sanitizeForXml().stripTrailingAfterHtml()
                                // 封面页 body 用 background-image 时，body 高度只有内容高（如 <br>），
                                // 导致 background-size:cover 只渲染顶部一条；注入样式撑满高度。
                                if (Regex("""<body[^>]*background-image""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                                    text = injectHeadStyle(text, "html,body{height:100%;margin:0;padding:0}")
                                }
                                // 插图显示不全（大插图只显示顶部一条）：出版方 CSS 常给 img 设死宽高、
                                // 或用 background 尺寸，导致超高长图被裁掉下半部分。注入 !important 样式，
                                // 同时约束宽、高，保证图片按原比例缩放、完整落在页面内。
                                if (Regex("""<(img|image|svg)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                                    text = injectHeadStyle(text, "img,svg{max-width:100%!important;max-height:95vh!important;height:auto!important}")
                                }
                            }
                            // 替换混淆文件名（raw 和 URL 编码两种形态）
                            for ((obf, clean) in obfuscationMap) {
                                text = text.replace(obf, clean)
                                text = text.replace(obf.replace("*", "%2A").replace(":", "%3A"), clean)
                            }
                            if (text != String(data, Charsets.UTF_8)) {
                                changed = true
                                data = text.toByteArray(Charsets.UTF_8)
                            }
                        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
                            val downscaled = downscaleImage(data)
                            if (downscaled != null) { changed = true; data = downscaled }
                        }

                        val newEntry = ZipEntry(newName)
                        if (entry.time >= 0) newEntry.time = entry.time
                        newEntry.comment = entry.comment
                        newEntry.extra = entry.extra
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = data.size.toLong()
                            newEntry.compressedSize = data.size.toLong()
                            newEntry.crc = CRC32().also { it.update(data) }.value
                        }
                        zout.putNextEntry(newEntry)
                        zout.write(data)
                        zout.closeEntry()
                    }
                }
            }
            if (changed) sanitizedFile else { sanitizedFile.delete(); epubFile }
        } catch (_: Exception) {
            sanitizedFile.delete()
            epubFile
        }
    }

    /** 在 <head> 打开标签后注入一个 <style> 块；无 <head> 时原样返回。 */
    private fun injectHeadStyle(text: String, css: String): String {
        val headTag = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE).find(text) ?: return text
        val insertAt = headTag.range.last + 1
        return text.substring(0, insertAt) + "<style>$css</style>" + text.substring(insertAt)
    }

    /**
     * 修复「Extra content at the end of the document」：某些出版工具会把内容（如图片）写到
     * </html> 之后，严格 XML 解析（Readium WebView 用 application/xhtml+xml）会因此报错。
     * 把根元素关闭标签之后的所有非空白内容截掉，保证文档只有一个根元素。
     */
    private fun String.stripTrailingAfterHtml(): String {
        val close = Regex("""</html\s*>""", RegexOption.IGNORE_CASE).find(this) ?: return this
        val after = substring(close.range.last + 1)
        return if (after.any { !it.isWhitespace() }) substring(0, close.range.last + 1) else this
    }

    // ── 打开时的轻量残留内容清洗（修复旧版导入书籍）────────────────────────

    private fun stripVersionKey(path: String) = "trailing_strip_v${SANITIZE_VERSION}_$path"

    private fun markTrailingStripped(epubFile: File) =
        prefs.edit().putBoolean(stripVersionKey(epubFile.absolutePath), true).apply()

    /**
     * 打开 EPUB 前调用：确保各 XHTML 条目里 `</html>` 之后的非空白残留内容（如封面页残留的 <img>）
     * 已被清除。旧版导入的书籍首次打开会自动补做一次清洗（按文件路径 + 版本号只执行一次），
     * 无需用户手动删除重导。返回（可能被改写后的）EPUB 文件。
     */
    fun ensureEpubTrailingStripped(epubFile: File): File {
        if (prefs.getBoolean(stripVersionKey(epubFile.absolutePath), false)) return epubFile
        try {
            stripEpubTrailingContent(epubFile)
        } catch (_: Exception) {
            // 清洗失败也不阻断打开，仍返回原文件
        }
        markTrailingStripped(epubFile)
        return epubFile
    }

    /** 轻量重写：仅移除 XHTML 条目 `</html>` 之后的非空白内容，其余条目原样复制。返回是否有改动。 */
    private fun stripEpubTrailingContent(epubFile: File): Boolean {
        val htmlExts = listOf(".html", ".xhtml", ".htm")
        fun isHtml(name: String) = htmlExts.any { name.endsWith(it) }

        var needFix = false
        java.util.zip.ZipFile(epubFile).use { zin ->
            val entries = zin.entries()
            while (entries.hasMoreElements() && !needFix) {
                val entry = entries.nextElement()
                if (isHtml(entry.name.lowercase())) {
                    val text = zin.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    if (text.stripTrailingAfterHtml() != text) needFix = true
                }
            }
        }
        if (!needFix) return false

        val tmp = File(epubFile.parentFile, "${epubFile.name}.tmp")
        try {
            java.util.zip.ZipFile(epubFile).use { zin ->
                ZipOutputStream(FileOutputStream(tmp)).use { zout ->
                    val entries = zin.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        var data = zin.getInputStream(entry).readBytes()
                        if (isHtml(entry.name.lowercase())) {
                            val text = String(data, Charsets.UTF_8)
                            val stripped = text.stripTrailingAfterHtml()
                            if (stripped != text) data = stripped.toByteArray(Charsets.UTF_8)
                        }
                        val newEntry = ZipEntry(entry.name)
                        if (entry.time >= 0) newEntry.time = entry.time
                        newEntry.comment = entry.comment
                        newEntry.extra = entry.extra
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = data.size.toLong()
                            newEntry.compressedSize = data.size.toLong()
                            newEntry.crc = CRC32().also { it.update(data) }.value
                        }
                        zout.putNextEntry(newEntry)
                        zout.write(data)
                        zout.closeEntry()
                    }
                }
            }
            if (!tmp.renameTo(epubFile)) {
                epubFile.delete()
                if (!tmp.renameTo(epubFile)) {
                    tmp.copyTo(epubFile, overwrite = true)
                    tmp.delete()
                }
            }
            return true
        } catch (_: Exception) {
            tmp.delete()
            return false
        }
    }

    /** 百分号解码（仅处理 %XX，不把 + 当空格）。 */
    private fun percentDecode(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { sb.append(v.toChar()); i += 3; continue }
            }
            sb.append(s[i]); i++
        }
        return sb.toString()
    }

    /** 把超大图片降采样到最长边 2000px 以内，避免 WebView 只渲染顶部一条。返回 null 表示无需处理。 */
    private fun downscaleImage(data: ByteArray): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            val w = bounds.outWidth; val h = bounds.outHeight
            val maxDim = 2000
            if (w <= 0 || h <= 0 || (w <= maxDim && h <= maxDim)) return null
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            val ratio = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * ratio).toInt().coerceAtLeast(1),
                (bmp.height * ratio).toInt().coerceAtLeast(1), true,
            )
            if (scaled !== bmp) bmp.recycle()
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            scaled.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return it.getString(idx)
                }
            }
            uri.lastPathSegment
        } catch (_: Exception) { uri.lastPathSegment }
    }

    private fun detectFormat(extension: String) = when (extension.lowercase()) {
        "epub" -> "EPUB"; "pdf" -> "PDF"; "cbz" -> "CBZ"; "mobi" -> "MOBI"
        "azw", "azw3" -> "AZW"; "txt" -> "TXT"; "docx" -> "DOCX"
        else -> extension.uppercase()
    }
}
