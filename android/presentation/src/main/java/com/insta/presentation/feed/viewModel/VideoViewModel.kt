package com.insta.presentation.feed.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insta.domain.models.VideoResponse
import com.insta.domain.usecases.GetVideoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val getVideoUseCase: GetVideoUseCase
) :
    ViewModel() {
    private val _videoState = MutableStateFlow(VideoState())
    val videoState: StateFlow<VideoState> = _videoState

    fun getVideo(videoId: String) {
        viewModelScope.launch {
            try {
                val video = getVideoUseCase(videoId)
                _videoState.update {
                    it.copy(
                        video = video,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _videoState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    data class VideoState(
        val video: VideoResponse? = null,
        val isLoading: Boolean = true,
        val error: String? = null
    )
}