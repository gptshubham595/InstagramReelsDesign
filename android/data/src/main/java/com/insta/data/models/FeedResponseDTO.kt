package com.insta.data.models

import kotlinx.serialization.Serializable

@Serializable
data class FeedResponseDTO(
    val videos: List<VideoResponseDTO>,
    val totalVideos: Int,
    val hasMore: Boolean
)