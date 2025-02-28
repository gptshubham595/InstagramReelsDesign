package com.insta.presentation.feed.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insta.domain.models.VideoResponse
import com.insta.domain.usecases.GetFeedUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val getFeedUseCase: GetFeedUseCase
) : ViewModel() {
    private val _feedState = MutableStateFlow(FeedState())
    val feedState: StateFlow<FeedState> = _feedState

    private var currentPage = 0

    init {
        fetchNextPage()
    }

    fun fetchNextPage() {
        Log.d("FeedViewModel", "===============================")
        Log.d("FeedViewModel", "fetchNextPage $currentPage")
        viewModelScope.launch {
            try {
                val videosList = getFeedUseCase(currentPage)
                _feedState.update {
                    it.copy(
                        videos = it.videos + videosList,
                        isLoading = false,
                        page = currentPage
                    )
                }
                Log.d("FeedViewModel", "$currentPage -> $videosList")
                Log.d("FeedViewModel", "===============================")
                currentPage++
            } catch (e: Exception) {
                _feedState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    data class FeedState(
        val videos: List<VideoResponse> = emptyList(),
        val isLoading: Boolean = true,
        val error: String? = null,
        val page: Int = 0
    )
}


