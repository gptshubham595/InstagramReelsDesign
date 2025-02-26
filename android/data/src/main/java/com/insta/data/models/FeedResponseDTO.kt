package com.insta.data.models

data class FeedResponseDTO(
    val videos: List<VideoResponseDTO>,
    val totalVideos: Int,
    val hasMore: Boolean
)