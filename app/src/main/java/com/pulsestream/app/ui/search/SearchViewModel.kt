package com.pulsestream.app.ui.search

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.apis
import com.pulsestream.app.CloudStreamApp.Companion.getKey
import com.pulsestream.app.CloudStreamApp.Companion.getKeys
import com.pulsestream.app.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.pulsestream.app.ui.APIRepository
import com.pulsestream.app.ui.home.HomeViewModel
import com.pulsestream.app.utils.AdultContentFilter
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.pulsestream.app.utils.DataStoreHelper.currentAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


data class ExpandableSearchList(
    var list: List<SearchResponse>, var currentPage: Int, var hasNext: Boolean,
)

const val SEARCH_HISTORY_KEY = "search_history"

class SearchViewModel : ViewModel() {
    private val _searchResponse: MutableLiveData<Resource<ExpandableSearchList>> =
        MutableLiveData()
    val searchResponse: LiveData<Resource<ExpandableSearchList>> get() = _searchResponse

    private val _currentSearch: MutableLiveData<Map<String, ExpandableSearchList>> =
        MutableLiveData()
    val currentSearch: LiveData<Map<String, ExpandableSearchList>> get() = _currentSearch

    private val _currentHistory: MutableLiveData<List<SearchHistoryItem>> = MutableLiveData()
    val currentHistory: LiveData<List<SearchHistoryItem>> get() = _currentHistory

    private val _searchSuggestions: MutableLiveData<List<String>> = MutableLiveData()
    val searchSuggestions: LiveData<List<String>> get() = _searchSuggestions

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null

    private var repos: List<APIRepository> = try {
        apis.withLock {
            apis.mapNotNull { api ->
                try {
                    APIRepository(api)
                } catch (e: Throwable) {
                    logError(Exception("SearchViewModel: Failed to create APIRepository for '${api.name}': ${e.message}", e))
                    null
                }
            }
        }
    } catch (e: Throwable) {
        logError(e)
        emptyList()
    }

    /** Force-reload repos from current APIHolder.apis. Returns true if repos is non-empty after reload. */
    private fun forceReloadRepos(): Boolean {
        // Try apis first
        repos = try {
            apis.withLock {
                apis.mapNotNull { api ->
                    try {
                        APIRepository(api)
                    } catch (e: Throwable) {
                        logError(Exception("SearchViewModel: Failed to create APIRepository for '${api.name}': ${e.message}", e))
                        null
                    }
                }
            }
        } catch (e: Throwable) {
            logError(e)
            emptyList()
        }
        if (repos.isEmpty()) {
            // APIs might not be populated yet — try allProviders as a fallback
            repos = try {
                APIHolder.allProviders.withLock {
                    APIHolder.allProviders.toSet().mapNotNull { api ->
                        try {
                            APIRepository(api)
                        } catch (e: Throwable) {
                            logError(Exception("SearchViewModel: Failed to create APIRepository for '${api.name}' (allProviders): ${e.message}", e))
                            null
                        }
                    }
                }
            } catch (e: Throwable) {
                logError(e)
                emptyList()
            }
        }
        com.pulsestream.app.diagnostics.PulseDiagnostics.event("REPOSITORY", "operation=forceReloadRepos", "repoCount=${repos.size}", "apisSize=${apis.size}", "allProvidersSize=${APIHolder.allProviders.size}")
        return repos.isNotEmpty()
    }

    fun clearSearch() {
        _searchResponse.postValue(Resource.Success(ExpandableSearchList(emptyList(), 0, false)))
        _currentSearch.postValue(emptyMap())
        expandableSearches.clear()
    }

    var lastQuery: String? = null

    /** Save which providers can searched again and which search result page they are on.
     * Maps provider name to search list.
     * @see [HomeViewModel.expandable] */
    private val expandableSearches: MutableMap<String, ExpandableSearchList> = ConcurrentHashMap()

    @Volatile
    private var currentSearchIndex = 0

    fun reloadRepos() {
        forceReloadRepos()
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) {
        currentSearchIndex++
        searchJob?.cancel()
        searchJob = viewModelScope.launchSafe {
            search(query, providersActive, ignoreSettings, isQuickSearch)
        }
    }

