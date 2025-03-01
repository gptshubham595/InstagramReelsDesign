package com.insta.presentation.feed.adapter

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.insta.core.di.ExoPlayerProvider
import com.insta.core.managers.VideoCacheManager
import com.insta.domain.models.VideoResponse
import com.insta.presentation.databinding.ItemReelBinding
import com.insta.presentation.helper.hide
import com.insta.presentation.helper.show
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.core.net.toUri
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource

@UnstableApi
class FeedAdapter @Inject constructor(
    private val lifecycleOwner: LifecycleOwner,
    private val videoCacheManager: VideoCacheManager,
    private val exoPlayerProvider: ExoPlayerProvider,
    val videos: MutableList<VideoResponse> = mutableListOf()
) : RecyclerView.Adapter<FeedAdapter.ReelViewHolder>() {

    private var recyclerView: RecyclerView? = null

    companion object {
        private const val TAG = "FeedAdapter"
        private const val PRELOAD_DELAY_MS = 2000L // 2 seconds before preloading next chunks
    }

    // Keep track of currently active viewholder for player management
    private var currentlyPlayingHolder: ReelViewHolder? = null
    private val handler = Handler(Looper.getMainLooper())

    // Track visible items for preloading decisions
    private val visibleItems = mutableSetOf<Int>()

    init {
        // Preload first chunks for initial videos
        Log.d(TAG, "======================================")
        Log.d(TAG, "FeedAdapter initialized, preloading first chunks")
        Log.d(TAG, "======================================")
        preloadFirstChunks()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReelViewHolder {
        Log.d(TAG, "onCreateViewHolder")
        val binding = ItemReelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReelViewHolder, position: Int) {
        Log.d(TAG, "======================================")
        Log.d(TAG, "onBindViewHolder: position=$position, videoId=${videos[position].id}")
        holder.bind(videos[position])
        Log.d(TAG, "======================================")
    }

    override fun getItemCount(): Int = videos.size

    // Call when new videos are added to preload their first chunks
    fun preloadFirstChunks() {
        Log.d(TAG, "======================================")
        Log.d(TAG, "preloadFirstChunks START: videos count=${videos.size}")
        lifecycleOwner.lifecycleScope.launch {
            videos.forEach { video ->
                val videoId = video.id ?: ""
                Log.d(TAG, "Checking if first chunk is cached for videoId=$videoId")
                if (!videoCacheManager.isFirstChunkCached(videoId)) {
                    Log.d(TAG, "First chunk not cached, downloading for videoId=$videoId")
                    val success = videoCacheManager.downloadFirstChunk(video)
                    Log.d(TAG, "Download result for videoId=$videoId: $success")
                } else {
                    Log.d(TAG, "First chunk already cached for videoId=$videoId")
                }
            }
            Log.d(TAG, "preloadFirstChunks COMPLETED")
            Log.d(TAG, "======================================")
        }
    }

    // Register visible items for playback decisions
    fun onItemVisible(position: Int) {
        Log.d(TAG, "======================================")
        Log.d(TAG, "onItemVisible: position=$position")
        if (position >= 0 && position < videos.size) {
            visibleItems.add(position)
            Log.d(TAG, "Current visible items: $visibleItems")
            considerPlayback(position)
        } else {
            Log.d(TAG, "Position out of bounds: $position, size=${videos.size}")
        }
        Log.d(TAG, "======================================")
    }

    // Remove from visible items when scrolled away
    fun onItemInvisible(position: Int) {
        Log.d(TAG, "======================================")
        Log.d(TAG, "onItemInvisible: position=$position")
        visibleItems.remove(position)
        Log.d(TAG, "Current visible items after removal: $visibleItems")

        Log.d(TAG, "Releasing current player for position=$position")
        releaseCurrentPlayer()
        Log.d(TAG, "======================================")
    }

    // Decide which item should be playing
    private fun considerPlayback(position: Int) {
        Log.d(TAG, "considerPlayback: position=$position")
        // If we're already playing this position, do nothing
        if (currentlyPlayingHolder?.bindingAdapterPosition == position) {
            Log.d(TAG, "Already playing this position, nothing to do")
            return
        }

        // Release any previous player
        Log.d(TAG, "Releasing previous player")
        releaseCurrentPlayer()

        // Find the view holder and play the video
        val holder = getViewHolderForPosition(position)
        if (holder != null) {
            Log.d(TAG, "Found ViewHolder for position $position, preparing playback")
            currentlyPlayingHolder = holder
            holder.prepareAndPlay()
        } else {
            Log.d(TAG, "Could not find ViewHolder for position $position")
        }
    }
    // Override onDetachedFromRecyclerView to clear the reference
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
        Log.d(TAG, "Adapter detached from RecyclerView")
    }
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        Log.d(TAG, "Adapter attached to RecyclerView")
    }

    private fun getViewHolderForPosition(position: Int): ReelViewHolder? {
        return try {
            val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ReelViewHolder
            Log.d(TAG, "getViewHolderForPosition: position=$position, found=${holder != null}")
            holder
        } catch (e: Exception) {
            Log.e(TAG, "Error finding ViewHolder for position $position: ${e.message}")
            null
        }
    }

    private fun releaseCurrentPlayer() {
        Log.d(
            TAG,
            "releaseCurrentPlayer: current position=${currentlyPlayingHolder?.bindingAdapterPosition}"
        )
        currentlyPlayingHolder?.releasePlayer()
        currentlyPlayingHolder = null
    }

    // Cleanup when adapter is detached
    fun release() {
        Log.d(TAG, "======================================")
        Log.d(TAG, "release: cleaning up resources")
        releaseCurrentPlayer()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "======================================")
    }

    inner class ReelViewHolder(val binding: ItemReelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var player: ExoPlayer? = null
        private var currentVideo: VideoResponse? = null
        private var preloadTaskActive: Boolean = false
        private var playbackStartTime: Long = 0
        private val preloadRunnable = Runnable { preloadNextChunks() }

        fun bind(video: VideoResponse) {
            Log.d(TAG, "ViewHolder bind: position=$bindingAdapterPosition, videoId=${video.id}")
            currentVideo = video

            // Reset view state - START WITH BOTH ELEMENTS HIDDEN
            binding.playerView.hide()
            binding.thumbnailImage.hide()
            binding.progressBar.show()

            binding.videoTitle.text = video.title
            binding.videoDescription.text = video.description

            // Load thumbnail using Glide
            Log.d(TAG, "Loading thumbnail: ${video.thumbnail}")
            Glide.with(binding.root.context)
                .load(video.thumbnail)
                .into(binding.thumbnailImage)

            // Check if first chunk is already cached
            Log.d(TAG, "Checking first chunk availability")
            checkFirstChunkAndPrepare()
        }

        // Check if we can start playing immediately
        private fun checkFirstChunkAndPrepare() {
            val videoId = currentVideo?.id ?: return
            Log.d(TAG, "checkFirstChunkAndPrepare: videoId=$videoId")

            if (videoCacheManager.isFirstChunkCached(videoId)) {
                // First chunk is available, prepare player immediately
                Log.d(TAG, "First chunk is available, setting up player")
                setupPlayerWithCachedChunk()

                // If this is visible, start playing right away without showing thumbnail
                if (visibleItems.contains(bindingAdapterPosition)) {
                    binding.playerView.show()
                    binding.progressBar.hide()
                    player?.playWhenReady = true
                    Log.d(TAG, "This item is visible, starting playback immediately")
                }
            } else {
                // Show thumbnail until first chunk is downloaded
                Log.d(TAG, "First chunk not available, showing thumbnail and downloading")
                binding.thumbnailImage.show()
                binding.progressBar.hide()
                binding.playerView.hide()

                // Request download of first chunk
                lifecycleOwner.lifecycleScope.launch {
                    currentVideo?.let { video ->
                        Log.d(TAG, "Downloading first chunk for videoId=$videoId")
                        val success = videoCacheManager.downloadFirstChunk(video)
                        Log.d(TAG, "Download result: $success, checking cache again")

                        if (success && videoCacheManager.isFirstChunkCached(videoId)) {
                            Log.d(TAG, "First chunk now available after download")
                            setupPlayerWithCachedChunk()

                            // If this view is currently visible, start playing immediately
                            if (visibleItems.contains(bindingAdapterPosition)) {
                                binding.thumbnailImage.hide()
                                binding.playerView.show()
                                player?.playWhenReady = true
                                Log.d(TAG, "This item is visible, starting playback")
                            }
                        } else {
                            Log.d(TAG, "First chunk still not available after download attempt")
                            // Keep showing thumbnail
                        }
                    }
                }
            }
        }

        // Set up player with cached first chunk
        private fun setupPlayerWithCachedChunk() {
            val videoId = currentVideo?.id ?: return
            val firstChunkPath = videoCacheManager.getFirstChunkPath(videoId)
            Log.d(TAG, "setupPlayerWithCachedChunk: videoId=$videoId, path=$firstChunkPath")

            if (firstChunkPath == null) {
                Log.e(TAG, "Failed to get cached chunk path despite cache check passing")
                return
            }

            initializePlayer()

            player?.let { exoPlayer ->
                // Create a media source for the locally cached chunk
                Log.d(TAG, "Creating media source for local chunk: $firstChunkPath")
                val mediaSource = exoPlayerProvider.createFirstChunkMediaSource(firstChunkPath)
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()

                // Don't auto-play, we'll play when visible
                exoPlayer.playWhenReady = false
                Log.d(TAG, "Player prepared with local chunk, waiting for visibility")
            }
        }

        // Initialize player with proper configuration
        private fun initializePlayer() {
            if (player == null) {
                Log.d(TAG, "initializePlayer: creating new ExoPlayer instance")
                player = exoPlayerProvider.getPlayer().apply {
                    // Configure player view
                    binding.playerView.player = this
                    binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                    // Player listeners
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            val stateStr = when (state) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> "UNKNOWN"
                            }
                            Log.d(TAG, "Player state changed: $stateStr")

                            when (state) {
                                Player.STATE_BUFFERING -> {
                                    Log.d(TAG, "Buffering video...")
                                    binding.progressBar.show()
                                }

                                Player.STATE_READY -> {
                                    Log.d(TAG, "Ready to play")
                                    binding.progressBar.hide()

                                    // Always hide thumbnail when player is ready
                                    binding.thumbnailImage.hide()

                                    if (visibleItems.contains(bindingAdapterPosition)) {
                                        binding.playerView.show()
                                    }

                                    if (playbackStartTime == 0L && isPlaying) {
                                        playbackStartTime = System.currentTimeMillis()
                                        Log.d(TAG, "First playback, scheduling preload after 2s")
                                        // Schedule preload after 2 seconds
                                        schedulePreload()
                                    }
                                }

                                Player.STATE_ENDED -> {
                                    Log.d(TAG, "Video ended, looping")
                                    // Loop video
                                    seekTo(0)
                                    play()
                                }

                                else -> { /* Do nothing */
                                }
                            }
                        }
                    })
                }
            } else {
                Log.d(TAG, "Player already initialized")
            }
        }

        // Prepare player and start playing
        fun prepareAndPlay() {
            val videoId = currentVideo?.id ?: return
            Log.d(TAG, "======================================")
            Log.d(TAG, "prepareAndPlay: videoId=$videoId, position=$bindingAdapterPosition")
            playbackStartTime = 0 // Reset timer

            // If first chunk is cached, play from local file first
            if (videoCacheManager.isFirstChunkCached(videoId)) {
                Log.d(TAG, "First chunk is cached, playing from local file")
                if (player == null) {
                    Log.d(TAG, "Setting up player with cached chunk")
                    setupPlayerWithCachedChunk()
                }

                // Start playback immediately - hide thumbnail, show player
                binding.thumbnailImage.hide()
                binding.playerView.show()
                binding.progressBar.hide()
                Log.d(TAG, "Starting playback")
                player?.playWhenReady = true
            } else {
                // No cached chunk yet, show thumbnail and try to download
                Log.d(TAG, "No cached chunk, showing thumbnail and trying to download")
                binding.thumbnailImage.show()
                binding.playerView.hide()
                binding.progressBar.show()

                lifecycleOwner.lifecycleScope.launch {
                    currentVideo?.let { video ->
                        Log.d(TAG, "Downloading first chunk on-demand")
                        val success = videoCacheManager.downloadFirstChunk(video)
                        Log.d(TAG, "Download result: $success")

                        if (success && videoCacheManager.isFirstChunkCached(videoId)) {
                            // Only proceed if this holder is still for the same video and visible
                            if (visibleItems.contains(bindingAdapterPosition)) {
                                Log.d(
                                    TAG,
                                    "First chunk downloaded successfully and view is visible"
                                )
                                setupPlayerWithCachedChunk()
                                binding.thumbnailImage.hide()
                                binding.playerView.show()
                                binding.progressBar.hide()
                                player?.playWhenReady = true
                            } else {
                                Log.d(TAG, "First chunk downloaded but view is no longer visible")
                            }
                        } else {
                            // If download failed, try direct DASH playback
                            Log.d(TAG, "First chunk download failed, trying direct DASH playback")
                            val dashManifestUrl = video.dashManifest
                            if (dashManifestUrl != null) {
                                Log.d(
                                    TAG,
                                    "Setting up DASH playback with manifest: $dashManifestUrl"
                                )
                                setupDashPlayback(dashManifestUrl)
                                binding.thumbnailImage.hide()
                                binding.playerView.show()
                                player?.playWhenReady = true
                            } else {
                                Log.e(TAG, "No DASH manifest available, can't play video")
                                // Keep showing thumbnail
                                binding.thumbnailImage.show()
                            }
                            binding.progressBar.hide()
                        }
                    }
                }
            }
            Log.d(TAG, "======================================")
        }

        // Set up player with DASH manifest
        private fun setupDashPlayback(dashManifestUrl: String) {
            Log.d(TAG, "setupDashPlayback: url=$dashManifestUrl")
            initializePlayer()

            player?.let { exoPlayer ->

                val mediaItem = MediaItem.Builder()
                    .setUri(dashManifestUrl)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()

                val dashMediaSource = exoPlayerProvider.createDashMediaSource(dashManifestUrl)
                Log.d(TAG, "DASH media source created, preparing player")
                exoPlayer.setMediaSource(dashMediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        }

        // Schedule preloading of next chunks after delay
        private fun schedulePreload() {
            // Cancel any existing preload task
            cancelPreloadTask()

            // Schedule new preload task - must be visible for 2 seconds
            Log.d(TAG, "Scheduling preload task for ${PRELOAD_DELAY_MS}ms from now")
            preloadTaskActive = true
            handler.postDelayed(preloadRunnable, PRELOAD_DELAY_MS)
        }

        // Cancel scheduled preload
        private fun cancelPreloadTask() {
            if (preloadTaskActive) {
                Log.d(TAG, "Cancelling preload task")
                handler.removeCallbacks(preloadRunnable)
                preloadTaskActive = false
            }
        }

        // Preload next chunks and switch to DASH
        private fun preloadNextChunks() {
            val video = currentVideo ?: return
            val dashManifestUrl = video.dashManifest ?: return

            Log.d(TAG, "======================================")
            Log.d(TAG, "preloadNextChunks: videoId=${video.id}")

            // Ensure we've been playing for at least 2 seconds
            val playDuration = System.currentTimeMillis() - playbackStartTime
            Log.d(TAG, "Play duration: ${playDuration}ms, required: ${PRELOAD_DELAY_MS}ms")

            if (playDuration < PRELOAD_DELAY_MS) {
                // Not enough time has passed, reschedule
                val remainingTime = PRELOAD_DELAY_MS - playDuration
                Log.d(TAG, "Not played long enough, rescheduling for ${remainingTime}ms from now")
                handler.postDelayed(preloadRunnable, remainingTime)
                return
            }

            // Mark as inactive since we're executing now
            preloadTaskActive = false

            // Start preloading next chunks
            Log.d(TAG, "Starting to preload next chunks")
            lifecycleOwner.lifecycleScope.launch {
                val success = videoCacheManager.preloadNextChunks(video)
                Log.d(TAG, "Preload next chunks result: $success")
            }

            // Switch to DASH playback for continuous streaming
            Log.d(TAG, "Switching to DASH playback: $dashManifestUrl")
            switchToDashPlayback(dashManifestUrl)
            Log.d(TAG, "======================================")
        }

        // Switch from single chunk to DASH manifest
        private fun switchToDashPlayback(dashManifestUrl: String) {
            // Extract base URL from manifest URL (adjust as needed for your URL structure)
            val baseUrl = dashManifestUrl.substringBeforeLast("/")
            exoPlayerProvider.logMpdContent(dashManifestUrl)

            // Validate that at least the low quality initialization segment exists
            exoPlayerProvider.validateInitializationSegment(baseUrl, "init-low.mp4") { exists ->
                if (!exists) {
                    Log.e(
                        "VideoPlayer",
                        "Initialization segment not found, falling back to direct playback"
                    )
                    // Fall back to direct playback without DASH
//                    setupDirectPlayback(
//                        currentVideo?.directUrl ?: return@validateInitializationSegment
//                    )
                    return@validateInitializationSegment
                }
            }

            player?.let { exoPlayer ->
                try {
                    // Remember current playback position and state
                    val currentPosition = exoPlayer.currentPosition
                    val wasPlaying = exoPlayer.isPlaying

                    Log.d("VideoPlayer", "switchToDashPlayback: position=$currentPosition, wasPlaying=$wasPlaying")

                    // Create media item with initialization data
                    val mediaItem = MediaItem.Builder()
                        .setUri(dashManifestUrl.toUri())
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build()

                    val loadControl = DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            1000, // Min buffer ms
                            3000, // Max buffer ms
                            500,  // Buffer for playback ms
                            1000  // Buffer for rebuffer ms
                        )
                        .build()

                    // Create DASH media source
                    val dashMediaSource = exoPlayerProvider.createDashMediaSource(dashManifestUrl)

                    // Set the new media source
                    exoPlayer.setMediaSource(dashMediaSource)
                    exoPlayer.prepare()

                    // Restore position and playback state
//                    exoPlayer.seekTo(currentPosition)
                    if (exoPlayer.playWhenReady != wasPlaying) {
                        exoPlayer.playWhenReady = wasPlaying
                    }

                    Log.d("VideoPlayer", "Switched to DASH playback at position $currentPosition")
                } catch (e: Exception) {
                    Log.e("VideoPlayer", "Error switching to DASH playback: ${e.message}", e)
                    // Fall back to direct playback on error
//                    setupDirectPlayback(currentVideo?.directUrl ?: return@validateInitializationSegment)
                }
            }
        }

        private fun setupDirectPlayback(videoUrl: String) {
            Log.d("VideoPlayer", "Falling back to direct playback: $videoUrl")

            player?.let { exoPlayer ->
                try {
                    // Build a simple media item for direct playback
                    val mediaItem = MediaItem.fromUri(videoUrl)

                    // Use a progressive media source
                    val mediaSource = ProgressiveMediaSource.Factory(
                        DefaultDataSource.Factory(binding.root.context)
                    ).createMediaSource(mediaItem)

                    // Set media source and prepare
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    // Show player view
                    binding.thumbnailImage.hide()
                    binding.playerView.show()
                    binding.progressBar.hide()
                } catch (e: Exception) {
                    Log.e("VideoPlayer", "Error in direct playback: ${e.message}", e)
                    // Show error state
                    binding.progressBar.hide()
                    binding.thumbnailImage.show()
                    // Consider showing an error message to the user
                }
            }
        }

        // Release player resources
        fun releasePlayer() {
            Log.d(TAG, "releasePlayer: position=$bindingAdapterPosition")
            cancelPreloadTask()
            player?.release()
            player = null
            binding.playerView.player = null
            playbackStartTime = 0
            Log.d(TAG, "Player resources released")
        }
    }
}