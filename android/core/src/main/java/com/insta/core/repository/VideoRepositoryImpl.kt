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


class VideoRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val localIpAddress: String
) :
    VideoRepository {

    override suspend fun getFeed(page: Int): List<VideoResponse> {
        return apiService.getFeed(page).videos.map { it.toVideoResponse(localIpAddress) }
    }

    override suspend fun getVideo(videoId: String): VideoResponse {
        return apiService.getVideo(videoId).toVideoResponse(localIpAddress)
    }
}

private fun VideoResponseDTO.toVideoResponse(localIpAddress: String): VideoResponse {
    return VideoResponse(
        id = id,
        title = title,
        description = description,
        duration = duration,
        dashManifest = localIpAddress + dashManifest,
        thumbnail = localIpAddress + thumbnail,
        firstChunk = firstChunk?.toChunkResponse(localIpAddress),
        chunks = chunks?.map { it.toChunkResponse(localIpAddress) }
    )
}

private fun ChunkDTO.toChunkResponse(localIpAddress: String): Chunk {
    return Chunk(
        index = index,
        startTime = startTime,
        duration = duration,
        urls = urls?.toUrlResponse(localIpAddress)
    )
}

private fun UrlsDTO.toUrlResponse(localIpAddress: String): Urls {
    return Urls(
        high = localIpAddress + high,
        medium = localIpAddress + medium,
        low = localIpAddress + low
    )
}

