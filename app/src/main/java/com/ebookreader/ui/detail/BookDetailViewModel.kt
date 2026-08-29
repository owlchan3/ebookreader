package com.ebookreader.ui.detail

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.network.ApiKeyManager
import com.ebookreader.data.network.DeepSeekClient
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.ChatRepository
import com.ebookreader.domain.repository.TagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BookDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val bookRepository: BookRepository = Injector.bookRepository()
    private val tagRepository: TagRepository = Injector.tagRepository()
    private val apiKeyManager: ApiKeyManager = Injector.apiKeyManager()
    private val chatRepository: ChatRepository = Injector.chatRepository()
    private val deepSeekClient: DeepSeekClient = Injector.deepSeekClient()
    private val decomposeDao = Injector.appDatabase().bookDecompositionDao()

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _allTags = MutableStateFlow<List<Tag>>(emptyList())
    val allTags: StateFlow<List<Tag>> = _allTags.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    private val _relatedBooks = MutableStateFlow<List<Book>>(emptyList())
    val relatedBooks: StateFlow<List<Book>> = _relatedBooks.asStateFlow()

    private val _allBooks = MutableStateFlow<List<Book>>(emptyList())
    val allBooks: StateFlow<List<Book>> = _allBooks.asStateFlow()

    private val _showAddRelatedDialog = MutableStateFlow(false)
    val showAddRelatedDialog: StateFlow<Boolean> = _showAddRelatedDialog.asStateFlow()

    private val _isGeneratingDesc = MutableStateFlow(false)
    val isGeneratingDesc: StateFlow<Boolean> = _isGeneratingDesc.asStateFlow()

    private val _coverMessage = MutableStateFlow<String?>(null)
    val coverMessage: StateFlow<String?> = _coverMessage.asStateFlow()

    /** 本书是否已有拆书（完成或进行中，用于「AI 拆书」按钮直接打开）。 */
    private val _hasDecomposition = MutableStateFlow(false)
    val hasDecomposition: StateFlow<Boolean> = _hasDecomposition.asStateFlow()

    /** 拆书状态：none / generating / done。 */
    private val _decomposeStatus = MutableStateFlow("none")
    val decomposeStatus: StateFlow<String> = _decomposeStatus.asStateFlow()

    /** 已拆书的 bookType（用于直接打开结果页时带参数）。 */
    private val _existingDecomposeBookType = MutableStateFlow("general")
    val existingDecomposeBookType: StateFlow<String> = _existingDecomposeBookType.asStateFlow()

    private var currentBookId: Long = 0

    fun isAiEnabled(): Boolean = apiKeyManager.isEnabled()

    /** Reload the current book from DB (called on screen resume to reflect reader progress). */
    fun reloadBook() {
        if (currentBookId == 0L) return
        viewModelScope.launch {
            _book.value = bookRepository.getBookById(currentBookId)
        }
        refreshDecomposeStatus(currentBookId)
    }

    private fun refreshDecomposeStatus(bookId: Long) {
        viewModelScope.launch {
            val entity = decomposeDao.getByBookId(bookId)
            _hasDecomposition.value = entity != null && (entity.status == "done" || entity.status == "generating")
            _decomposeStatus.value = entity?.status ?: "none"
            if (entity != null && entity.bookType.isNotBlank()) {
                _existingDecomposeBookType.value = entity.bookType
            }
        }
    }

    fun loadBook(bookId: Long) {
        currentBookId = bookId
        reloadBook()
        viewModelScope.launch {
            tagRepository.getTagsForBook(bookId).collect { _tags.value = it }
        }
        viewModelScope.launch {
            tagRepository.getAllTags().collect { _allTags.value = it }
        }
        viewModelScope.launch {
            bookRepository.getRelatedBooks(bookId).collect { _relatedBooks.value = it }
        }
        viewModelScope.launch {
            bookRepository.getAllBooks().collect { _allBooks.value = it }
        }
    }

    fun addTag(tagId: Long) {
        val book = _book.value ?: return
        viewModelScope.launch { tagRepository.addTagToBook(book.id, tagId) }
    }

    fun removeTag(tagId: Long) {
        val book = _book.value ?: return
        viewModelScope.launch { tagRepository.removeTagFromBook(book.id, tagId) }
    }

    fun createAndAddTag(name: String) {
        val book = _book.value ?: return
        viewModelScope.launch {
            val tagId = tagRepository.insertTag(Tag(name = name))
            tagRepository.addTagToBook(book.id, tagId)
        }
    }

    fun deleteBook() {
        val book = _book.value ?: return
        viewModelScope.launch {
            bookRepository.deleteBooks(listOf(book.id))
            _isDeleted.value = true
        }
    }

    fun updateBookInfo(title: String, author: String, description: String) {
        val book = _book.value ?: return
        val oldAuthor = book.author
        val updated = book.copy(title = title, author = author, description = description)
        viewModelScope.launch {
            bookRepository.updateBook(updated)
            _book.value = updated
            // Re-sync auto relations if author changed
            if (author.trim().lowercase() != oldAuthor.trim().lowercase()) {
                bookRepository.syncAutoRelationsForBook(book.id)
            }
        }
    }

    fun generateDescription(onResult: (String) -> Unit) {
        val book = _book.value ?: return
        if (!apiKeyManager.isEnabled()) return
        _isGeneratingDesc.value = true
        viewModelScope.launch {
            try {
                val sample = chatRepository.getBookTextSample(book.id, 1000)
                if (sample.isBlank()) {
                    _isGeneratingDesc.value = false
                    return@launch
                }
                val prompt = "以下是一本书前1000字的内容：\n\n$sample"
                val systemPrompt = "你是一个专业的图书编辑。根据提供的书籍内容片段，提取或推断出一段简洁的书籍简介（100字以内，中文）。只返回简介本身，不要包含任何前缀或解释。"
                val result = deepSeekClient.complete(prompt, systemPrompt)
                result.onSuccess { desc ->
                    onResult(desc)
                }
            } catch (_: Exception) {
                // Silently fail — user can manually edit
            }
            _isGeneratingDesc.value = false
        }
    }

    fun addRelatedBook(relatedBookId: Long) {
        val book = _book.value ?: return
        viewModelScope.launch { bookRepository.addManualRelation(book.id, relatedBookId) }
    }

    fun removeRelatedBook(relatedBookId: Long) {
        val book = _book.value ?: return
        viewModelScope.launch { bookRepository.removeBookRelation(book.id, relatedBookId) }
    }

    fun showAddRelatedDialog() { _showAddRelatedDialog.value = true }
    fun dismissAddRelatedDialog() { _showAddRelatedDialog.value = false }

    fun clearCoverMessage() { _coverMessage.value = null }

    /** 从相册选择新封面，缩放到 400px 后保存到 covers 目录并更新书籍。 */
    fun updateCover(uri: Uri) {
        val book = _book.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val coverDir = File(getApplication<Application>().filesDir, "covers")
                    coverDir.mkdirs()
                    val coverFile = File(coverDir, "book_${book.id}_${System.currentTimeMillis()}.jpg")
                    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: throw Exception("无法读取图片")
                    val maxDim = 400
                    val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                        val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                        Bitmap.createScaledBitmap(
                            bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                            (bitmap.height * ratio).toInt().coerceAtLeast(1), true,
                        )
                    } else bitmap
                    FileOutputStream(coverFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    val updated = book.copy(coverPath = coverFile.absolutePath)
                    bookRepository.updateBook(updated)
                    _book.value = updated
                }
                _coverMessage.value = "封面已更新"
            } catch (e: Exception) {
                _coverMessage.value = "封面更新失败"
            }
        }
    }

    /** 将当前封面保存到系统相册。 */
    fun saveCoverToGallery() {
        val book = _book.value ?: return
        val coverPath = book.coverPath ?: run { _coverMessage.value = "暂无封面"; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val src = File(coverPath)
                    if (!src.exists()) throw Exception("封面文件不存在")
                    val app = getApplication<Application>()
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "cover_${book.title}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                    val resolver = app.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("无法创建相册条目")
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw Exception("无法写入相册")
                }
                _coverMessage.value = "已保存到相册"
            } catch (e: Exception) {
                _coverMessage.value = "保存失败"
            }
        }
    }
}
