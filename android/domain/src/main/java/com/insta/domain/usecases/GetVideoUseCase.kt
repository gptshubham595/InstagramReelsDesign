package com.insta.domain.usecases

import com.insta.domain.models.VideoResponse
import com.insta.domain.repository.VideoRepository
import javax.inject.Inject

class GetVideoUseCase @Inject constructor(private val repository: VideoRepository) {
    suspend operator fun invoke(videoId: String): VideoResponse {
        return repository.getVideo(videoId)
    }
}
