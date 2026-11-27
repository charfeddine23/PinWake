package com.example.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.example.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object TransitionHelper {

    fun startActivitySmooth(context: Context, intent: Intent) {
        val started = runCatching {
            val options = ActivityOptions.makeCustomAnimation(
                context,
                R.anim.pinwake_activity_enter,
                R.anim.pinwake_activity_exit
            )
            context.startActivity(intent, options.toBundle())
            true
        }.getOrDefault(false)
        if (!started) {
            runCatching { context.startActivity(intent) }
        }
    }

    @Suppress("DEPRECATION")
    fun finishSmooth(activity: Activity) {
        activity.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.pinwake_activity_close_enter,
                R.anim.pinwake_activity_close_exit
            )
        } else {
            activity.overridePendingTransition(
                R.anim.pinwake_activity_close_enter,
                R.anim.pinwake_activity_close_exit
            )
        }
    }

    fun animateLayoutChange(container: ViewGroup, durationMs: Long = 210L) {
        if ("robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) return
        if (!container.isLaidOut) return
        val transition = AutoTransition().apply {
            duration = durationMs
            interpolator = FastOutSlowInInterpolator()
        }
        TransitionManager.beginDelayedTransition(container, transition)
    }

    fun configureBottomSheetDialog(dialog: BottomSheetDialog, expandImmediately: Boolean = true) {
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            bottomSheet.setBackgroundColor(Color.TRANSPARENT)
            if (expandImmediately) {
                runCatching {
                    val behavior = BottomSheetBehavior.from(bottomSheet)
                    behavior.skipCollapsed = true
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachLuxuryPressFeedback(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(85L)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200L)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                }
            }
            false
        }
    }
}
