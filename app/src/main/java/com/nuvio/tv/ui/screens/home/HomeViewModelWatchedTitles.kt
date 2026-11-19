package com.nuvio.tv.ui.screens.home

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.WatchedTitlesMode
import com.nuvio.tv.domain.model.legacyKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

private const val WATCHED_LISTS_WAIT_MS = 1_500L
private const val BUILT_IN_ROWS_SETTLE_MS = 500L

internal class HomeWatchedTitlesState {
    val filter = MutableStateFlow<HomeWatchedFilter?>(null)
    val listed = MutableStateFlow<HomeListedFilter?>(null)
    val refillPages = ConcurrentHashMap<String, Int>()

    @Volatile
    var homeShown = false

    /** Row that still shows watched titles because it had focus when they became watched. */
    @Volatile
    var heldRowKey: String? = null

    @Volatile
    var waitDeadlineMs = 0L

    var idsJob: Job? = null
}

internal fun HomeViewModel.observeWatchedTitlesPipeline() {
    watchedTitles.idsJob?.cancel()
    watchedTitles.idsJob = null
    watchedTitles.filter.value = HomeWatchedFilter(WatchedTitlesMode.SHOW)
    _uiState.update { state ->
        if (state.watchedTitlesMode == WatchedTitlesMode.SHOW) state else state.copy(watchedTitlesMode = WatchedTitlesMode.SHOW)
    }
}

/** Plan-to-watch and Watching list hiding has been removed; keep rows unfiltered. */
internal fun HomeViewModel.observeListedTitlesPipeline() {
    watchedTitles.listed.value = HomeListedFilter(hidePlan = false, hideWatching = false)
}

/** Reads list rows again when their titles change, while at least one of them is switched on. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun HomeViewModel.observeBuiltInRowChangesPipeline() {
    viewModelScope.launch {
        combine(builtInCatalogs.addons, layoutPreferenceDataStore.disabledHomeCatalogKeys) { addons, _ -> addons }
            .debounce(BUILT_IN_ROWS_SETTLE_MS)
            .map { addons -> addons.filter { addon -> addon.enabledHomeCatalogs(this@observeBuiltInRowChangesPipeline).isNotEmpty() } }
            .distinctUntilChanged()
            .flatMapLatest { addons ->
                addons.map { addon -> builtInCatalogs.rowChanges(addon.id).map { addon } }.merge()
            }
            .collect { addon ->
                addon.enabledHomeCatalogs(this@observeBuiltInRowChangesPipeline).forEach { catalog ->
                    loadCatalogPipeline(addon, catalog, catalogLoadGeneration, isRefresh = true, forceReplace = true)
                }
            }
    }
}

private fun Addon.enabledHomeCatalogs(viewModel: HomeViewModel): List<CatalogDescriptor> = catalogs.filter { catalog ->
    catalog.shouldShowOnHome() && !viewModel.isCatalogDisabled(
        addonBaseUrl = baseUrl,
        addonId = id,
        type = catalog.apiType,
        catalogId = catalog.id,
        catalogName = catalog.name
    )
}

private fun HomeViewModel.scheduleWatchedRowsUpdate() {
    if (hasAnyCatalogRows()) scheduleUpdateCatalogRows()
}

/**
 * The first rows wait briefly for the saved watched lists, so a title is not shown and then pulled
 * away a moment later. If the lists take longer, the rows show in full and are filtered when they land.
 */
internal suspend fun HomeViewModel.awaitWatchedFilterPipeline(): HomeWatchedFilter? {
    val state = watchedTitles.filter
    val ready = { filter: HomeWatchedFilter? ->
        filter != null && (filter.mode != WatchedTitlesMode.HIDE || filter.ids != null)
    }
    if (ready(state.value) && watchedTitles.listed.value?.ready == true) return state.value
    val now = SystemClock.elapsedRealtime()
    if (watchedTitles.waitDeadlineMs == 0L) {
        watchedTitles.waitDeadlineMs = now + WATCHED_LISTS_WAIT_MS
    }
    val remaining = watchedTitles.waitDeadlineMs - now
    if (remaining > 0) {
        withTimeoutOrNull(remaining) {
            state.first { ready(it) }
            watchedTitles.listed.first { it?.ready == true }
        }
    }
    return state.value
}

internal fun HomeViewModel.requestWatchedRefillPages(sourceRows: List<CatalogRow>, shownRows: List<CatalogRow>) {
    val shownByKey = shownRows.associateBy { it.legacyKey() }
    val loading = _loadingCatalogs.value
    sourceRows.forEach { source ->
        val key = source.legacyKey()
        val shown = shownByKey[key] ?: return@forEach
        val fetched = watchedTitles.refillPages[key] ?: 0
        if (key in loading || !shouldRefillAfterHidingWatched(source, shown, fetched)) return@forEach
        watchedTitles.refillPages[key] = fetched + 1
        loadMoreCatalogItemsPipeline(source.catalogId, source.addonId, source.apiType)
    }
}

internal fun HomeViewModel.onWatchedFocusRowChanged(rowKey: String?) {
    val held = watchedTitles.heldRowKey ?: return
    if (held != rowKey) scheduleWatchedRowsUpdate()
}

internal fun HomeViewModel.onHomeLeftForWatchedTitles() {
    watchedTitles.homeShown = false
    if (watchedTitles.heldRowKey != null) scheduleWatchedRowsUpdate()
}
