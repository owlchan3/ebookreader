package com.ebookreader.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ebookreader.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 应用数据备份 / 恢复。
 *
 * 备份范围：Room 数据库三件套（.db / -wal / -shm）、filesDir/books、filesDir/covers、
 * shared_prefs 下全部 .xml 设置文件。打包成单个 zip，附 manifest.json 校验头。
 *
 * 导入为「全量覆盖」：先校验 manifest，再关闭数据库、清空 books/covers/数据库/prefs，
 * 解包写回。恢复后需重启应用，让单例数据库重新从磁盘加载。
 */
object BackupManager {

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FORMAT_MAGIC = "ebookreader-backup"
    private const val FORMAT_VERSION = 1

    data class BackupResult(
        val success: Boolean,
        val message: String,
    )

    /** 导出到 [destUri]（SAF 提供的可写输出流）。 */
    suspend fun exportBackup(context: Context, destUri: Uri): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                val databasesDir = context.getDatabasePath("ebook_reader.db").parentFile
                val booksDir = File(context.filesDir, "books")
                val coversDir = File(context.filesDir, "covers")
                val prefsDir = File(context.dataDir, "shared_prefs")

                // 先把 WAL 中已提交的数据合并回主库文件，避免 .db 与 -wal/-shm 不同步导致备份不完整。
                try { AppDatabase.getInstance(context).checkpoint() } catch (_: Exception) { }

                var bookCount = 0
                var totalBytes = 0L

                val out = context.contentResolver.openOutputStream(destUri)
                    ?: return@withContext BackupResult(false, "无法创建备份文件")

                out.use { os ->
                    ZipOutputStream(os).use { zip ->
                        val manifest = JSONObject()
                            .put("format", FORMAT_MAGIC)
                            .put("version", FORMAT_VERSION)
                            .put("appVersion", com.ebookreader.BuildConfig.VERSION_NAME)
                            .put("createdAt", System.currentTimeMillis())
                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        for (name in listOf("ebook_reader.db", "ebook_reader.db-wal", "ebook_reader.db-shm")) {
                            val f = File(databasesDir, name)
                            if (f.exists()) {
                                totalBytes += f.length()
                                zipFile(zip, f, name)
                            }
                        }
                        bookCount = zipDir(zip, booksDir, "books") { totalBytes += it }
                        zipDir(zip, coversDir, "covers") { totalBytes += it }
                        prefsDir.listFiles()?.forEach { f ->
                            if (f.isFile && f.name.endsWith(".xml")) {
                                totalBytes += f.length()
                                zipFile(zip, f, "shared_prefs/${f.name}")
                            }
                        }
                    }
                }

                BackupResult(true, "导出成功：${bookCount} 本书，共 ${formatBytes(totalBytes)}")
            } catch (e: Exception) {
                BackupResult(false, "导出失败：${e.message ?: "未知错误"}")
            }
        }

    /** 从 [srcUri] 导入备份（全量覆盖）。恢复后需重启应用。 */
    suspend fun importBackup(context: Context, srcUri: Uri): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                if (!validateManifest(context, srcUri)) {
                    return@withContext BackupResult(false, "不是有效的 EBookReader 备份文件")
                }

                AppDatabase.closeInstance()

                val databasesDir = context.getDatabasePath("ebook_reader.db").parentFile
                val booksDir = File(context.filesDir, "books")
                val coversDir = File(context.filesDir, "covers")
                val prefsDir = File(context.dataDir, "shared_prefs")

                for (name in listOf("ebook_reader.db", "ebook_reader.db-wal", "ebook_reader.db-shm")) {
                    File(databasesDir, name).delete()
                }
                booksDir.deleteRecursively()
                booksDir.mkdirs()
                coversDir.deleteRecursively()
                coversDir.mkdirs()
                prefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { it.delete() }

                var count = 0
                var totalBytes = 0L

                val input = context.contentResolver.openInputStream(srcUri)
                    ?: return@withContext BackupResult(false, "无法读取备份文件")

                input.use { ins ->
                    ZipInputStream(ins).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (!entry.isDirectory && name != MANIFEST_ENTRY && isSafePath(name)) {
                                val dest = when {
                                    name == "ebook_reader.db" || name == "ebook_reader.db-wal" || name == "ebook_reader.db-shm" ->
                                        File(databasesDir, name)
                                    name.startsWith("books/") ->
                                        File(booksDir, name.removePrefix("books/"))
                                    name.startsWith("covers/") ->
                                        File(coversDir, name.removePrefix("covers/"))
                                    name.startsWith("shared_prefs/") && name.endsWith(".xml") ->
                                        File(prefsDir, name.removePrefix("shared_prefs/"))
                                    else -> null
                                }
                                if (dest != null) {
                                    dest.parentFile?.mkdirs()
                                    FileOutputStream(dest).use { fo -> zip.copyTo(fo) }
                                    totalBytes += dest.length()
                                    count++
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }

                BackupResult(true, "恢复成功：${count} 个文件，共 ${formatBytes(totalBytes)}。\n应用将自动重启以加载数据。")
            } catch (e: Exception) {
                BackupResult(false, "恢复失败：${e.message ?: "未知错误"}")
            }
        }

    /** 打开备份文件第一遍：读取并校验 manifest.json。 */
    private fun validateManifest(context: Context, srcUri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(srcUri)?.use { ins ->
                ZipInputStream(ins).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == MANIFEST_ENTRY) {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            return JSONObject(text).optString("format") == FORMAT_MAGIC
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 拒绝可能逃逸出目标目录的路径（zip-slip 防护）。 */
    private fun isSafePath(name: String): Boolean =
        name.isNotEmpty() &&
            !name.startsWith("/") &&
            !name.contains("../") &&
            name.split('/').none { it == ".." }

    private fun zipFile(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun zipDir(zip: ZipOutputStream, dir: File, baseName: String, onSize: (Long) -> Unit): Int {
        if (!dir.exists()) return 0
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                count += zipDir(zip, f, "$baseName/${f.name}", onSize)
            } else if (f.isFile) {
                onSize(f.length())
                zipFile(zip, f, "$baseName/${f.name}")
                count++
            }
        }
        return count
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 重启应用进程，让恢复后的数据从磁盘重新加载（清空所有内存单例与 ViewModel）。 */
    fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
