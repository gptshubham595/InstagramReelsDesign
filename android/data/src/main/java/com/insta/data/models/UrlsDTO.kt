package com.insta.data.models
import kotlinx.serialization.Serializable

@Serializable
data class UrlsDTO(
    val low: String?,
    val medium: String?,
    val high: String?
)