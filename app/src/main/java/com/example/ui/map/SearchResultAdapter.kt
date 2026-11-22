package com.example.ui.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.data.remote.dto.NominatimPlaceDto
import com.example.databinding.ItemSearchResultBinding

class SearchResultAdapter(
    private val onPlaceClick: (NominatimPlaceDto) -> Unit
) : ListAdapter<NominatimPlaceDto, SearchResultAdapter.PlaceViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaceViewHolder(
        private val binding: ItemSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NominatimPlaceDto) {
            binding.tvPlaceTitle.text = item.shortTitle()
            binding.tvPlaceAddress.text = item.displayName
            binding.root.setOnClickListener { onPlaceClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<NominatimPlaceDto>() {
        override fun areItemsTheSame(
            oldItem: NominatimPlaceDto,
            newItem: NominatimPlaceDto
        ): Boolean = oldItem.placeId == newItem.placeId

        override fun areContentsTheSame(
            oldItem: NominatimPlaceDto,
            newItem: NominatimPlaceDto
        ): Boolean = oldItem == newItem
    }
}
