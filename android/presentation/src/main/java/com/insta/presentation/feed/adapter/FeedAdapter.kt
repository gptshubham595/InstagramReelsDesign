package com.insta.presentation.feed.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.insta.domain.models.VideoResponse
import com.insta.presentation.databinding.ItemReelBinding

class FeedAdapter(
    val videos: MutableList<VideoResponse>,
    val onVideoClick: (String) -> Unit
) : RecyclerView.Adapter<FeedAdapter.ReelViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReelViewHolder {
        val binding = ItemReelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReelViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount(): Int = videos.size

    inner class ReelViewHolder(private val binding: ItemReelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(video: VideoResponse) {
            // Load thumbnail using Glide
            Glide.with(binding.root.context)
                .load(video.thumbnail)
                .into(binding.thumbnailImage)

            // Set click listener for the video item
            binding.root.setOnClickListener {
                video.id?.let { p1 -> onVideoClick(p1) }
            }
        }
    }
}