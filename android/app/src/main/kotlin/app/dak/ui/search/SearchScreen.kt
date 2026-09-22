package app.dak.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import app.dak.R
import app.dak.core.model.Category
import app.dak.core.model.SimInfo
import app.dak.index.SearchHit
import app.dak.index.SearchSort
import app.dak.index.repo.SavedSearchItem
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.premium.Feature
import app.dak.search.Filter
import app.dak.search.Folder
import app.dak.search.QueryParser
import app.dak.search.SearchQuery
import app.dak.search.Suggestion
import app.dak.ui.common.CategoryChip
import app.dak.ui.common.EmptyState
import app.dak.ui.common.LockChip
import app.dak.ui.common.categoryLabel
import app.dak.ui.common.relativeTime
import app.dak.ui.conversation.MessageTextColors
import app.dak.ui.conversation.annotateMessage
import app.dak.ui.settings.UpgradeSheet
import app.dak.ui.theme.DakTheme
import java.time.ZonedDateTime

/**
 * Search screen (its own back-stack entry): query field with Gmail-style operators, filter chips from
 * `SearchQuery.chips()`, a filter sheet for people who will not type operators, typed-ahead suggestions, saved
 * searches, and results grouped by conversation with matches highlighted. Opening a result pushes the thread with
 * the message highlighted; back returns here with query, results and scroll intact.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    val viewModel: SearchViewModel = hiltViewModel()
    val text by viewModel.text.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val sims by viewModel.sims.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val aiWorking by viewModel.aiWorking.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()

    val (initialIndex, initialOffset) = remember { viewModel.savedScroll }
    val listState = rememberLazyListState(initialIndex, initialOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> viewModel.onScroll(index, offset) }
    }

    var filterSheet by rememberSaveable { mutableStateOf(false) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var upgrade by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (text.isEmpty()) runCatching { focus.requestFocus() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    TextField(
                        value = viewModel.fieldText,
                        onValueChange = { viewModel.onTextChange(it); showSuggestions = true },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.scr_search_hint)) },
                        trailingIcon = {
                            if (text.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onTextChange("") }) {
                                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_clear))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { showSuggestions = false }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                actions = {
                    IconButton(onClick = { filterSheet = true }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.scr_search_filters))
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Outlined.Sort, contentDescription = stringResource(R.string.scr_search_sort))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SearchSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(sortLabel(s)) },
                                    trailingIcon = { if (s == sort) Icon(Icons.Outlined.Check, contentDescription = null) },
                                    onClick = { sortMenu = false; viewModel.setSort(s) },
                                )
                            }
                        }
                    }
                    if (text.isNotBlank()) {
                        IconButton(onClick = { saveDialog = true }) {
                            Icon(Icons.Outlined.BookmarkBorder, contentDescription = stringResource(R.string.scr_search_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (chips.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chips.forEach { chip ->
                        InputChip(
                            selected = true,
                            onClick = { viewModel.removeFilter(chip.filter) },
                            label = { Text(chip.label) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scr_search_remove_filter), modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            AiSearchRow(
                state = aiState,
                working = aiWorking,
                hasText = text.isNotBlank(),
                onAsk = viewModel::askAi,
                onLocked = { upgrade = true },
            )
            HorizontalDivider()
            val typing = showSuggestions && suggestions.isNotEmpty() && text.isNotBlank()
            when {
                typing -> SuggestionList(suggestions) { viewModel.applySuggestion(it) }
                text.isBlank() -> SavedSearchList(
                    saved = saved,
                    onOpen = viewModel::applySaved,
                    onTogglePin = viewModel::togglePinned,
                    onDelete = viewModel::deleteSaved,
                    onClearHistory = viewModel::clearHistory,
                )
                else -> Box(Modifier.fillMaxSize()) {
                    val loading = results.loadState.refresh is LoadState.Loading
                    if (loading && results.itemCount == 0) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else if (!loading && results.itemCount == 0) {
                        EmptyState(icon = Icons.Outlined.Search, title = stringResource(R.string.scr_search_no_results), body = stringResource(R.string.scr_search_no_results_body))
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(count = results.itemCount, contentType = results.itemContentType { "hit" }) { index ->
                            val hit = results[index] ?: return@items
                            SearchResultRow(hit) {
                                viewModel.onResultOpened()
                                if (hit.binId != null) navigator.navigate(Routes.BIN)
                                else navigator.openConversation(hit.conversationId, highlight = hit.message.key.toString())
                            }
                        }
                    }
                }
            }
        }
    }

    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            FilterSheetContent(query = query, sims = sims, onToggle = { filter, active -> if (active) viewModel.removeFilter(filter) else viewModel.addFilter(filter) })
        }
    }
    if (saveDialog) {
        SaveSearchDialog(
            onSave = { name, pinned -> saveDialog = false; viewModel.saveCurrent(name, pinned) },
            onDismiss = { saveDialog = false },
        )
    }
    if (upgrade) UpgradeSheet(feature = Feature.AI_SEARCH, onDismiss = { upgrade = false })
}

@Composable
private fun sortLabel(sort: SearchSort): String = stringResource(
    when (sort) {
        SearchSort.RECENT -> R.string.scr_search_sort_recent
        SearchSort.RELEVANCE -> R.string.scr_search_sort_relevance
        SearchSort.AMOUNT -> R.string.scr_search_sort_amount
    },
)

@Composable
private fun AiSearchRow(state: AiSearchState, working: Boolean, hasText: Boolean, onAsk: () -> Unit, onLocked: () -> Unit) {
    val locked = state == AiSearchState.LOCKED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = locked || (hasText && state == AiSearchState.AVAILABLE)) { if (locked) onLocked() else onAsk() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tint = if (locked) DakTheme.colors.locked else MaterialTheme.colorScheme.primary
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = tint)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.scr_search_ai_title), style = MaterialTheme.typography.bodyMedium, color = if (locked) DakTheme.colors.locked else MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(
                    when (state) {
                        AiSearchState.LOCKED -> R.string.scr_search_ai_locked
                        AiSearchState.AVAILABLE -> R.string.scr_search_ai_available
                        AiSearchState.OFFLINE -> R.string.scr_search_ai_offline
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            locked -> LockChip()
            working -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SuggestionList(suggestions: List<Suggestion>, onPick: (Suggestion) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(suggestions, key = { it.source.name + ":" + it.text }) { s ->
            ListItem(
                modifier = Modifier.clickable { onPick(s) },
                leadingContent = {
                    Icon(
                        when (s.source) {
                            Suggestion.Source.RECENT_QUERY -> Icons.Outlined.History
                            Suggestion.Source.SENDER -> Icons.Outlined.Search
                            Suggestion.Source.CONTACT -> Icons.Outlined.Person
                        },
                        contentDescription = null,
                    )
                },
                headlineContent = { Text(s.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun SavedSearchList(
    saved: List<SavedSearchItem>,
    onOpen: (SavedSearchItem) -> Unit,
    onTogglePin: (SavedSearchItem) -> Unit,
    onDelete: (SavedSearchItem) -> Unit,
    onClearHistory: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.scr_search_saved),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        if (saved.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.scr_search_saved_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(saved, key = { it.id }) { item ->
            ListItem(
                modifier = Modifier.clickable { onOpen(item) },
                headlineContent = { Text(item.name) },
                supportingContent = { Text(item.query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onTogglePin(item) }) {
                            Icon(
                                Icons.Outlined.PushPin,
                                contentDescription = stringResource(if (item.pinned) R.string.scr_search_unpin_folder else R.string.scr_search_pin_folder),
                                tint = if (item.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scr_search_operators_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onClearHistory) { Text(stringResource(R.string.scr_search_clear_history)) }
            }
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, onClick: () -> Unit) {
    val colors = DakTheme.colors
    val textColors = MessageTextColors(
        otpBackground = colors.otpHighlight,
        otpContent = colors.onOtpHighlight,
        highlightBackground = colors.focusHighlight,
        link = MaterialTheme.colorScheme.primary,
    )
    val snippet = remember(hit.message.key, hit.highlights, textColors) {
        annotateMessage(body = hit.message.body, colors = textColors, highlights = hit.highlights)
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = if (hit.binId != null) { { Text(stringResource(R.string.scr_search_in_bin)) } } else null,
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(hit.conversationTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (hit.message.category != Category.UNKNOWN && hit.message.enriched) CategoryChip(hit.message.category)
            }
        },
        supportingContent = {
            Column {
                Text(snippet, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (hit.matchCount > 1) {
                    Text(
                        stringResource(R.string.scr_search_more_matches, hit.matchCount - 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        trailingContent = { Text(relativeTime(hit.message.dateMillis), style = MaterialTheme.typography.labelSmall) },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheetContent(query: SearchQuery, sims: List<SimInfo>, onToggle: (Filter, Boolean) -> Unit) {
    val now = remember { ZonedDateTime.now() }
    fun parsed(op: String): Filter? = QueryParser.parse(op, now).filters.firstOrNull()

    @Composable
    fun Section(title: Int, content: @Composable () -> Unit) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        content()
    }

    @Composable
    fun Toggle(filter: Filter?, label: String) {
        if (filter == null) return
        val active = filter in query.filters
        FilterChip(selected = active, onClick = { onToggle(filter, active) }, label = { Text(label) })
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Section(R.string.scr_filter_category) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Category.entries.filter { it != Category.UNKNOWN }.forEach { c -> Toggle(Filter.CategoryIs(c), categoryLabel(c)) }
            }
        }
        if (sims.size > 1) {
            Section(R.string.scr_filter_sim) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sims.filter { it.slotIndex >= 0 }.forEach { sim ->
                        Toggle(Filter.Sim((sim.slotIndex + 1).toString()), stringResource(R.string.sim_n, (sim.slotIndex + 1).toString()))
                    }
                }
            }
        }
        Section(R.string.scr_filter_has) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle(Filter.HasOtp, stringResource(R.string.scr_filter_has_otp))
                Toggle(Filter.HasLink, stringResource(R.string.scr_filter_has_link))
                Toggle(Filter.HasAttachment, stringResource(R.string.scr_filter_has_attachment))
                Toggle(parsed("amount:>500"), stringResource(R.string.scr_filter_amount_500))
                Toggle(parsed("amount:>5000"), stringResource(R.string.scr_filter_amount_5000))
            }
        }
        Section(R.string.scr_filter_when) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle(parsed("during:today"), stringResource(R.string.scr_filter_today))
                Toggle(parsed("during:yesterday"), stringResource(R.string.scr_filter_yesterday))
                Toggle(parsed("during:\"last week\""), stringResource(R.string.scr_filter_last_week))
                Toggle(parsed("during:\"this month\""), stringResource(R.string.scr_filter_this_month))
                Toggle(parsed("during:\"last month\""), stringResource(R.string.scr_filter_last_month))
            }
        }
        Section(R.string.scr_filter_status) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle(Filter.IsUnread, stringResource(R.string.scr_filter_unread))
                Toggle(Filter.IsStarred, stringResource(R.string.scr_filter_starred))
                Toggle(Filter.InFolder(Folder.ARCHIVE), stringResource(R.string.scr_filter_archive))
                Toggle(Filter.InFolder(Folder.BIN), stringResource(R.string.scr_filter_bin))
            }
        }
    }
}

@Composable
private fun SaveSearchDialog(onSave: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var pinned by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scr_search_save)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.scr_search_save_name)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { pinned = !pinned }) {
                    Checkbox(checked = pinned, onCheckedChange = { pinned = it })
                    Text(stringResource(R.string.scr_search_save_pin))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, pinned) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
