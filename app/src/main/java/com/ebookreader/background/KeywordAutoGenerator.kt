package com.ebookreader.background

import com.ebookreader.di.Injector
import com.ebookreader.domain.repository.KeywordRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * 关键词后台生成：仅在导入新书时自动提取关键词（静默、不阻塞阅读），
 * 不主动扫描/回填书架存量书——存量书的关键词由用户在详情页手动触发。
 */
object KeywordAutoGenerator {

    /** 兜底异常处理器：后台任务任何未捕获的 Throwable（含 OOM）只记日志，绝不闪退。 */
    private val handler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "keyword auto generator crashed")
    }

    /** 进程级作用域：不依赖任何 ViewModel，切屏/退到后台时进程仍在即可继续。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)

    /** 全局串行化提取：一次只跑一本书，避免抢占 CPU/IO，保证阅读不卡顿。 */
    private val mutex = Mutex()

    private val keywordRepository: KeywordRepository get() = Injector.keywordRepository()

    /** 为新导入的书（或任意 bookId）后台生成关键词；已有则跳过。 */
    fun ensureGenerated(bookId: Long) {
        if (bookId <= 0) return
        scope.launch { generateNow(bookId) }
    }

    private suspend fun generateNow(bookId: Long) {
        try {
            mutex.withLock {
                val existing = keywordRepository.getKeywordsForBookOnce(bookId)
                if (existing.isEmpty()) {
                    val keywords = Injector.keywordExtractor().extract(bookId)
                    if (keywords.isNotEmpty()) {
                        keywordRepository.replaceKeywords(bookId, keywords)
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "OOM during keyword generation for book $bookId")
            System.gc()
        } catch (e: Throwable) {
            Timber.e(e, "keyword generation failed for book $bookId")
        }
    }
}
