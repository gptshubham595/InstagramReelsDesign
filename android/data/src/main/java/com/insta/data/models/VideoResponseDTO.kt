package com.insta.data.models

data class VideoResponseDTO(
    val id: String?,
    val title: String?,
    val description: String?,
    val duration: Double?,
    val thumbnail: String?,
    val firstChunk: ChunkDTO?,
    val chunks: List<ChunkDTO>?
)
