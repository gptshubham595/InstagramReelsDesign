package com.insta.presentation.feed.adapter

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
class FeedAdapter @Inject constructor(
    private val lifecycleOwner: LifecycleOwner,
    private val videoCacheManager: VideoCacheManager,
    private val exoPlayerProvider: ExoPlayerProvider,
    val videos: MutableList<VideoResponse> = mutableListOf(),
    val onVideoClick: (String) -> Unit
) : RecyclerView.Adapter<FeedAdapter.ReelViewHolder>() {

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
        preloadFirstChunks()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReelViewHolder {
        val binding = ItemReelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReelViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount(): Int = videos.size

    // Call when new videos are added to preload their first chunks
    fun preloadFirstChunks() {
        lifecycleOwner.lifecycleScope.launch {
            videos.forEach { video ->
                if (!videoCacheManager.isFirstChunkCached(video.id ?: "")) {
                    videoCacheManager.downloadFirstChunk(video)
                }
            }
        }
    }

    // Register visible items for playback decisions
    fun onItemVisible(position: Int) {
        visibleItems.add(position)
        considerPlayback(position)
    }

    // Remove from visible items when scrolled away
    fun onItemInvisible(position: Int) {
        visibleItems.remove(position)

        // If this was the currently playing item, stop it
        if (currentlyPlayingHolder?.bindingAdapterPosition == position) {
            releaseCurrentPlayer()
        }
    }

    // Decide which item should be playing
    private fun considerPlayback(position: Int) {
        // If we're already playing this position, do nothing
        if (currentlyPlayingHolder?.bindingAdapterPosition == position) {
            return
        }

        // Release any previous player
        releaseCurrentPlayer()

        // Find the view holder and play the video
        val holder = getViewHolderForPosition(position)
        holder?.let {
            currentlyPlayingHolder = it
            it.prepareAndPlay()
        }
    }

    private fun getViewHolderForPosition(position: Int): ReelViewHolder? {
        return try {
            val recyclerView = currentlyPlayingHolder?.binding?.root?.parent as? RecyclerView
            recyclerView?.findViewHolderForAdapterPosition(position) as? ReelViewHolder
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseCurrentPlayer() {
        currentlyPlayingHolder?.releasePlayer()
        currentlyPlayingHolder = null
    }

    // Cleanup when adapter is detached
    fun release() {
        releaseCurrentPlayer()
        handler.removeCallbacksAndMessages(null)
    }

    inner class ReelViewHolder(val binding: ItemReelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var player: ExoPlayer? = null
        private var currentVideo: VideoResponse? = null
        private var preloadTaskActive: Boolean = false
        private val preloadRunnable = Runnable { preloadNextChunks() }

        fun bind(video: VideoResponse) {
            currentVideo = video

            // Reset view state
            binding.playerView.visibility = View.GONE
            binding.thumbnailImage.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE

            // Load thumbnail using Glide
            Glide.with(binding.root.context)
                .load(video.thumbnail)
                .into(binding.thumbnailImage)

            // Set click listener for the video item
            binding.root.setOnClickListener {
                video.id?.let { videoId -> onVideoClick(videoId) }
            }

            // Check if first chunk is already cached
            checkFirstChunkAndPrepare()
        }

        // Check if we can start playing immediately
        private fun checkFirstChunkAndPrepare() {
            val videoId = currentVideo?.id ?: return

            if (videoCacheManager.isFirstChunkCached(videoId)) {
                // First chunk is available, prepare player but don't autoplay
                // It will only start when this item becomes visible
                setupPlayerWithCachedChunk()
            } else {
                // Show thumbnail until first chunk is downloaded
                binding.thumbnailImage.visibility = View.VISIBLE
                binding.playerView.visibility = View.GONE

                // Request download of first chunk
                lifecycleOwner.lifecycleScope.launch {
                    currentVideo?.let { videoCacheManager.downloadFirstChunk(it) }
                }
            }
        }

        // Set up player with cached first chunk
        private fun setupPlayerWithCachedChunk() {
            val videoId = currentVideo?.id ?: return
            val firstChunkPath = videoCacheManager.getFirstChunkPath(videoId) ?: return

            initializePlayer()

            player?.let { exoPlayer ->
                // Create a media source for the locally cached chunk
                val mediaSource = exoPlayerProvider.createFirstChunkMediaSource(firstChunkPath)
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()

                // Don't auto-play, we'll play when visible
                exoPlayer.playWhenReady = false
            }
        }

        // Initialize player with proper configuration
        private fun initializePlayer() {
            if (player == null) {
                player = exoPlayerProvider.getPlayer().apply {
                    // Configure player view
                    binding.playerView.player = this
                    binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                    // Player listeners
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                }

                                Player.STATE_READY -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.thumbnailImage.visibility = View.GONE
                                    binding.playerView.visibility = View.VISIBLE
                                }

                                Player.STATE_ENDED -> {
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
            }
        }

        // Prepare player and start playing
        fun prepareAndPlay() {
            val videoId = currentVideo?.id ?: return

            // If first chunk is cached, play from local file first
            if (videoCacheManager.isFirstChunkCached(videoId)) {
                if (player == null) {
                    setupPlayerWithCachedChunk()
                }

                // Start playback and schedule preloading
                player?.playWhenReady = true
                schedulePreload()
            } else {
                // No cached chunk yet, show thumbnail and try to download
                binding.thumbnailImage.visibility = View.VISIBLE
                binding.playerView.visibility = View.GONE

                lifecycleOwner.lifecycleScope.launch {
                    currentVideo?.let { videoCacheManager.downloadFirstChunk(it) }

                    // Check again after attempted download
                    if (videoCacheManager.isFirstChunkCached(videoId)) {
                        setupPlayerWithCachedChunk()
                        player?.playWhenReady = true
                        schedulePreload()
                    }
                }
            }
        }

        // Schedule preloading of next chunks after delay
        private fun schedulePreload() {
            // Cancel any existing preload task
            cancelPreloadTask()

            // Schedule new preload task
            preloadTaskActive = true
            handler.postDelayed(preloadRunnable, PRELOAD_DELAY_MS)
        }

        // Cancel scheduled preload
        private fun cancelPreloadTask() {
            if (preloadTaskActive) {
                handler.removeCallbacks(preloadRunnable)
                preloadTaskActive = false
            }
        }

        // Preload next chunks and switch to DASH
        private fun preloadNextChunks() {
            val video = currentVideo ?: return
            val dashManifestUrl = video.dashManifest ?: return

            // Mark as active
            preloadTaskActive = false

            // Start preloading next chunks
            lifecycleOwner.lifecycleScope.launch {
                videoCacheManager.preloadNextChunks(video)
            }

            // Switch to DASH playback for continuous streaming
            switchToDashPlayback(dashManifestUrl)
        }

        // Switch from single chunk to DASH manifest
        private fun switchToDashPlayback(dashManifestUrl: String) {
            player?.let { exoPlayer ->
                // Remember current playback position and state
                val currentPosition = exoPlayer.currentPosition
                val wasPlaying = exoPlayer.isPlaying

                // Create DASH media source
                val dashMediaSource = exoPlayerProvider.createDashMediaSource(dashManifestUrl)

                // Set the new media source
                exoPlayer.setMediaSource(dashMediaSource)
                exoPlayer.prepare()

                // Restore position and playback state
                exoPlayer.seekTo(currentPosition)
                exoPlayer.playWhenReady = wasPlaying

                Log.d(TAG, "Switched to DASH playback at position $currentPosition")
            }
        }

        // Release player resources
        fun releasePlayer() {
            cancelPreloadTask()
            player?.release()
            player = null
            binding.playerView.player = null
        }
    }

}