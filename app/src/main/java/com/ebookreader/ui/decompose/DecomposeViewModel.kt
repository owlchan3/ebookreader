package com.ebookreader.ui.decompose

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/** 拆书 ViewModel：转发到进程级 [DecomposeEngine]，并在开始拆书时启动前台服务以支持后台。 */
class DecomposeViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<DecomposeState> = DecomposeEngine.state

    fun bookTypeLabel(type: String): String = DecomposeEngine.bookTypeLabel(type)

    fun load(bookId: Long) = DecomposeEngine.load(bookId)

    fun start(bookId: Long, bookType: String) {
        DecomposeEngine.start(bookId, bookType)
        startService(bookId, bookType)
    }

    fun answerRetry(retry: Boolean) = DecomposeEngine.answerRetry(retry)

    fun cancel() = DecomposeEngine.cancel()

    fun delete(bookId: Long, onDeleted: () -> Unit) = DecomposeEngine.delete(bookId, onDeleted)

    fun deleteProgressAndRestart(bookId: Long, bookType: String) {
        DecomposeEngine.deleteProgressAndRestart(bookId, bookType)
        startService(bookId, bookType)
    }

    private fun startService(bookId: Long, bookType: String) {
        try {
            val ctx = getApplication<Application>()
            val intent = android.content.Intent(ctx, DecomposeService::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_BOOK_TYPE, bookType)
            }
            ContextCompat.startForegroundService(ctx, intent)
        } catch (_: Exception) { }
    }

    companion object {
        const val EXTRA_BOOK_ID = "decompose_book_id"
        const val EXTRA_BOOK_TYPE = "decompose_book_type"
    }
}
