package com.insta.presentation.feed.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.insta.domain.models.VideoResponse
import com.insta.presentation.databinding.FragmentVideoBinding
import com.insta.presentation.feed.viewModel.VideoViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VideoFragment : Fragment() {
    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoViewModel by viewModels()
    private val args by navArgs<VideoFragmentArgs>()
    private var player: ExoPlayer? = null

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
        player = ExoPlayer.Builder(requireContext()).build()
        binding.playerView.player = player

        // Build the MediaItem for the first chunk (medium quality)
        val firstChunk = video.firstChunk ?: video.chunks?.firstOrNull()
        val mediaItem = firstChunk?.let {
            it.urls?.medium?.let { uri -> MediaItem.fromUri(uri) }
        } ?: return // Handle the case where there are no chunks

        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }
}