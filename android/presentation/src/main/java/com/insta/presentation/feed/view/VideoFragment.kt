package com.insta.presentation.feed.view

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.insta.core.di.ExoPlayerProvider
import com.insta.core.managers.VideoCacheManager
import com.insta.domain.models.VideoResponse
import com.insta.presentation.databinding.FragmentVideoBinding
import com.insta.presentation.feed.viewModel.VideoViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.media3.common.util.UnstableApi
@AndroidEntryPoint
class VideoFragment : Fragment() {
    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoViewModel by viewModels()
    private val args by navArgs<VideoFragmentArgs>()

    @Inject
    lateinit var videoCacheManager: VideoCacheManager

    @Inject
    lateinit var exoPlayerProvider: ExoPlayerProvider

    private var player: ExoPlayer? = null
    private var dashManifestUrl: String? = null
    private var preloadHandler = Handler(Looper.getMainLooper())
    private var preloadRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.getVideo(args.videoId)
        observeVideoState()
    }

    private fun observeVideoState() {
        lifecycleScope.launch {
            viewModel.videoState.collectLatest { state ->
                when {
                    state.isLoading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.errorText.visibility = View.GONE
                    }

                    state.error != null -> {
                        binding.progressBar.visibility = View.GONE
                        binding.errorText.visibility = View.VISIBLE
                        binding.errorText.text = state.error
                    }

                    else -> {
                        binding.progressBar.visibility = View.GONE
                        binding.errorText.visibility = View.GONE
                        state.video?.let { initializePlayer(it) }
                    }
                }
            }
        }
    }

    private fun initializePlayer(video: VideoResponse) {
        // Load thumbnail first (will be hidden when video starts playing)
        Glide.with(requireContext())
            .load(video.thumbnail)
            .into(binding.thumbnailImage)

        // Show thumbnail initially
        binding.thumbnailImage.visibility = View.VISIBLE
        binding.playerView.visibility = View.INVISIBLE

        // Save the DASH manifest URL for later use
        dashManifestUrl = video.dashManifest

        // Initialize player
        player = exoPlayerProvider.getPlayer().apply {
            binding.playerView.player = this
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

            // Set event listeners
            setPlaybackListener()
        }

        // Check if first chunk is already cached
        val videoId = video.id ?: return
        if (videoCacheManager.isFirstChunkCached(videoId)) {
            // Start with cached first chunk for immediate playback
            startWithCachedChunk(videoId, video)
        } else {
            // Download first chunk and then play
            lifecycleScope.launch {
                binding.progressBar.visibility = View.VISIBLE
                videoCacheManager.downloadFirstChunk(video)

                // After download attempt, check again
                if (videoCacheManager.isFirstChunkCached(videoId)) {
                    startWithCachedChunk(videoId, video)
                } else {
                    // If still not available, use DASH directly
                    startWithDashManifest(video)
                }
            }
        }
    }

    private fun setPlaybackListener() {
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }

                    Player.STATE_READY -> {
                        binding.progressBar.visibility = View.GONE
                        binding.thumbnailImage.visibility = View.GONE
                        binding.playerView.visibility = View.VISIBLE

                        // Schedule preload of next chunks after 2 seconds
                        scheduleNextChunksPreload()
                    }

                    Player.STATE_ENDED -> {
                        // Loop video
                        player?.seekTo(0)
                        player?.play()
                    }

                    else -> { /* Do nothing */
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("VideoFragment", "Player error: ${error.message}")

                // Try to switch to DASH if playing from cache fails
                if (dashManifestUrl != null) {
                    startWithDashManifest()
                }
            }
        })
    }

    private fun startWithCachedChunk(videoId: String, video: VideoResponse? = null) {
        val chunkPath = videoCacheManager.getFirstChunkPath(videoId) ?: return

        player?.let { exoPlayer ->
            // Create media source for the locally cached chunk
            val mediaSource = exoPlayerProvider.createFirstChunkMediaSource(chunkPath)

            // Set the media source and prepare
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        Log.d("VideoFragment", "Playing from cached first chunk")
    }

    private fun startWithDashManifest(video: VideoResponse? = null) {
        val manifestUrl = video?.dashManifest ?: dashManifestUrl ?: return

        player?.let { exoPlayer ->
            // Create DASH media source
            val dashMediaSource = exoPlayerProvider.createDashMediaSource(manifestUrl)

            // Set media source and prepare
            exoPlayer.setMediaSource(dashMediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        Log.d("VideoFragment", "Playing from DASH manifest")
    }

    private fun scheduleNextChunksPreload() {
        // Cancel any existing preload task
        preloadRunnable?.let { preloadHandler.removeCallbacks(it) }

        // Schedule preloading of next chunks after 2 seconds
        preloadRunnable = Runnable {
            val video = viewModel.videoState.value.video ?: return@Runnable

            // Preload next chunks
            lifecycleScope.launch {
                videoCacheManager.preloadNextChunks(video)

                // Switch to DASH playback for smoother streaming
                if (dashManifestUrl != null && player?.isPlaying == true) {
                    switchToDashPlayback()
                }
            }
        }.also {
            preloadHandler.postDelayed(it, 2000) // 2 seconds delay
        }
    }

    private fun switchToDashPlayback() {
        val manifestUrl = dashManifestUrl ?: return

        player?.let { exoPlayer ->
            // Remember current position and playing state
            val currentPosition = exoPlayer.currentPosition
            val wasPlaying = exoPlayer.isPlaying

            // Create and set DASH media source
            val dashMediaSource = exoPlayerProvider.createDashMediaSource(manifestUrl)
            exoPlayer.setMediaSource(dashMediaSource)
            exoPlayer.prepare()

            // Restore position and playback state
            exoPlayer.seekTo(currentPosition)
            exoPlayer.playWhenReady = wasPlaying

            Log.d("VideoFragment", "Switched to DASH playback at position $currentPosition")
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroyView() {
        // Clean up resources
        preloadRunnable?.let { preloadHandler.removeCallbacks(it) }
        player?.release()
        player = null
        binding.playerView.player = null
        super.onDestroyView()
        _binding = null
    }
}