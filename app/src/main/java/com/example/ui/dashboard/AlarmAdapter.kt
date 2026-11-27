package com.example.ui.dashboard

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.databinding.ItemAlarmCardBinding
import com.example.util.DistanceFormatter
import java.util.Locale

class AlarmAdapter(
    private val onToggleActive: (GeoAlarmEntity, Boolean) -> Unit,
    private val onEditClick: (GeoAlarmEntity) -> Unit,
    private val onNavigateClick: (GeoAlarmEntity) -> Unit,
    private val onShareClick: (GeoAlarmEntity) -> Unit,
    private val onDeleteClick: (GeoAlarmEntity) -> Unit
) : ListAdapter<GeoAlarmEntity, AlarmAdapter.AlarmViewHolder>(AlarmDiffCallback) {

    private val seenAlarmIds = mutableSetOf<Long>()

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

    override fun onViewRecycled(holder: AlarmViewHolder) {
        holder.stopActiveEffects()
        super.onViewRecycled(holder)
    }

    inner class AlarmViewHolder(
        private val binding: ItemAlarmCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var radarPulseAnimator: AnimatorSet? = null
        private var dotPulseAnimator: ObjectAnimator? = null

        init {
            attachLuxuryPressFeedback(binding.cardAlarmRoot)
            attachLuxuryPressFeedback(binding.btnEditAlarm)
            attachLuxuryPressFeedback(binding.btnNavigateAlarm)
            attachLuxuryPressFeedback(binding.btnShareAlarm)
            attachLuxuryPressFeedback(binding.btnDeleteAlarm)
        }

        fun bind(item: GeoAlarmEntity) {
            val ctx = binding.root.context
            val density = ctx.resources.displayMetrics.density

            binding.cardAlarmRoot.setBackgroundResource(
                if (item.isActive) R.drawable.bg_alarm_card_luxury_active
                else R.drawable.bg_alarm_card_luxury
            )
            binding.cardAlarmRoot.elevation = if (item.isActive) 12f * density else 3f * density

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

            val iconTintColor = ContextCompat.getColor(
                ctx,
                when (item.favoriteCategory) {
                    FavoriteCategory.HOME -> R.color.accent_emerald
                    FavoriteCategory.WORK -> R.color.accent_amber
                    FavoriteCategory.STATION -> R.color.accent_cyan_glow
                    FavoriteCategory.CUSTOM -> R.color.accent_cyan_glow
                    FavoriteCategory.NONE -> R.color.accent_cyan
                }
            )
            ImageViewCompat.setImageTintList(
                binding.ivCategoryIcon,
                ColorStateList.valueOf(iconTintColor)
            )

            val categoryLabel = when (item.favoriteCategory) {
                FavoriteCategory.HOME -> ctx.getString(R.string.fav_home)
                FavoriteCategory.WORK -> ctx.getString(R.string.fav_work)
                FavoriteCategory.STATION -> ctx.getString(R.string.fav_station)
                FavoriteCategory.CUSTOM -> ctx.getString(R.string.fav_custom)
                FavoriteCategory.NONE -> ctx.getString(R.string.alarm_category_default)
            }
            val statusLabel = if (item.isActive) {
                ctx.getString(R.string.alarm_card_armed)
            } else {
                ctx.getString(R.string.status_standby)
            }
            binding.tvAlarmCategoryEyebrow.text = ctx.getString(
                R.string.alarm_eyebrow_format,
                categoryLabel.uppercase(Locale.getDefault()),
                statusLabel.uppercase(Locale.getDefault())
            )

            binding.tvRadiusBadge.text = DistanceFormatter.formatRadiusBadge(ctx, item.radiusMeters)

            val toneTitle = item.ringtoneTitle?.trim()
            if (!toneTitle.isNullOrBlank()) {
                binding.tvRingtoneBadge.visibility = View.VISIBLE
                binding.tvRingtoneBadge.text = ctx.getString(R.string.ringtone_badge_format, toneTitle)
            } else {
                binding.tvRingtoneBadge.visibility = View.GONE
            }

            binding.badgeVibrateOn.visibility =
                if (item.vibrateEnabled) View.VISIBLE else View.GONE

            if (item.isActive) {
                val dist = item.lastDistanceMeters
                val insideZone = dist != null && dist <= item.radiusMeters
                if (insideZone) {
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
                binding.dotAlarmStatus.setBackgroundResource(R.drawable.bg_badge_cyan)
                binding.tvAlarmCategoryEyebrow.setTextColor(
                    ContextCompat.getColor(ctx, R.color.accent_cyan_glow)
                )
                startActiveEffects()
            } else {
                binding.tvDistanceBadge.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                binding.tvDistanceBadge.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_secondary)
                )
                binding.tvDistanceBadge.text = ctx.getString(R.string.status_standby)
                binding.dotAlarmStatus.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                binding.tvAlarmCategoryEyebrow.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_secondary)
                )
                stopActiveEffects()
            }

            binding.switchAlarmActive.contentDescription =
                ctx.getString(R.string.cd_switch_alarm, item.title)
            binding.switchAlarmActive.setOnCheckedChangeListener(null)
            binding.switchAlarmActive.isChecked = item.isActive
            binding.switchAlarmActive.setOnCheckedChangeListener { _, isChecked ->
                onToggleActive(item, isChecked)
            }

            binding.btnEditAlarm.setOnClickListener { onEditClick(item) }
            binding.btnNavigateAlarm.setOnClickListener { onNavigateClick(item) }
            binding.btnShareAlarm.setOnClickListener { onShareClick(item) }
            binding.btnDeleteAlarm.setOnClickListener { onDeleteClick(item) }
            binding.cardAlarmRoot.setOnClickListener { onEditClick(item) }

            if (seenAlarmIds.add(item.id)) {
                binding.cardAlarmRoot.alpha = 0f
                binding.cardAlarmRoot.translationY = 12f * density
                binding.cardAlarmRoot.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                binding.cardAlarmRoot.alpha = 1f
                binding.cardAlarmRoot.translationY = 0f
            }
        }

        private fun startActiveEffects() {
            if (radarPulseAnimator?.isRunning == true) return
            stopActiveEffects()

            binding.viewRadarHalo.visibility = View.VISIBLE
            binding.viewRadarHalo.scaleX = 0.92f
            binding.viewRadarHalo.scaleY = 0.92f
            binding.viewRadarHalo.alpha = 0.72f

            val scaleX = ObjectAnimator.ofFloat(binding.viewRadarHalo, View.SCALE_X, 0.92f, 1.32f).apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                duration = 1600L
            }
            val scaleY = ObjectAnimator.ofFloat(binding.viewRadarHalo, View.SCALE_Y, 0.92f, 1.32f).apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                duration = 1600L
            }
            val fadeOut = ObjectAnimator.ofFloat(binding.viewRadarHalo, View.ALPHA, 0.72f, 0f).apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                duration = 1600L
            }

            radarPulseAnimator = AnimatorSet().apply {
                interpolator = AccelerateDecelerateInterpolator()
                playTogether(scaleX, scaleY, fadeOut)
                start()
            }

            dotPulseAnimator = ObjectAnimator.ofFloat(binding.dotAlarmStatus, View.ALPHA, 1f, 0.35f).apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                duration = 950L
                start()
            }
        }

        fun stopActiveEffects() {
            radarPulseAnimator?.cancel()
            radarPulseAnimator = null
            dotPulseAnimator?.cancel()
            dotPulseAnimator = null

            binding.viewRadarHalo.visibility = View.GONE
            binding.viewRadarHalo.scaleX = 1f
            binding.viewRadarHalo.scaleY = 1f
            binding.viewRadarHalo.alpha = 0f
            binding.dotAlarmStatus.alpha = 1f
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

    private object AlarmDiffCallback : DiffUtil.ItemCallback<GeoAlarmEntity>() {
        override fun areItemsTheSame(oldItem: GeoAlarmEntity, newItem: GeoAlarmEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: GeoAlarmEntity, newItem: GeoAlarmEntity): Boolean =
            oldItem == newItem
    }
}
