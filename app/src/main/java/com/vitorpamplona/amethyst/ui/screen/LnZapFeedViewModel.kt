package com.vitorpamplona.amethyst.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.model.LocalCacheState
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.NostrDataSource
import com.vitorpamplona.amethyst.service.NostrUserProfileZapsDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class NostrUserProfileZapsFeedViewModel: LnZapFeedViewModel(NostrUserProfileZapsDataSource)

open class LnZapFeedViewModel(val dataSource: NostrDataSource<Pair<Note, Note>>): ViewModel() {
    private val _feedContent = MutableStateFlow<LnZapFeedState>(LnZapFeedState.Loading)
    val feedContent = _feedContent.asStateFlow()

    fun refresh() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        scope.launch {
            refreshSuspended()
        }
    }


    private fun refreshSuspended() {
        val notes = dataSource.loadTop()

        val oldNotesState = feedContent.value
        if (oldNotesState is LnZapFeedState.Loaded) {
            // Using size as a proxy for has changed.
            if (notes.size != oldNotesState.feed.value.size && notes.firstOrNull() != oldNotesState.feed.value.firstOrNull()) {
                updateFeed(notes)
            }
        } else {
            updateFeed(notes)
        }
    }

    private fun updateFeed(notes: List<Pair<Note,Note>>) {
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        scope.launch {
            val currentState = feedContent.value
            if (notes.isEmpty()) {
                _feedContent.update { LnZapFeedState.Empty }
            } else if (currentState is LnZapFeedState.Loaded) {
                // updates the current list
                currentState.feed.value = notes
            } else {
                _feedContent.update { LnZapFeedState.Loaded(mutableStateOf(notes)) }
            }
        }
    }

    var handlerWaiting = AtomicBoolean()

    @Synchronized
    private fun invalidateData() {
        if (handlerWaiting.getAndSet(true)) return

        handlerWaiting.set(true)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        scope.launch {
            delay(1000)
            refresh()
            handlerWaiting.set(false)
        }
    }

    private val cacheListener: (LocalCacheState) -> Unit = {
        invalidateData()
    }

    init {
        LocalCache.live.observeForever(cacheListener)
    }

    override fun onCleared() {
        LocalCache.live.removeObserver(cacheListener)
        super.onCleared()
    }
}