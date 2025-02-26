package com.insta.domain.models

data class VideoResponse(
    val id: String?,
    val title: String?,
    val description: String?,
    val duration: Double?,
    val thumbnail: String?,
    val dashManifest: String?,
    val firstChunk: Chunk?,
    val chunks: List<Chunk>?
)
