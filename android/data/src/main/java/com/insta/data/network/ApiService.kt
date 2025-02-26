package com.insta.data.network
import com.insta.data.models.FeedResponseDTO
import com.insta.data.models.VideoResponseDTO
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("api/feed")
    suspend fun getFeed(
        @Query("page") page: Int = 0,
        @Query("pageSize") pageSize: Int = 10
    ): FeedResponseDTO

    @GET("api/videos/{videoId}")
    suspend fun getVideo(@Path("videoId") videoId: String): VideoResponseDTO
}

