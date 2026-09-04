package com.remmi.browser.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.remmi.browser.storage.BookmarkDao
import com.remmi.browser.storage.BookmarkItem
import com.remmi.browser.storage.HistoryDao
import com.remmi.browser.storage.HistoryItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Calendar

enum class DateFilter(val label: String) {
    ALL("All Time"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 Days"),
    CUSTOM("Custom")
}

enum class BookmarkSort(val label: String) {
    DATE_DESC("Date Added (Newest)"),
    DATE_ASC("Date Added (Oldest)"),
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)")
}

data class HistoryStats(
    val totalVisits: Int = 0,
    val uniqueDomains: Int = 0,
    val todayVisits: Int = 0
)

class ActivityViewModel(
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao
) : ViewModel() {

    private val defaultFolders = listOf("General", "Work", "Personal", "Social", "News", "Shopping")

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _dateFilter = MutableStateFlow(DateFilter.ALL)
    val dateFilter: StateFlow<DateFilter> = _dateFilter.asStateFlow()

    private val _customDateRange = MutableStateFlow<Pair<Long, Long>?>(null)
    val customDateRange: StateFlow<Pair<Long, Long>?> = _customDateRange.asStateFlow()

    private val _selectedFolder = MutableStateFlow("All")
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    private val _bookmarkSort = MutableStateFlow(BookmarkSort.DATE_DESC)
    val bookmarkSort: StateFlow<BookmarkSort> = _bookmarkSort.asStateFlow()

    private val _customFolders = MutableStateFlow<List<String>>(emptyList())

    // Selection mode state
    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedHistoryIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedHistoryIds: StateFlow<Set<Long>> = _selectedHistoryIds.asStateFlow()

    private val _selectedBookmarkIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedBookmarkIds: StateFlow<Set<Long>> = _selectedBookmarkIds.asStateFlow()

    val allBookmarks: StateFlow<List<BookmarkItem>> = bookmarkDao.getAllBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFolders: StateFlow<List<String>> = combine(
        allBookmarks,
        _customFolders
    ) { bookmarks, custom ->
        val dbCategories = bookmarks.map { it.category }.filter { it.isNotBlank() }
        val union = (defaultFolders + custom + dbCategories).distinct()
        listOf("All") + union
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("All") + defaultFolders)

    val folderCounts: StateFlow<Map<String, Int>> = allBookmarks.map { bookmarks ->
        val counts = mutableMapOf<String, Int>()
        counts["All"] = bookmarks.size
        for (b in bookmarks) {
            val cat = b.category.ifBlank { "General" }
            counts[cat] = (counts[cat] ?: 0) + 1
        }
        counts
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), mapOf("All" to 0))

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateDateFilter(filter: DateFilter, customRange: Pair<Long, Long>? = null) {
        _dateFilter.value = filter
        if (customRange != null) {
            _customDateRange.value = customRange
        }
    }

    fun updateFolder(folder: String) {
        _selectedFolder.value = folder
    }

    fun updateBookmarkSort(sort: BookmarkSort) {
        _bookmarkSort.value = sort
    }

    fun addCustomFolder(folderName: String) {
        val trimmed = folderName.trim()
        if (trimmed.isNotBlank() && !_customFolders.value.contains(trimmed)) {
            _customFolders.value = _customFolders.value + trimmed
            _selectedFolder.value = trimmed
        }
    }

    // Multi-select management
    fun setSelectionMode(enabled: Boolean) {
        _isSelectionMode.value = enabled
        if (!enabled) {
            _selectedHistoryIds.value = emptySet()
            _selectedBookmarkIds.value = emptySet()
        }
    }

    fun toggleHistorySelection(id: Long) {
        val current = _selectedHistoryIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _selectedHistoryIds.value = current
    }

    fun toggleBookmarkSelection(id: Long) {
        val current = _selectedBookmarkIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _selectedBookmarkIds.value = current
    }

    fun selectAllHistory(ids: List<Long>) {
        _selectedHistoryIds.value = ids.toSet()
    }

    fun selectAllBookmarks(ids: List<Long>) {
        _selectedBookmarkIds.value = ids.toSet()
    }

    fun clearSelection() {
        _selectedHistoryIds.value = emptySet()
        _selectedBookmarkIds.value = emptySet()
        _isSelectionMode.value = false
    }

    @OptIn(FlowPreview::class)
    val historyList: StateFlow<List<HistoryItem>> = combine(
        _searchQuery.debounce(250),
        _dateFilter,
        _customDateRange
    ) { query, filter, customRange ->
        Triple(query, filter, customRange)
    }.flatMapLatest { (query, filter, customRange) ->
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val (minTime, maxTime) = when (filter) {
            DateFilter.TODAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                Pair(calendar.timeInMillis, now)
            }
            DateFilter.YESTERDAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfToday = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                Pair(calendar.timeInMillis, startOfToday - 1)
            }
            DateFilter.LAST_7_DAYS -> {
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                Pair(calendar.timeInMillis, now)
            }
            DateFilter.CUSTOM -> {
                customRange ?: Pair(0L, Long.MAX_VALUE)
            }
            DateFilter.ALL -> Pair(0L, Long.MAX_VALUE)
        }
        historyDao.getFilteredHistory(query.trim(), minTime, maxTime)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyStats: StateFlow<HistoryStats> = historyList.map { list ->
        val total = list.size
        val unique = list.mapNotNull {
            try {
                val host = URI(it.url).host
                host?.removePrefix("www.")
            } catch (_: Exception) {
                null
            }
        }.distinct().size
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = cal.timeInMillis
        val todayCount = list.count { it.timestamp >= startOfToday }
        HistoryStats(totalVisits = total, uniqueDomains = unique, todayVisits = todayCount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryStats())

    @OptIn(FlowPreview::class)
    val bookmarksList: StateFlow<List<BookmarkItem>> = combine(
        allBookmarks,
        _searchQuery.debounce(250),
        _selectedFolder,
        _bookmarkSort
    ) { all, query, folder, sort ->
        var list = all
        if (folder != "All") {
            list = list.filter { it.category.equals(folder, ignoreCase = true) }
        }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
        }
        when (sort) {
            BookmarkSort.DATE_DESC -> list.sortedByDescending { it.timestamp }
            BookmarkSort.DATE_ASC -> list.sortedBy { it.timestamp }
            BookmarkSort.NAME_ASC -> list.sortedBy { it.title.ifBlank { it.url }.lowercase() }
            BookmarkSort.NAME_DESC -> list.sortedByDescending { it.title.ifBlank { it.url }.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearHistory() {
        viewModelScope.launch {
            historyDao.clearHistory()
            _selectedHistoryIds.value = emptySet()
        }
    }

    fun deleteHistory(item: HistoryItem) {
        viewModelScope.launch {
            historyDao.delete(item)
            _selectedHistoryIds.value = _selectedHistoryIds.value - item.id
        }
    }

    fun deleteSelectedHistory() {
        viewModelScope.launch {
            val ids = _selectedHistoryIds.value
            val currentItems = historyList.value.filter { it.id in ids }
            historyDao.deleteAll(currentItems)
            _selectedHistoryIds.value = emptySet()
            if (_selectedHistoryIds.value.isEmpty()) {
                _isSelectionMode.value = false
            }
        }
    }

    fun deleteBookmark(item: BookmarkItem) {
        viewModelScope.launch {
            bookmarkDao.delete(item)
            _selectedBookmarkIds.value = _selectedBookmarkIds.value - item.id
        }
    }

    fun deleteSelectedBookmarks() {
        viewModelScope.launch {
            val ids = _selectedBookmarkIds.value
            val currentItems = allBookmarks.value.filter { it.id in ids }
            bookmarkDao.deleteAll(currentItems)
            _selectedBookmarkIds.value = emptySet()
            if (_selectedBookmarkIds.value.isEmpty()) {
                _isSelectionMode.value = false
            }
        }
    }

    fun addBookmark(title: String, url: String, category: String = "General") {
        viewModelScope.launch {
            val effectiveCategory = if (category == "All" || category.isBlank()) {
                if (_selectedFolder.value != "All") _selectedFolder.value else "General"
            } else category
            bookmarkDao.insert(BookmarkItem(url = url, title = title.ifBlank { url }, category = effectiveCategory))
        }
    }

    fun updateBookmark(id: Long, title: String, url: String, category: String) {
        viewModelScope.launch {
            val current = allBookmarks.value.find { it.id == id }
            if (current != null) {
                bookmarkDao.insert(current.copy(title = title, url = url, category = category))
            }
        }
    }

    fun moveBookmark(item: BookmarkItem, newFolder: String) {
        viewModelScope.launch {
            bookmarkDao.insert(item.copy(category = newFolder))
        }
    }

    fun importBookmarksJson(jsonString: String): Int {
        var count = 0
        try {
            val array = JSONArray(jsonString)
            val items = mutableListOf<BookmarkItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val title = obj.optString("title", "Bookmark")
                val url = obj.optString("url", "")
                val cat = obj.optString("category", "General")
                if (url.isNotBlank()) {
                    items.add(BookmarkItem(title = title, url = url, category = cat))
                    count++
                }
            }
            if (items.isNotEmpty()) {
                viewModelScope.launch {
                    bookmarkDao.insertAll(items)
                }
            }
        } catch (_: Exception) {}
        return count
    }

    fun exportBookmarksJson(): String {
        val array = JSONArray()
        for (b in allBookmarks.value) {
            val obj = JSONObject().apply {
                put("title", b.title)
                put("url", b.url)
                put("category", b.category)
                put("timestamp", b.timestamp)
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    fun exportHistoryJson(): String {
        val array = JSONArray()
        for (h in historyList.value) {
            val obj = JSONObject().apply {
                put("title", h.title)
                put("url", h.url)
                put("timestamp", h.timestamp)
            }
            array.put(obj)
        }
        return array.toString(2)
    }
}

class ActivityViewModelFactory(
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ActivityViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ActivityViewModel(historyDao, bookmarkDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
