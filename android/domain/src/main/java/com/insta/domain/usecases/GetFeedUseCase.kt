package com.insta.domain.usecases

import com.insta.domain.models.VideoResponse
import com.insta.domain.repository.VideoRepository
import javax.inject.Inject

class GetFeedUseCase @Inject constructor(
    private val repository: VideoRepository
) {
    suspend operator fun invoke(page: Int): List<VideoResponse> {
        return repository.getFeed(page)
    }
}