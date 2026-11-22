package com.example.ui.dashboard

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.local.entity.TripHistoryEntity
import com.example.data.local.entity.TripOutcome
import com.example.databinding.ItemTripHistoryBinding
import java.util.Date

class TripHistoryAdapter :
    ListAdapter<TripHistoryEntity, TripHistoryAdapter.HistoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemTripHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(
        private val binding: ItemTripHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TripHistoryEntity) {
            val ctx = binding.root.context
            binding.tvHistoryTitle.text = item.alarmTitle
            val timeFormatted = DateFormat.getTimeFormat(ctx).format(Date(item.triggeredAt))
            val dateFormatted = DateFormat.getMediumDateFormat(ctx).format(Date(item.triggeredAt))
            binding.tvHistoryTime.text = "$dateFormatted · $timeFormatted · ${item.radiusMeters} m"

            when (item.outcome) {
                TripOutcome.ARRIVED -> {
                    binding.tvHistoryOutcome.setBackgroundResource(R.drawable.bg_badge_emerald)
                    binding.tvHistoryOutcome.setTextColor(
                        ContextCompat.getColor(ctx, R.color.accent_emerald)
                    )
                    binding.tvHistoryOutcome.setText(R.string.outcome_arrived)
                }
                TripOutcome.SNOOZED -> {
                    binding.tvHistoryOutcome.setBackgroundResource(R.drawable.bg_badge_cyan)
                    binding.tvHistoryOutcome.setTextColor(
                        ContextCompat.getColor(ctx, R.color.accent_cyan_glow)
                    )
                    binding.tvHistoryOutcome.setText(R.string.outcome_snoozed)
                }
                TripOutcome.CANCELLED -> {
                    binding.tvHistoryOutcome.setBackgroundResource(R.drawable.bg_badge_rose)
                    binding.tvHistoryOutcome.setTextColor(
                        ContextCompat.getColor(ctx, R.color.accent_rose)
                    )
                    binding.tvHistoryOutcome.setText(R.string.outcome_cancelled)
                }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TripHistoryEntity>() {
        override fun areItemsTheSame(
            oldItem: TripHistoryEntity,
            newItem: TripHistoryEntity
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: TripHistoryEntity,
            newItem: TripHistoryEntity
        ): Boolean = oldItem == newItem
    }
}
