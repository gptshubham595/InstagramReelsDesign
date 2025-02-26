package com.insta.presentation.feed.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.insta.presentation.databinding.FragmentFeedBinding
import com.insta.presentation.feed.adapter.FeedAdapter
import com.insta.presentation.feed.viewModel.FeedViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FeedFragment : Fragment() {
    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FeedViewModel by viewModels()
    private lateinit var feedAdapter: FeedAdapter

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
        feedAdapter = FeedAdapter(mutableListOf()) { videoId ->
            val action = FeedFragmentDirections.actionFeedFragmentToVideoFragment(videoId)
            findNavController().navigate(action)
        }

        binding.viewPager.apply {
            adapter = feedAdapter
        }
    }

    private fun observeFeedState() {
        lifecycleScope.launch {
            viewModel.feedState.collectLatest { state ->
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
//                        feedAdapter.videos.addAll(state.videos)
//                        feedAdapter.notifyItemRangeInserted(
//                            feedAdapter.videos.size - state.videos.size,
//                            state.videos.size
//                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}