    /** Debounced search for real-time typing - waits 300ms before executing */
    fun searchDebounced(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
    ) {
        // Cancel any pending debounced search (including any in-flight search() call)
        searchJob?.cancel()
        
        searchJob = viewModelScope.launchSafe {
            delay(300) // Debounce
            if (query.length > 1) {
                currentSearchIndex++
                search(query, providersActive, ignoreSettings, false)
            } else {
                clearSearch()
            }
        }
    }

    fun updateHistory() = ioSafe {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.postValue(items)
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     * 
     * @param query The search query to get suggestions for
     */
    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()
        
        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.postValue(emptyList())
            return
        }
        
        suggestionJob = ioSafe {
            delay(300) // Debounce
            val suggestions = SearchSuggestionApi.getSuggestions(query)
            _searchSuggestions.postValue(suggestions)
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionJob?.cancel()
        _searchSuggestions.postValue(emptyList())
    }

    private val lock: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // ExpandableHomepageList because the home adapter is reused in the search fragment
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
        try {
            // H4 FIX: Atomic check-and-add to prevent TOCTOU race
            if (!lock.add(name)) {
                com.pulsestream.app.diagnostics.PulseDiagnostics.event("SEARCH", "expandAndReturn", "SKIPPED", "reason=already_expanding", "name=$name")
                return null
            }
            val query = lastQuery
            if (query == null) {
                com.pulsestream.app.diagnostics.PulseDiagnostics.event("SEARCH", "expandAndReturn", "SKIPPED", "reason=no_last_query", "name=$name")
                return null
            }
            val repo = repos.find { it.name == name }
            if (repo == null) {
                com.pulsestream.app.diagnostics.PulseDiagnostics.event("SEARCH", "expandAndReturn", "SKIPPED", "reason=no_repo", "name=$name", "availableRepos=${repos.map { it.name }}")
                return null
            }

            expandableSearches[name]?.let { current ->
                debugAssert({ !current.hasNext }) {
                    "Expand called when not needed"
                }

                val nextPage = current.currentPage + 1
                val next = try {
                    repo.search(query, nextPage)
                } catch (e: Throwable) {
                    com.lagradost.cloudstream3.mvvm.logError(e)
                    Resource.Failure(false, e.message ?: "Expand failed")
                }
                if (next is Resource.Success) {
                    val nextValue = next.value
                    val nextItems = AdultContentFilter.filterList(nextValue.items)
                    expandableSearches[name]?.apply {
                        this.hasNext = nextValue.hasNext
                        this.currentPage = nextPage

                        debugWarning({ nextItems.any { outer -> this.list.any { it.url == outer.url } } }) {
                            "Expanded search contained an item that was previously already in the list.\nQuery = $query, ${nextItems} = ${this.list}"
                        }

                        // just to be sure we are not adding the same shit for some reason
                        // Avoids weird behavior in the recyclerview by recreating the list
                        val merged = (this.list + nextItems).distinctBy { it.url }
                        this.list = AdultContentFilter.filterByImage(merged)
                    } ?: debugWarning {
                        "Expanded an item not in search load named $name, current list is ${expandableSearches.keys}"
                    }
                } else {
                    current.hasNext = false
                }

                _searchResponse.postValue(Resource.Success(bundleSearch(expandableSearches)))
                _currentSearch.postValue(expandableSearches)
            }

            lock -= name

            val item = expandableSearches[name] ?: return null
            return HomeViewModel.ExpandableHomepageList(
                HomePageList(name, item.list),
                item.currentPage,
                item.hasNext
            )
        } catch (e: Throwable) {
            com.lagradost.cloudstream3.mvvm.logError(e)
            lock -= name
            // L3 FIX: Remove partially-modified entry to prevent stale data
            expandableSearches.remove(name)
            return null
        }
    }

    private fun bundleSearch(lists: Map<String, ExpandableSearchList>): ExpandableSearchList {
        if (lists.size == 1) {
            return lists.values.first()
        }

        val list = ArrayList<SearchResponse>()
        val nestedList =
            lists.map { it.value.list }

        // I do it this way to move the relevant search results to the top
        var index = 0
        while (true) {
            var added = 0
            for (sublist in nestedList) {
                if (sublist.size > index) {
                    list.add(sublist[index])
                    added++
                }
            }
            if (added == 0) break
            index++
        }

        return ExpandableSearchList(list, 1, false)
    }

    private suspend fun search(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) {
        val reqId = com.pulsestream.app.diagnostics.PulseDiagnostics.newRequestId()
        val searchScreen = if (isQuickSearch) "QuickSearch" else "GlobalSearch"
        com.pulsestream.app.diagnostics.PulseDiagnostics.searchEvent(searchScreen, query, "requestId=$reqId", "SEARCH_START")
        try {
            val currentIndex = currentSearchIndex
            if (query.length <= 1) {
                clearSearch()
                return
            }

            if (!isQuickSearch) {
                val key = query.hashCode().toString()
                setKey(
                    "$currentAccount/$SEARCH_HISTORY_KEY",
                    key,
                    SearchHistoryItem(
                        searchedAt = System.currentTimeMillis(),
                        searchText = query,
                        type = emptyList(), // TODO implement tv type
                        key = key,
                    )
                )
            }

            _searchResponse.postValue(Resource.Loading())
            _currentSearch.postValue(emptyMap())
            expandableSearches.clear()

            lastQuery = query

            withContext(Dispatchers.IO) { // This interrupts UI otherwise
                // Safety: if repos is empty (race condition with plugin loading), force-reload
                if (repos.isEmpty()) {
                    logError(Exception("SearchViewModel: repos was empty for query '$query', attempting force reload from ${apis.size} apis / ${APIHolder.allProviders.size} allProviders"))
                    forceReloadRepos()
                }
                logError(Exception("SearchViewModel: search query='$query' providersActive=${providersActive.size} repos=${repos.size} ignoreSettings=$ignoreSettings isQuickSearch=$isQuickSearch"))

                val providersToSearch = repos.filter { a ->
                    (ignoreSettings || (providersActive.isEmpty() || providersActive.contains(a.name))) && (!isQuickSearch || a.hasQuickSearch)
                }

                logError(Exception("SearchViewModel: providersToSearch=${providersToSearch.size} names=${providersToSearch.map { it.name }}"))
                
                if (providersToSearch.isEmpty()) {
                    _searchResponse.postValue(Resource.Success(ExpandableSearchList(emptyList(), 0, false)))
                    return@withContext
                }

                providersToSearch.amap { a -> // Parallel
                    val search = try {
                        if (isQuickSearch) a.quickSearch(query) else a.search(query, 1)
                    } catch (e: Throwable) {
                        com.lagradost.cloudstream3.mvvm.logError(e)
                        Resource.Failure(false, e.message ?: "Search failed")
                    }
                    // Check if this search is still the current one
                    if (currentSearchIndex != currentIndex) return@amap
                    if (search is Resource.Success) {
                        val searchValue = search.value
                        val filteredItems = AdultContentFilter.filterList(searchValue.items)
                        expandableSearches[a.name] =
                            ExpandableSearchList(filteredItems, 1, searchValue.hasNext)
                    } else if (search is Resource.Failure) {
                        // Log provider failure but don't crash - continue with other providers
                        com.lagradost.cloudstream3.mvvm.logError(Exception("Provider ${a.name} search failed: ${search.errorString}"))
                    }

                    _currentSearch.postValue(expandableSearches)
                }

                if (currentSearchIndex != currentIndex) return@withContext // this should prevent rewrite of existing data bug

                val bundled = bundleSearch(expandableSearches.toMap())
                val filteredItems = AdultContentFilter.filterByImage(bundled.list)
                val filteredUrls = filteredItems.map { it.url }.toSet()
                expandableSearches.forEach { (_, value) ->
                    value.list = value.list.filter { it.url in filteredUrls }
                }

                _currentSearch.postValue(expandableSearches)
                _searchResponse.postValue(Resource.Success(ExpandableSearchList(filteredItems, bundled.currentPage, bundled.hasNext)))
                com.pulsestream.app.diagnostics.PulseDiagnostics.searchEvent(searchScreen, query, "requestId=$reqId", "SEARCH_COMPLETE", "resultCount=${filteredItems.size}", "providerCount=${expandableSearches.size}")
            }
        } catch (e: Throwable) {
            com.pulsestream.app.diagnostics.PulseDiagnostics.error("SearchViewModel", "search", e, "requestId=$reqId", "query=$query")
            com.lagradost.cloudstream3.mvvm.logError(e)
            _searchResponse.postValue(Resource.Failure(false, e.message ?: "Search failed"))
        }
    }
}
