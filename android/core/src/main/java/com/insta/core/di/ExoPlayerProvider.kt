package com.insta.core.di

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@UnstableApi
@Singleton
class ExoPlayerProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MAX_BUFFER_MS = 30000 // 30 seconds max buffer
        private const val MIN_BUFFER_MS = 2000  // 2 seconds min buffer for smooth playback
        private const val BUFFER_FOR_PLAYBACK_MS =
            1000 // Start playback after 1 second of buffering
    }

    // Create an ExoPlayer instance with optimized settings
    fun getPlayer(): ExoPlayer {
        // Create LoadControl for buffer settings
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Create player with optimized settings
        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            )
            .setTrackSelector(DefaultTrackSelector(context).apply {
                // Set parameters to prefer performance on mobile
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSize(1280, 720) // Limit max resolution
                        .setForceHighestSupportedBitrate(false)
                ) // Allow adaptive bitrate
            })
            .build()


        // Add a player listener for detailed logging
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_IDLE -> Log.d("DASHPlayer", "State: IDLE")
                    Player.STATE_BUFFERING -> Log.d("DASHPlayer", "State: BUFFERING")
                    Player.STATE_READY -> Log.d("DASHPlayer", "State: READY")
                    Player.STATE_ENDED -> Log.d("DASHPlayer", "State: ENDED")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("DASHPlayer", "Player error: ${error.message}", error)
                // Log the stacktrace
                error.printStackTrace()

                // Try to get more information about what caused the error
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        Log.e("DASHPlayer", "Network connection failed")

                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        Log.e("DASHPlayer", "Network timeout")

                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                        Log.e("DASHPlayer", "Malformed container data")

                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        Log.e("DASHPlayer", "Malformed manifest")
                    // Add other error codes as needed
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d("DASHPlayer", "Is playing: $isPlaying")
            }
        })

        return player
    }

    // Create media source for single local chunk
    fun createFirstChunkMediaSource(localChunkPath: String): MediaSource {
        val uri = Uri.fromFile(File(localChunkPath))

        // Create a progressive media source for local file
        return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
            .createMediaSource(MediaItem.fromUri(uri))
    }

    // Create media source for DASH streaming
    fun createDashMediaSource(dashManifestUrl: String): MediaSource {
        val uri = dashManifestUrl.toUri()

        // Create a data source factory with retry logic
        val dataSourceFactory = createLoggingDataSourceFactory()


        // Create a DASH media source with improved configuration and error handling
        return DashMediaSource.Factory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(object :
                DefaultLoadErrorHandlingPolicy(/* minLoadRetryCount= */ 3) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                    // Implement exponential backoff for retries
                    return min(1000L * (1 shl (loadErrorInfo.errorCount - 1)), 5000L)
                }

                override fun getMinimumLoadableRetryCount(dataType: Int): Int {
                    return 3 // Try at least 3 times
                }
            })
            .createMediaSource(MediaItem.fromUri(uri))
    }

    fun createLoggingDataSourceFactory(): DataSource.Factory {
        return object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                val defaultDataSource = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setDefaultRequestProperties(
                        mapOf(
                            "Connection" to "keep-alive"
                        )
                    )
                    .createDataSource()

                return LoggingDataSource(defaultDataSource)
            }
        }
    }

    // 2. Add a method to validate if the initialization segment exists before attempting playback
    fun validateInitializationSegment(
        baseUrl: String,
        initPath: String,
        callback: (Boolean) -> Unit
    ) {
        val url = "$baseUrl/$initPath"
        val request = Request.Builder().url(url).head().build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("VideoPlayer", "Init segment validation failed: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val exists = response.isSuccessful
                Log.d("VideoPlayer", "Init segment $url exists: $exists")
                callback(exists)
            }
        })
    }


    // Create a merged media source for smooth transition
    fun createMergedMediaSource(localChunkPath: String, dashManifestUrl: String): MediaSource {
        val localSource = createFirstChunkMediaSource(localChunkPath)
        val dashSource = createDashMediaSource(dashManifestUrl)

        // Concatenate sources for seamless playback
        // This allows playing the local file first, then transition to DASH
        return ConcatenatingMediaSource(localSource, dashSource)
    }

    @UnstableApi
    inner class LoggingDataSource(private val wrappedDataSource: DataSource) : DataSource {
        override fun open(dataSpec: DataSpec): Long {
            Log.d(
                "DASHNetwork", "Opening: ${dataSpec.uri}, position=${dataSpec.position}, " +
                        "length=${dataSpec.length}"
            )
            try {
                return wrappedDataSource.open(dataSpec)
            } catch (e: Exception) {
                Log.e("DASHNetwork", "Error opening ${dataSpec.uri}: ${e.message}")
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return wrappedDataSource.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = wrappedDataSource.uri

        override fun close() {
            Log.d("DASHNetwork", "Closing: ${wrappedDataSource.uri}")
            wrappedDataSource.close()
        }

        override fun addTransferListener(transferListener: TransferListener) {
            wrappedDataSource.addTransferListener(transferListener)
        }
    }

    fun logMpdContent(dashManifestUrl: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(dashManifestUrl).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val content = response.body?.string() ?: "Empty response"
                    Log.d("DASHManifest", "MPD Content: $content")

                    // Extract and check initialization segments
                    val initSegments = extractInitSegmentUrls(content)
                    Log.d("DASHManifest", "Found initialization segments: $initSegments")

                    // Check if they exist
                    for (initUrl in initSegments) {
                        val baseUrl = dashManifestUrl.substringBeforeLast("/")
                        val fullUrl = "$baseUrl/$initUrl"
                        checkFileExists(fullUrl)
                    }
                } else {
                    Log.e("DASHManifest", "Failed to fetch MPD: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("DASHManifest", "Error fetching MPD: ${e.message}", e)
            }
        }
    }

    private fun extractInitSegmentUrls(mpdContent: String): List<String> {
        val pattern = Regex("""sourceURL="([^"]+\.mp4)"""")
        return pattern.findAll(mpdContent).map { it.groupValues[1] }.toList()
    }

    private fun checkFileExists(url: String) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).head().build()
            val response = client.newCall(request).execute()

            Log.d("DASHManifest", "File $url exists: ${response.isSuccessful}")
        } catch (e: Exception) {
            Log.e("DASHManifest", "Error checking file $url: ${e.message}")
        }
    }
}
// A DataSource wrapper that logs all operations