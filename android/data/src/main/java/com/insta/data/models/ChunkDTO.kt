package com.insta.data.models

import kotlinx.serialization.Serializable

@Serializable
data class ChunkDTO(
    val index: Int?,
    val startTime: Double?,
    val duration: Double?,
    val urls: UrlsDTO?
)