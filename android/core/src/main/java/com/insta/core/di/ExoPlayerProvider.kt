package com.insta.core.di

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class ExoPlayerProvider @Inject constructor(
    private val context: Context
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
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
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
        val uri = Uri.parse(dashManifestUrl)

        // Create a DASH media source
        return DashMediaSource.Factory(
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000) // 15 seconds connection timeout
                .setReadTimeoutMs(15000)    // 15 seconds read timeout
        )
            .createMediaSource(MediaItem.fromUri(uri))
    }

    // Create a merged media source for smooth transition
    fun createMergedMediaSource(localChunkPath: String, dashManifestUrl: String): MediaSource {
        val localSource = createFirstChunkMediaSource(localChunkPath)
        val dashSource = createDashMediaSource(dashManifestUrl)

        // Concatenate sources for seamless playback
        // This allows playing the local file first, then transition to DASH
        return ConcatenatingMediaSource(localSource, dashSource)
    }
}