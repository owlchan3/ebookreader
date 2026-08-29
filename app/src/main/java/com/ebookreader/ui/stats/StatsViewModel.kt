package com.ebookreader.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.local.entity.DailyReadingSessionEntity
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val bookRepository = Injector.bookRepository()
    private val sessionDao = Injector.appDatabase().dailyReadingSessionDao()
    private val tagRepository = Injector.tagRepository()

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    /** 被标记为「已阅」的书籍 id 集合。 */
    private val _readBookIds = MutableStateFlow<Set<Long>>(emptySet())
    val readBookIds: StateFlow<Set<Long>> = _readBookIds.asStateFlow()

    private val _dailySessions = MutableStateFlow<List<DailyReadingSessionEntity>>(emptyList())
    val dailySessions: StateFlow<List<DailyReadingSessionEntity>> = _dailySessions.asStateFlow()

    private val _totalReadingSeconds = MutableStateFlow(0L)
    val totalReadingSeconds: StateFlow<Long> = _totalReadingSeconds.asStateFlow()

    /** 选中的某一天，及其「阅读最久的三本书」。 */
    private val _selectedDay = MutableStateFlow<String?>(null)
    val selectedDay: StateFlow<String?> = _selectedDay.asStateFlow()

    private val _selectedDayTopBooks = MutableStateFlow<List<TopBookInfo>>(emptyList())
    val selectedDayTopBooks: StateFlow<List<TopBookInfo>> = _selectedDayTopBooks.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            bookRepository.getAllBooks().collect { list ->
                _books.value = list
                _loading.value = false
            }
        }
        viewModelScope.launch {
            loadDailySessions()
        }
        viewModelScope.launch {
            loadReadBookIds()
        }
    }

    private suspend fun loadReadBookIds() {
        try {
            val readTagId = tagRepository.ensureReadTagExists()
            val allBookTags = tagRepository.getAllBookTags()
            _readBookIds.value = allBookTags
                .filterValues { tags -> tags.any { it.id == readTagId } }
                .keys
        } catch (_: Exception) {
            _readBookIds.value = emptySet()
        }
    }

    private suspend fun loadDailySessions() {
        // Load last 7 days of reading sessions
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -6)
        val fromDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(cal.time)
        _dailySessions.value = sessionDao.getSessionsSince(fromDate)
        // 总阅读时长改用 daily_reading_sessions 全表求和（删书不丢时长），与「周」同源
        _totalReadingSeconds.value = sessionDao.getTotalReadingSeconds()
    }

    /** 选中某一天，加载当天阅读最久的三本书。 */
    fun selectDay(date: String?) {
        _selectedDay.value = date
        if (date == null) {
            _selectedDayTopBooks.value = emptyList()
            return
        }
        viewModelScope.launch {
            val readings = Injector.appDatabase().dailyBookReadingDao().getByDate(date)
            val titles = _books.value.associate { it.id to it.title }
            _selectedDayTopBooks.value = readings.mapNotNull { r ->
                titles[r.bookId]?.let { TopBookInfo(it, r.seconds) }
            }.take(3)
        }
    }
}

/** 某本书在某个时间段的阅读时长（用于详情展示）。 */
data class TopBookInfo(val title: String, val seconds: Long)
