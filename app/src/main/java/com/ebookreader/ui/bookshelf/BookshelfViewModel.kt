package com.ebookreader.ui.bookshelf

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.background.KeywordAutoGenerator
import com.ebookreader.data.importer.BookImporter
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.model.TagGroup
import com.ebookreader.domain.model.TagToken
import com.ebookreader.domain.model.TokenType
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortMode { RECENT, TITLE, AUTHOR, DATE_ADDED }
enum class SearchMode { TEXT, TAG }
enum class TagLogic { AND, OR }

class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val bookRepository: BookRepository = Injector.bookRepository()
    private val tagRepository: TagRepository = Injector.tagRepository()

    private val _sortMode = MutableStateFlow(SortMode.RECENT)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchMode = MutableStateFlow(SearchMode.TEXT)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    private val _batchOpMessage = MutableStateFlow<String?>(null)
    val batchOpMessage: StateFlow<String?> = _batchOpMessage.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /** 导入进度：Pair(已完成数, 总数)；未导入时为 null。 */
    private val _importProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val importProgress: StateFlow<Pair<Int, Int>?> = _importProgress.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _showSearch = MutableStateFlow(false)
    val showSearch: StateFlow<Boolean> = _showSearch.asStateFlow()

    // Token-based tag search state
    private val _tokens = MutableStateFlow<List<TagToken>>(emptyList())
    val tokens: StateFlow<List<TagToken>> = _tokens.asStateFlow()

    private val _cursorPos = MutableStateFlow(0)
    val cursorPos: StateFlow<Int> = _cursorPos.asStateFlow()

    // The search query string is the sole source of truth.
    // In TEXT mode: filtered against title/author.
    // In TAG mode: parsed as tag expression like "(科幻 OR 玄幻) AND 经典".

    val allTags: StateFlow<List<Tag>> = tagRepository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val tagGroups: StateFlow<List<TagGroup>> = tagRepository.getAllTagGroups()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 打了「置顶」标签的书 id 集合，供最近阅读排序置顶用。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pinnedBookIds: StateFlow<Set<Long>> = allTags
        .map { tags -> tags.firstOrNull { it.isPinTag }?.id }
        .distinctUntilChanged()
        .flatMapLatest { pinId ->
            if (pinId == null) flowOf(emptySet<Long>()) else bookRepository.getBookIdsByTag(pinId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val _selectedTagGroupId = MutableStateFlow<Long?>(null)
    val selectedTagGroupId: StateFlow<Long?> = _selectedTagGroupId.asStateFlow()

    val selectedTagIds: StateFlow<Set<Long>> = combine(_searchQuery, allTags) { query, tags ->
        val result = parseTagQueryFull(query, tags)
        result.positiveGroups.flatten().toSet()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val filteredBooksFlow = combine(
        _searchQuery, _sortMode, _searchMode, allTags
    ) { query, sort, mode, tags -> Triple(query, sort, Pair(mode, tags)) }
        .flatMapLatest { (query, _, pair) ->
            val (mode, tags) = pair
            if (mode == SearchMode.TAG && query.isNotBlank()) {
                val result = parseTagQueryFull(query, tags)
                if (result.negativeTagIds.isNotEmpty()) {
                    // Has negation — use the full negation-aware query
                    bookRepository.getBooksByTagGroupsWithNegation(
                        result.positiveGroups, result.negativeTagIds
                    )
                } else if (result.positiveGroups.isNotEmpty()) {
                    val groups = result.positiveGroups
                    if (groups.size == 1 && groups[0].size <= 1) {
                        bookRepository.getBooksByAllTags(groups.flatten(), groups.flatten().size)
                    } else if (groups.size == 1) {
                        bookRepository.getBooksByAnyTags(groups.flatten())
                    } else {
                        bookRepository.getBooksByTagGroups(groups)
                    }
                } else {
                    bookRepository.getAllBooks()
                }
            } else {
                bookRepository.getAllBooks()
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val books: StateFlow<List<Book>> = combine(
        filteredBooksFlow, _searchQuery, _sortMode, _searchMode, pinnedBookIds
    ) { list, query, sort, mode, pinnedIds ->
        // TEXT mode: 按空白分词做多词 AND 搜索，逐词匹配书名/作者/格式；TAG mode: 已按标签过滤
        val filtered = if (mode == SearchMode.TEXT && query.isNotBlank()) {
            val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            list.filter { book ->
                terms.all { term ->
                    book.title.contains(term, ignoreCase = true) ||
                        book.author.contains(term, ignoreCase = true) ||
                        book.format.contains(term, ignoreCase = true)
                }
            }
        } else list
        when (sort) {
            SortMode.RECENT -> {
                val byRecent = filtered.sortedByDescending { it.lastReadTimestamp }
                if (pinnedIds.isEmpty()) byRecent
                else {
                    val (pinned, rest) = byRecent.partition { it.id in pinnedIds }
                    pinned + rest
                }
            }
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.AUTHOR -> filtered.sortedBy { it.author.lowercase() }
            SortMode.DATE_ADDED -> filtered.sortedByDescending { it.addedTimestamp }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val bookImporter = BookImporter(getApplication())

    fun setSortMode(mode: SortMode) { _sortMode.value = mode }
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun toggleSearchMode() {
        val newMode = if (_searchMode.value == SearchMode.TEXT) SearchMode.TAG else SearchMode.TEXT
        _searchMode.value = newMode
        _searchQuery.value = ""
        _tokens.value = emptyList()
        _cursorPos.value = 0
    }

    fun openSearch() { _showSearch.value = true }

    fun closeSearch() {
        _showSearch.value = false
        _searchQuery.value = ""
        _tokens.value = emptyList()
        _cursorPos.value = 0
    }

    private fun tokensToQuery(tokens: List<TagToken>): String =
        tokens.joinToString(" ") { token ->
            when (token.type) {
                TokenType.TAG -> token.text
                TokenType.AND -> "AND"
                TokenType.OR -> "OR"
                TokenType.NO -> "NO"
                TokenType.LPAREN -> "("
                TokenType.RPAREN -> ")"
            }
        }

    private fun computeDepths(tokens: List<TagToken>): List<TagToken> {
        var depth = 0
        return tokens.map { token ->
            when (token.type) {
                TokenType.LPAREN -> {
                    val d = depth
                    depth++
                    token.copy(depth = d)
                }
                TokenType.RPAREN -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    token.copy(depth = depth)
                }
                else -> token.copy(depth = depth)
            }
        }
    }

    private fun syncTokens() {
        val updated = computeDepths(_tokens.value)
        _tokens.value = updated
        _searchQuery.value = tokensToQuery(updated)
    }

    // Tag query builder — inserts token at cursor position
    fun insertTagName(tagName: String) {
        if (_searchMode.value != SearchMode.TAG) return
        val tag = allTags.value.find { it.name.equals(tagName, ignoreCase = true) }
        val token = TagToken(
            type = TokenType.TAG, text = tagName,
            tagId = tag?.id ?: 0, depth = 0,
        )
        insertToken(token)
    }

    fun insertAnd() {
        if (_searchMode.value != SearchMode.TAG) return
        insertToken(TagToken(type = TokenType.AND, text = "AND"))
    }

    fun insertOr() {
        if (_searchMode.value != SearchMode.TAG) return
        insertToken(TagToken(type = TokenType.OR, text = "OR"))
    }

    fun insertNo() {
        if (_searchMode.value != SearchMode.TAG) return
        insertToken(TagToken(type = TokenType.NO, text = "NO"))
    }

    fun insertOpenParen() {
        if (_searchMode.value != SearchMode.TAG) return
        insertToken(TagToken(type = TokenType.LPAREN, text = "("))
    }

    fun insertCloseParen() {
        if (_searchMode.value != SearchMode.TAG) return
        insertToken(TagToken(type = TokenType.RPAREN, text = ")"))
    }

    private fun insertToken(token: TagToken) {
        val list = _tokens.value.toMutableList()
        val pos = _cursorPos.value.coerceIn(0, list.size)
        list.add(pos, token)
        _tokens.value = list
        _cursorPos.value = pos + 1
        syncTokens()
    }

    fun deleteAtCursor() {
        val list = _tokens.value.toMutableList()
        val pos = _cursorPos.value
        if (_searchMode.value == SearchMode.TEXT) {
            // TEXT mode: delete last character from query
            val q = _searchQuery.value
            if (q.isNotEmpty()) _searchQuery.value = q.dropLast(1)
            return
        }
        // TAG mode: delete token before cursor (like backspace)
        if (list.isEmpty() || pos <= 0) return
        list.removeAt(pos - 1)
        _tokens.value = list
        _cursorPos.value = (pos - 1).coerceAtLeast(0)
        syncTokens()
    }

    fun clearTokens() {
        _tokens.value = emptyList()
        _cursorPos.value = 0
        _searchQuery.value = ""
    }

    fun moveCursorLeft() {
        _cursorPos.value = (_cursorPos.value - 1).coerceAtLeast(0)
    }

    fun moveCursorTo(pos: Int) {
        val max = _tokens.value.size
        _cursorPos.value = pos.coerceIn(0, max)
    }

    fun moveCursorRight() {
        val max = _tokens.value.size
        _cursorPos.value = (_cursorPos.value + 1).coerceAtMost(max)
    }

    data class TagQueryResult(
        val positiveGroups: List<List<Long>>,
        val negativeTagIds: Set<Long>,
    )

    companion object {
        // --- Recursive-descent boolean expression parser ---
        // Grammar:  expr = term ("OR" term)*
        //           term = factor ("AND" factor)*
        //           factor = "NO" TAG_NAME | TAG_NAME | "(" expr ")"
        // Output: positive CNF + set of negated tag IDs

        private var pos = 0
        private var input = ""
        private lateinit var tagMap: Map<String, Long>
        private var negatedTags = mutableSetOf<Long>()

        fun parseTagQuery(query: String, tags: List<Tag>): List<List<Long>> {
            val result = parseTagQueryFull(query, tags)
            // Merge negated tags as singleton positive groups for backward compat
            // (Backward compat: return only positive groups for old callers)
            return result.positiveGroups
        }

        fun parseTagQueryFull(query: String, tags: List<Tag>): TagQueryResult {
            if (query.isBlank()) return TagQueryResult(emptyList(), emptySet())
            tagMap = tags.associate { it.name.lowercase() to it.id }
            negatedTags = mutableSetOf()
            input = query.trim()
            pos = 0
            val result = parseExpression()
            val groups = if (result.isEmpty()) emptyList() else result.map { it.toList() }
            return TagQueryResult(groups, negatedTags.toSet())
        }

        private fun peek(): Char? = if (pos < input.length) input[pos] else null
        private fun consume(): Char = input[pos++]
        private fun skipSpaces() { while (peek() == ' ') consume() }

        private fun parseExpression(): MutableList<MutableSet<Long>> {
            // expr = term ("OR" term)*
            var left = parseTerm()
            skipSpaces()
            while (pos < input.length && input.regionMatches(pos, "OR", 0, 2) &&
                (pos + 2 >= input.length || input[pos + 2] == ' ' || input[pos + 2] == '(' || input[pos + 2] == 'N')) {
                pos += 2 // skip "OR"
                skipSpaces()
                val right = parseTerm()
                // OR distributes over AND: cross product
                left = orCnf(left, right)
                skipSpaces()
            }
            return left
        }

        private fun parseTerm(): MutableList<MutableSet<Long>> {
            // term = factor ("AND" factor)*
            var left = parseFactor()
            skipSpaces()
            while (pos < input.length && input.regionMatches(pos, "AND", 0, 3) &&
                (pos + 3 >= input.length || input[pos + 3] == ' ' || input[pos + 3] == '(' || input[pos + 3] == 'N')) {
                pos += 3 // skip "AND"
                skipSpaces()
                val right = parseFactor()
                // AND: concatenate groups
                left.addAll(right)
                skipSpaces()
            }
            return left
        }

        private fun parseFactor(): MutableList<MutableSet<Long>> {
            skipSpaces()
            // Check for "NO" prefix (negation)
            var negated = false
            if (input.regionMatches(pos, "NO", 0, 2) &&
                (pos + 2 >= input.length || input[pos + 2] == ' ' || input[pos + 2] == '(')) {
                pos += 2 // skip "NO"
                skipSpaces()
                negated = true
            }
            if (peek() == '(') {
                consume() // '('
                val inner = parseExpression()
                skipSpaces()
                if (peek() == ')') consume() // ')'
                if (negated) {
                    // Negate entire group: add all tags to exclusion (not well-supported, but handle simply)
                    for (group in inner) negatedTags.addAll(group)
                    return mutableListOf()
                }
                return inner
            }
            // Tag name: read until space, operator keyword, or paren
            val start = pos
            while (pos < input.length) {
                val c = input[pos]
                if (c == ' ' || c == '(' || c == ')') break
                // Check for AND/OR/NO keyword at this position
                if (input.regionMatches(pos, "AND", 0, 3)) {
                    if (pos + 3 >= input.length || input[pos + 3] == ' ' || input[pos + 3] == '(') break
                }
                if (input.regionMatches(pos, "OR", 0, 2)) {
                    if (pos + 2 >= input.length || input[pos + 2] == ' ' || input[pos + 2] == '(') break
                }
                if (input.regionMatches(pos, "NO", 0, 2)) {
                    if (pos + 2 >= input.length || input[pos + 2] == ' ' || input[pos + 2] == '(') break
                }
                pos++
            }
            val name = input.substring(start, pos).trim()
            val tagId = tagMap[name.lowercase()] ?: -1L
            if (negated) {
                if (tagId > 0) negatedTags.add(tagId)
                return mutableListOf()
            }
            return mutableListOf(mutableSetOf(tagId))
        }

        private fun orCnf(
            a: MutableList<MutableSet<Long>>,
            b: MutableList<MutableSet<Long>>,
        ): MutableList<MutableSet<Long>> {
            // (clause_a1 AND clause_a2 AND ...) OR (clause_b1 AND clause_b2 AND ...)
            // = (clause_a1 OR clause_b1) AND (clause_a1 OR clause_b2) AND (clause_a2 OR clause_b1) AND ...
            // Each clause is an OR-set already; merging two OR-sets = union of their tags
            val result = mutableListOf<MutableSet<Long>>()
            for (aGroup in a) {
                for (bGroup in b) {
                    result.add((aGroup + bGroup).toMutableSet())
                }
            }
            return result
        }
    }

    // Tag groups (bookshelves)
    fun selectTagGroup(group: TagGroup) {
        _selectedTagGroupId.value = group.id
        _searchMode.value = SearchMode.TAG
        val newTokens = if (group.query.isNotBlank()) {
            queryToTokens(group.query)
        } else {
            // 旧数据（无 query）：用 logicType + tags 重建
            val joinerType = if (group.logicType == "AND") TokenType.AND else TokenType.OR
            val list = mutableListOf<TagToken>()
            if (group.tags.size > 1) list.add(TagToken(type = TokenType.LPAREN, text = "("))
            group.tags.forEachIndexed { i, tag ->
                list.add(TagToken(type = TokenType.TAG, text = tag.name, tagId = tag.id))
                if (i < group.tags.size - 1) list.add(TagToken(type = joinerType, text = if (joinerType == TokenType.AND) "AND" else "OR"))
            }
            if (group.tags.size > 1) list.add(TagToken(type = TokenType.RPAREN, text = ")"))
            list
        }
        _tokens.value = newTokens
        _cursorPos.value = newTokens.size
        _searchQuery.value = tokensToQuery(newTokens)
    }

    /** 把保存的查询串解析回 token 序列（保存书架时按空格连接）。 */
    private fun queryToTokens(query: String): List<TagToken> {
        val tokens = mutableListOf<TagToken>()
        for (word in query.trim().split(Regex("\\s+"))) {
            if (word.isBlank()) continue
            when (word) {
                "(" -> tokens.add(TagToken(TokenType.LPAREN, "("))
                ")" -> tokens.add(TagToken(TokenType.RPAREN, ")"))
                "AND" -> tokens.add(TagToken(TokenType.AND, "AND"))
                "OR" -> tokens.add(TagToken(TokenType.OR, "OR"))
                "NO" -> tokens.add(TagToken(TokenType.NO, "NO"))
                else -> {
                    val tag = allTags.value.find { it.name == word }
                    tokens.add(TagToken(TokenType.TAG, word, tag?.id ?: 0))
                }
            }
        }
        return computeDepths(tokens)
    }

    fun clearTagGroup() {
        _selectedTagGroupId.value = null
        _searchQuery.value = ""
        _tokens.value = emptyList()
        _cursorPos.value = 0
    }

    fun saveCurrentFilterAsGroup(name: String) {
        val currentTokens = _tokens.value
        if (currentTokens.isEmpty()) return
        val tagTokens = currentTokens.filter { it.type == TokenType.TAG }
        if (tagTokens.isEmpty()) return
        // Determine logicType: NO > AND > OR priority
        val hasNo = currentTokens.any { it.type == TokenType.NO }
        val hasAnd = currentTokens.any { it.type == TokenType.AND }
        val logicType = when {
            hasNo -> "NO"
            hasAnd -> "AND"
            else -> "OR"
        }
        viewModelScope.launch {
            val selectedTags = allTags.value.filter { t -> tagTokens.any { it.tagId == t.id || it.text.equals(t.name, ignoreCase = true) } }
            tagRepository.insertTagGroup(
                TagGroup(name = name, logicType = logicType, isTab = true, tags = selectedTags, query = _searchQuery.value)
            )
            _batchOpMessage.value = "已保存书架: $name"
        }
    }

    fun deleteTagGroup(group: TagGroup) {
        viewModelScope.launch {
            tagRepository.deleteTagGroup(group)
            if (_selectedTagGroupId.value == group.id) clearTagGroup()
        }
    }

    // Selection
    fun toggleSelection(bookId: Long) {
        _selectedIds.value = _selectedIds.value.let {
            if (bookId in it) it - bookId else it + bookId
        }.also { if (it.isEmpty()) _isSelectionMode.value = false }
    }

    fun enterSelectionMode(bookId: Long) {
        _isSelectionMode.value = true
        _selectedIds.value = setOf(bookId)
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        _selectedIds.value = books.value.map { it.id }.toSet()
    }

    fun deleteSelected() {
        viewModelScope.launch {
            bookRepository.deleteBooks(_selectedIds.value.toList())
            exitSelectionMode()
        }
    }

    // Batch tag management
    fun batchAddTag(tagId: Long) {
        viewModelScope.launch {
            val tag = allTags.value.find { it.id == tagId }
            for (bookId in _selectedIds.value) {
                tagRepository.addTagToBook(bookId, tagId)
            }
            _batchOpMessage.value = "已为 ${_selectedIds.value.size} 本书添加标签: ${tag?.name ?: ""}"
        }
    }

    fun batchRemoveTag(tagId: Long) {
        viewModelScope.launch {
            val tag = allTags.value.find { it.id == tagId }
            for (bookId in _selectedIds.value) {
                tagRepository.removeTagFromBook(bookId, tagId)
            }
            _batchOpMessage.value = "已为 ${_selectedIds.value.size} 本书移除标签: ${tag?.name ?: ""}"
        }
    }

    /** 多选「关联」：把选中的书两两加入彼此的「相关书籍」（手动关联）。 */
    fun associateSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.size < 2) return
        viewModelScope.launch {
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    bookRepository.addManualRelation(ids[i], ids[j])
                }
            }
            _batchOpMessage.value = "已将 ${ids.size} 本书互相关联"
            exitSelectionMode()
        }
    }

    // Import
    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            val resolver = getApplication<Application>().contentResolver
            val fileName = resolveDisplayName(resolver, uri)
            val baseName = fileName.substringBeforeLast('.')
            if (baseName.isNotEmpty() && bookRepository.countBooksByBaseName(baseName) > 0) {
                _importError.value = "该书已存在于书架中"
                _isImporting.value = false
                return@launch
            }
            bookImporter.importFromUri(uri).fold(
                onSuccess = { imported ->
                    val newId = bookRepository.insertBook(imported.book)
                    bookRepository.syncAutoRelationsForBook(newId)
                    KeywordAutoGenerator.ensureGenerated(newId)
                },
                onFailure = { _importError.value = it.message ?: "导入失败" },
            )
            _isImporting.value = false
        }
    }

    fun importBooks(uris: List<Uri>) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            var successCount = 0
            var failCount = 0
            var dupCount = 0
            var firstError: String? = null
            val seenInBatch = mutableSetOf<String>()
            val resolver = getApplication<Application>().contentResolver
            val total = uris.size
            for ((index, uri) in uris.withIndex()) {
                _importProgress.value = index + 1 to total
                // Resolve actual display name from content URI (not lastPathSegment which may be opaque)
                val fileName = resolveDisplayName(resolver, uri)
                val baseName = fileName.substringBeforeLast('.')
                // 用去扩展名的名称检测重复，兼容 TXT/DOCX 导入后转成 EPUB 的情况
                val isDuplicate = (baseName.isNotEmpty() && bookRepository.countBooksByBaseName(baseName) > 0) ||
                    (baseName.isNotEmpty() && !seenInBatch.add(baseName))
                if (isDuplicate) {
                    dupCount++
                    continue
                }
                bookImporter.importFromUri(uri).fold(
                    onSuccess = { imported ->
                        val newId = bookRepository.insertBook(imported.book)
                        bookRepository.syncAutoRelationsForBook(newId)
                        KeywordAutoGenerator.ensureGenerated(newId)
                        successCount++
                    },
                    onFailure = { e ->
                        failCount++
                        if (firstError == null) {
                            firstError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        }
                    },
                )
            }
            _importProgress.value = null
            _isImporting.value = false
            val msg = buildString {
                if (successCount > 0) append("成功导入 $successCount 本书")
                if (dupCount > 0) {
                    if (successCount > 0) append("，")
                    append("$dupCount 本已存在")
                }
                if (failCount > 0) {
                    if (successCount > 0 || dupCount > 0) append("，")
                    append("$failCount 本失败")
                    if (firstError != null) append("：$firstError")
                }
            }
            if (msg.isNotEmpty()) _batchOpMessage.value = msg
        }
    }

    private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else ""
                } else ""
            } ?: (uri.lastPathSegment ?: "")
        } catch (_: Exception) {
            uri.lastPathSegment ?: ""
        }
    }

    fun createAndBatchAddTag(name: String) {
        viewModelScope.launch {
            val tagId = tagRepository.insertTag(Tag(name = name))
            for (bookId in _selectedIds.value) {
                tagRepository.addTagToBook(bookId, tagId)
            }
        }
    }

    fun clearImportError() { _importError.value = null }
    fun clearBatchMessage() { _batchOpMessage.value = null }
}
