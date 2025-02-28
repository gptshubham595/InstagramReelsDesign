package com.insta.presentation.feed.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.widget.ViewPager2
import com.insta.core.di.ExoPlayerProvider
import com.insta.core.managers.VideoCacheManager
import com.insta.presentation.databinding.FragmentFeedBinding
import com.insta.presentation.feed.adapter.FeedAdapter
import com.insta.presentation.feed.viewModel.FeedViewModel
import com.insta.presentation.feed.viewModel.VideoViewModel
import com.insta.presentation.helper.hide
import com.insta.presentation.helper.show
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class FeedFragment : Fragment() {
    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!
    private val feedViewModel: FeedViewModel by viewModels()
    private val videoViewModel: VideoViewModel by viewModels()

    @Inject
    lateinit var videoCacheManager: VideoCacheManager

    @Inject
    lateinit var exoPlayerProvider: ExoPlayerProvider

    private lateinit var feedAdapter: FeedAdapter

    // Track the currently visible item
    private var currentVisibleItem = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeFeedState()
    }

    private fun setupRecyclerView() {
        // Create adapter with lifecycle owner for coroutine scope
        feedAdapter = FeedAdapter(
            lifecycleOwner = viewLifecycleOwner,
            videoCacheManager = videoCacheManager,
            exoPlayerProvider = exoPlayerProvider
        )

        binding.viewPager.apply {
            adapter = feedAdapter
            orientation = ViewPager2.ORIENTATION_VERTICAL

            // Set up scroll state changes to track visible items
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    // Mark the previously visible item as invisible
                    if (position != currentVisibleItem) {
                        feedAdapter.onItemInvisible(currentVisibleItem)
                    }

                    // Update current position and notify adapter
                    currentVisibleItem = position
                    feedAdapter.onItemVisible(position)

                    // Load more content when reaching end
                    if (position >= feedAdapter.itemCount - 2) {
                        feedViewModel.fetchNextPage()
                    }
                }
            })
        }
    }

    private fun observeFeedState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.feedState.collectLatest { state ->
                    when {
                        state.isLoading && feedAdapter.itemCount == 0 -> {
                            binding.progressBar.show()
                            binding.errorText.hide()
                        }

                        state.error != null && feedAdapter.itemCount == 0 -> {
                            binding.progressBar.hide()
                            binding.errorText.show()
                            binding.errorText.text = state.error
                        }

                        else -> {
                            binding.progressBar.hide()
                            binding.errorText.hide()

                            val newVideos = state.videos.filter { video ->
                                feedAdapter.videos.none { it.id == video.id }
                            }

                            if (newVideos.isNotEmpty()) {
                                val startPosition = feedAdapter.videos.size
                                feedAdapter.videos.addAll(newVideos)
                                feedAdapter.notifyItemRangeInserted(startPosition, newVideos.size)

                                // Start preloading chunks for new videos
                                feedAdapter.preloadFirstChunks()

                                // If this is the first load, make sure our current item is visible
                                if (feedAdapter.videos.size == newVideos.size) {
                                    feedAdapter.onItemVisible(0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Notify adapter about visibility change to pause playback
        feedAdapter.onItemInvisible(currentVisibleItem)
    }

    override fun onResume() {
        super.onResume()
        // Resume playback when fragment is visible again
        if (::feedAdapter.isInitialized) {
            feedAdapter.onItemVisible(currentVisibleItem)
        }
    }

    override fun onDestroyView() {
        if (::feedAdapter.isInitialized) {
            feedAdapter.release()
        }
        binding.viewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
