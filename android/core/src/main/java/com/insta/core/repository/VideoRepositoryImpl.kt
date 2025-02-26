package com.insta.core.repository

import com.insta.data.models.ChunkDTO
import com.insta.data.models.UrlsDTO
import com.insta.data.models.VideoResponseDTO
import com.insta.data.network.ApiService
import com.insta.domain.models.Chunk
import com.insta.domain.models.Urls
import com.insta.domain.models.VideoResponse
import com.insta.domain.repository.VideoRepository
import javax.inject.Inject

const val BASE_URL = "https://249b-49-43-242-243.ngrok-free.app"

class VideoRepositoryImpl @Inject constructor(private val apiService: ApiService) :
    VideoRepository {
    override suspend fun getFeed(page: Int): List<VideoResponse> {
        return apiService.getFeed(page).videos.map { it.toVideoResponse() }
    }

    override suspend fun getVideo(videoId: String): VideoResponse {
        return apiService.getVideo(videoId).toVideoResponse()
    }
}

private fun VideoResponseDTO.toVideoResponse(): VideoResponse {
    return VideoResponse(
        id = id,
        title = title,
        description = description,
        duration = duration,
        dashManifest = BASE_URL + dashManifest,
        thumbnail = BASE_URL + thumbnail,
        firstChunk = firstChunk?.toChunkResponse(),
        chunks = chunks?.map { it.toChunkResponse() }
    )
}

private fun ChunkDTO.toChunkResponse(): Chunk {
    return Chunk(
        index = index,
        startTime = startTime,
        duration = duration,
        urls = urls?.toUrlResponse()
    )
}

private fun UrlsDTO.toUrlResponse(): Urls {
    return Urls(
        high = BASE_URL + high,
        medium = BASE_URL + medium,
        low = BASE_URL + low
    )
}

