package com.insta.data.models

import kotlinx.serialization.Serializable

@Serializable
data class VideoResponseDTO(
    val id: String?,
    val title: String?,
    val description: String?,
    val duration: Double?,
    val thumbnail: String?,
    val dashManifest: String?,
    val firstChunk: ChunkDTO?,
    val chunks: List<ChunkDTO>?
)
