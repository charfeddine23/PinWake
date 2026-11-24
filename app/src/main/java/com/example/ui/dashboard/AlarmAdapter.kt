package com.example.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.databinding.ItemAlarmCardBinding
import com.example.util.DistanceFormatter

class AlarmAdapter(
    private val onToggleActive: (GeoAlarmEntity, Boolean) -> Unit,
    private val onEditClick: (GeoAlarmEntity) -> Unit,
    private val onShareClick: (GeoAlarmEntity) -> Unit,
    private val onDeleteClick: (GeoAlarmEntity) -> Unit
) : ListAdapter<GeoAlarmEntity, AlarmAdapter.AlarmViewHolder>(AlarmDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
        val binding = ItemAlarmCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AlarmViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlarmViewHolder(
        private val binding: ItemAlarmCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GeoAlarmEntity) {
            val ctx = binding.root.context

            binding.cardAlarmRoot.setBackgroundResource(
                if (item.isActive) R.drawable.bg_liquid_glass_card_active
                else R.drawable.bg_liquid_glass_card
            )

            binding.tvAlarmName.text = item.title
            binding.tvAlarmAddress.text = item.address

            val iconRes = when (item.favoriteCategory) {
                FavoriteCategory.HOME -> R.drawable.ic_home
                FavoriteCategory.WORK -> R.drawable.ic_work
                FavoriteCategory.STATION -> R.drawable.ic_train
                FavoriteCategory.CUSTOM -> R.drawable.ic_star
                FavoriteCategory.NONE -> R.drawable.ic_pin_wake
            }
            binding.ivCategoryIcon.setImageResource(iconRes)

            binding.tvRadiusBadge.text = DistanceFormatter.formatRadiusBadge(ctx, item.radiusMeters)

            if (item.isActive) {
                val dist = item.lastDistanceMeters
                if (dist != null && dist <= item.radiusMeters) {
                    binding.tvDistanceBadge.setBackgroundResource(R.drawable.bg_badge_emerald)
                    binding.tvDistanceBadge.setTextColor(
                        ContextCompat.getColor(ctx, R.color.accent_emerald)
                    )
                    binding.tvDistanceBadge.text = ctx.getString(R.string.badge_inside_zone)
                } else {
                    binding.tvDistanceBadge.setBackgroundResource(R.drawable.bg_badge_cyan)
                    binding.tvDistanceBadge.setTextColor(
                        ContextCompat.getColor(ctx, R.color.accent_cyan_glow)
                    )
                    binding.tvDistanceBadge.text = DistanceFormatter.formatDistanceBadge(ctx, dist)
                }
            } else {
                binding.tvDistanceBadge.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                binding.tvDistanceBadge.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_secondary)
                )
                binding.tvDistanceBadge.text = ctx.getString(R.string.status_standby)
            }

            binding.switchAlarmActive.setOnCheckedChangeListener(null)
            binding.switchAlarmActive.isChecked = item.isActive
            binding.switchAlarmActive.setOnCheckedChangeListener { _, isChecked ->
                onToggleActive(item, isChecked)
            }

            binding.btnEditAlarm.setOnClickListener { onEditClick(item) }
            binding.btnShareAlarm.setOnClickListener { onShareClick(item) }
            binding.btnDeleteAlarm.setOnClickListener { onDeleteClick(item) }
            binding.cardAlarmRoot.setOnClickListener { onEditClick(item) }
        }
    }

    private object AlarmDiffCallback : DiffUtil.ItemCallback<GeoAlarmEntity>() {
        override fun areItemsTheSame(oldItem: GeoAlarmEntity, newItem: GeoAlarmEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: GeoAlarmEntity, newItem: GeoAlarmEntity): Boolean =
            oldItem == newItem
    }
}
