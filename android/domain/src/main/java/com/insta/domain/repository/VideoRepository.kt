package com.insta.domain.repository

import com.insta.domain.models.VideoResponse

interface VideoRepository {
    suspend fun getFeed(page: Int): List<VideoResponse>
    suspend fun getVideo(videoId: String): VideoResponse
}