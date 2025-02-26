package com.insta.core.managers

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.insta.domain.models.VideoResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class VideoCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "VideoCacheManager"
        private const val CACHE_DIRECTORY = "video_chunks"
        private const val MAX_CHUNKS_TO_CACHE = 10 // Limit cache size
    }

    private val cacheDir: File = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val downloadingChunks = ConcurrentHashMap<String, Boolean>()
    private val downloadCompletionListeners = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    // Check if first chunk is already cached for a video
    fun isFirstChunkCached(videoId: String): Boolean {
        val firstChunkFile = File(cacheDir, "${videoId}_0_low.mp4")
        return firstChunkFile.exists() && firstChunkFile.length() > 0
    }

    // Get local file path of first cached chunk
    fun getFirstChunkPath(videoId: String): String? {
        val firstChunkFile = File(cacheDir, "${videoId}_0_low.mp4")
        return if (firstChunkFile.exists() && firstChunkFile.length() > 0) {
            firstChunkFile.absolutePath
        } else null
    }

    // Download first chunk of a video (low quality for faster loading)
    suspend fun downloadFirstChunk(video: VideoResponse): Boolean {
        val videoId = video.id ?: return false
        val firstChunk = video.firstChunk ?: return false
        val lowQualityUrl = firstChunk.urls?.low ?: return false

        // Construct a key that includes quality level
        val chunkKey = "${videoId}_0_low.mp4"

        // Check if already downloaded or being downloaded
        if (isFirstChunkCached(videoId) || downloadingChunks[chunkKey] == true) {
            Log.d(TAG, "First chunk already cached or downloading: $chunkKey")
            return true
        }

        // Mark as downloading
        downloadingChunks[chunkKey] = true

        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Downloading first chunk: $lowQualityUrl")

                val request = Request.Builder().url(lowQualityUrl).build()
                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("Failed to download chunk: ${response.code}")
                }

                val outputFile = File(cacheDir, chunkKey)
                outputFile.outputStream().use { fileOutputStream ->
                    response.body?.byteStream()?.copyTo(fileOutputStream)
                }

                Log.d(TAG, "Successfully downloaded first chunk: $chunkKey")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading first chunk: ${e.message}")
            false
        } finally {
            downloadingChunks.remove(chunkKey)
            // Notify any listeners waiting for this download
            downloadCompletionListeners[chunkKey]?.forEach { it.invoke() }
            downloadCompletionListeners.remove(chunkKey)
        }
    }

    // Add listener for download completion
    fun addDownloadCompletionListener(chunkKey: String, listener: () -> Unit) {
        downloadCompletionListeners.getOrPut(chunkKey) { mutableListOf() }.add(listener)
    }

    // Preload next 3 chunks after user has watched for 2 seconds
    suspend fun preloadNextChunks(video: VideoResponse, currentChunkIndex: Int = 0): Boolean {
        val videoId = video.id ?: return false
        val chunks = video.chunks ?: return false

        // Calculate which chunks to preload (next 3 chunks after current)
        val chunksToPreload =
            (currentChunkIndex + 1 until minOf(currentChunkIndex + 4, chunks.size))
                .mapNotNull { index -> chunks.getOrNull(index) }

        if (chunksToPreload.isEmpty()) {
            Log.d(TAG, "No more chunks to preload")
            return true
        }

        var success = true

        // Download each chunk (medium quality for balance between speed and quality)
        withContext(Dispatchers.IO) {
            chunksToPreload.map { chunk ->
                async {
                    try {
                        val index = chunk.index ?: return@async false
                        val mediumQualityUrl = chunk.urls?.medium ?: return@async false
                        val chunkKey = "${videoId}_${index}_medium.mp4"

                        // Skip if already cached or downloading
                        if (File(
                                cacheDir,
                                chunkKey
                            ).exists() || downloadingChunks[chunkKey] == true
                        ) {
                            return@async true
                        }

                        // Mark as downloading
                        downloadingChunks[chunkKey] = true

                        Log.d(TAG, "Preloading chunk: $chunkKey")

                        val request = Request.Builder().url(mediumQualityUrl).build()
                        val response = okHttpClient.newCall(request).execute()

                        if (!response.isSuccessful) {
                            throw IOException("Failed to download chunk: ${response.code}")
                        }

                        val outputFile = File(cacheDir, chunkKey)
                        outputFile.outputStream().use { fileOutputStream ->
                            response.body?.byteStream()?.copyTo(fileOutputStream)
                        }

                        Log.d(TAG, "Successfully preloaded chunk: $chunkKey")
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preloading chunk: ${e.message}")
                        success = false
                        false
                    } finally {
                        downloadingChunks.remove("${videoId}_${chunk.index}_medium.mp4")
                    }
                }
            }.awaitAll()
        }

        // Clean up old chunks if needed
        cleanupOldChunks()

        return success
    }

    // Clean up old chunks to prevent cache from growing too large
    private fun cleanupOldChunks() {
        val files = cacheDir.listFiles() ?: return

        if (files.size > MAX_CHUNKS_TO_CACHE) {
            // Sort by last modified (oldest first)
            val filesToDelete = files.sortedBy { it.lastModified() }
                .take(files.size - MAX_CHUNKS_TO_CACHE)

            for (file in filesToDelete) {
                try {
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old chunk: ${file.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting old chunk: ${e.message}")
                }
            }
        }
    }

    // Clear all cached chunks (e.g., when user clears app data)
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Cleared video chunk cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache: ${e.message}")
        }
    }
}