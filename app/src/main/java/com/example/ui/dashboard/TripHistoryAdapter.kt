package com.example.ui.dashboard

import android.annotation.SuppressLint
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.local.entity.TripHistoryEntity
import com.example.data.local.entity.TripOutcome
import com.example.databinding.ItemTripHistoryBinding
import java.util.Date

class TripHistoryAdapter(
    private val onReplayClick: (TripHistoryEntity) -> Unit = {}
) : ListAdapter<TripHistoryEntity, TripHistoryAdapter.HistoryViewHolder>(DiffCallback) {

    private val seenTripIds = mutableSetOf<Long>()

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

        init {
            attachLuxuryPressFeedback(binding.cardHistoryRoot)
            attachLuxuryPressFeedback(binding.btnReplayTrip)
        }

        fun bind(item: TripHistoryEntity) {
            val ctx = binding.root.context
            val density = ctx.resources.displayMetrics.density
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

            binding.btnReplayTrip.setOnClickListener { onReplayClick(item) }
            binding.cardHistoryRoot.setOnClickListener { onReplayClick(item) }

            if (seenTripIds.add(item.id)) {
                binding.cardHistoryRoot.alpha = 0f
                binding.cardHistoryRoot.translationY = 10f * density
                binding.cardHistoryRoot.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                binding.cardHistoryRoot.alpha = 1f
                binding.cardHistoryRoot.translationY = 0f
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun attachLuxuryPressFeedback(view: View) {
            view.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        target.animate().cancel()
                        target.animate()
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(80L)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        target.animate().cancel()
                        target.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(180L)
                            .setInterpolator(OvershootInterpolator(1.6f))
                            .start()
                    }
                }
                false
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
