package app.dak.ui.search

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.dak.core.model.SimInfo
import app.dak.index.SearchHit
import app.dak.index.SearchSort
import app.dak.index.repo.SavedSearchItem
import app.dak.index.repo.SavedSearchRepository
import app.dak.index.repo.SearchRepository
import app.dak.navigation.Routes
import app.dak.premium.Entitlements
import app.dak.premium.Feature
import app.dak.premium.QueryUnderstanding
import app.dak.search.Chip
import app.dak.search.Filter
import app.dak.search.QueryParser
import app.dak.search.SearchQuery
import app.dak.search.Suggestion
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import javax.inject.Inject

/** State of the premium natural-language search entry. */
enum class AiSearchState { LOCKED, AVAILABLE, OFFLINE }

/**
 * Search is a screen in the back stack, not a mode: the query text, sort and scroll position live in this
 * back-stack entry's [SavedStateHandle], so returning from a result restores the same results at the same place
 * with the query intact (even after process death). Filters are part of the query text (Gmail-style operators),
 * so chips and the filter sheet edit the text through [SearchQuery.withFilter] / [SearchQuery.withoutFilter].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val search: SearchRepository,
    private val savedSearches: SavedSearchRepository,
    private val entitlements: Entitlements,
    private val understanding: QueryUnderstanding,
    sims: SimRepository,
) : ViewModel() {

    init {
        if (!savedState.contains(KEY_TEXT)) {
            savedState[KEY_TEXT] = savedState.get<String>(Routes.ARG_Q)?.let(Uri::decode).orEmpty()
        }
    }

    val text: StateFlow<String> = savedState.getStateFlow(KEY_TEXT, "")

    val sort: StateFlow<SearchSort> = savedState.getStateFlow(KEY_SORT, SearchSort.RECENT.name)
        .map { name -> SearchSort.entries.firstOrNull { it.name == name } ?: SearchSort.RECENT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchSort.RECENT)

    /** The parsed query (re-parsed on every edit; parsing is total and cheap). */
    val query: StateFlow<SearchQuery> = text.map { parse(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, parse(text.value))

    val chips: StateFlow<List<Chip>> = query.map { it.chips() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Paged results, grouped by conversation (one hit per conversation with its best message). */
    val results: Flow<PagingData<SearchHit>> = combine(text.debounce(250).distinctUntilChanged(), sort) { t, s -> t to s }
        .flatMapLatest { (t, s) ->
            val q = parse(t)
            if (t.isBlank() || (q.textExpr == null && q.filters.isEmpty())) flowOf(PagingData.empty<SearchHit>()) else search.search(q, s)
        }
        .cachedIn(viewModelScope)

    private val suggestionPrefix = MutableStateFlow("")
    val suggestions: StateFlow<List<Suggestion>> = suggestionPrefix.debounce(120).mapLatest { prefix ->
        if (prefix.isBlank()) emptyList() else runCatching { search.suggestions(prefix) }.getOrDefault(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val saved: StateFlow<List<SavedSearchItem>> = savedSearches.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sims: StateFlow<List<SimInfo>> = sims.sims

    val aiState: StateFlow<AiSearchState> = entitlements.granted.map { granted ->
        when {
            Feature.AI_SEARCH !in granted -> AiSearchState.LOCKED
            understanding.isAvailable -> AiSearchState.AVAILABLE
            else -> AiSearchState.OFFLINE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), if (entitlements.has(Feature.AI_SEARCH)) AiSearchState.AVAILABLE else AiSearchState.LOCKED)

    private val aiBusy = MutableStateFlow(false)
    val aiWorking: StateFlow<Boolean> = aiBusy.asStateFlow()

    /** First visible result and its offset, restored when coming back from a thread. */
    val savedScroll: Pair<Int, Int> get() = (savedState.get<Int>(KEY_SCROLL_INDEX) ?: 0) to (savedState.get<Int>(KEY_SCROLL_OFFSET) ?: 0)

    fun onTextChange(value: String) {
        savedState[KEY_TEXT] = value
        resetScroll()
        suggestionPrefix.value = value.substringAfterLast(' ')
    }

    fun setSort(sort: SearchSort) {
        savedState[KEY_SORT] = sort.name
        resetScroll()
    }

    fun addFilter(filter: Filter) = setText(query.value.withFilter(filter).toQueryString())

    fun removeFilter(filter: Filter) = setText(query.value.withoutFilter(filter).toQueryString())

    /** Replaces the word being typed with [suggestion]. */
    fun applySuggestion(suggestion: Suggestion) {
        val current = text.value
        val head = current.substringBeforeLast(' ', missingDelimiterValue = "")
        val value = if (suggestion.text.contains(' ')) "\"${suggestion.text}\"" else suggestion.text
        setText((if (head.isEmpty()) value else "$head $value") + " ")
        suggestionPrefix.value = ""
    }

    fun applySaved(item: SavedSearchItem) {
        setText(item.query)
        setSort(item.sort)
    }

    fun saveCurrent(name: String, pinned: Boolean) {
        val q = text.value.trim()
        if (q.isEmpty() || name.isBlank()) return
        viewModelScope.launch { savedSearches.save(name.trim(), q, sort.value, pinned) }
    }

    fun togglePinned(item: SavedSearchItem) {
        viewModelScope.launch { savedSearches.setPinned(item.id, !item.pinned) }
    }

    fun deleteSaved(item: SavedSearchItem) {
        viewModelScope.launch { savedSearches.delete(item.id) }
    }

    /** Records the query in history when the user opens a result (typed-ahead suggestions learn from it). */
    fun onResultOpened() {
        val q = text.value.trim()
        if (q.isNotEmpty()) viewModelScope.launch { runCatching { search.recordQuery(q) } }
    }

    fun onScroll(index: Int, offset: Int) {
        savedState[KEY_SCROLL_INDEX] = index
        savedState[KEY_SCROLL_OFFSET] = offset
    }

    /**
     * Premium: turns the free text into structured filters via [QueryUnderstanding] (only the query text is sent,
     * never message content). The user sees and can edit the chips it produced.
     */
    fun askAi() {
        if (aiState.value != AiSearchState.AVAILABLE || aiBusy.value) return
        val natural = text.value.trim()
        if (natural.isEmpty()) return
        aiBusy.value = true
        viewModelScope.launch {
            val structured = runCatching { understanding.toStructuredQuery(natural) }.getOrNull()
            aiBusy.value = false
            if (!structured.isNullOrBlank()) setText(structured)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { runCatching { search.clearHistory() } }
    }

    private fun setText(value: String) {
        savedState[KEY_TEXT] = value
        resetScroll()
    }

    private fun resetScroll() {
        savedState[KEY_SCROLL_INDEX] = 0
        savedState[KEY_SCROLL_OFFSET] = 0
    }

    private fun parse(text: String): SearchQuery = QueryParser.parse(text, ZonedDateTime.now())

    private companion object {
        const val KEY_TEXT = "search.text"
        const val KEY_SORT = "search.sort"
        const val KEY_SCROLL_INDEX = "search.scrollIndex"
        const val KEY_SCROLL_OFFSET = "search.scrollOffset"
    }
}
