package com.sree.sasi.overlay

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import com.sree.sasi.R

/**
 * Small floating card shown on long-press of Sasi, offering hide options.
 * Hosted as its own tiny overlay window by [CompanionService]; auto-dismissed
 * after 10 seconds there.
 */
class HideMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class HideAction {
        HIDE_15MIN,
        HIDE_1HOUR,
        HIDE_UNTIL_LOCK,
        CLOSE_UNTIL_REMINDER,
        TURN_OFF,
        CANCEL,
    }

    var onAction: ((HideAction) -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundResource(R.drawable.menu_card)
        val p = dp(10)
        setPadding(p, p, p, p)
        addItem("Hide 15 min", HideAction.HIDE_15MIN)
        addItem("Hide 1 hour", HideAction.HIDE_1HOUR)
        addItem("Hide until screen lock", HideAction.HIDE_UNTIL_LOCK)
        addItem("Close until next reminder", HideAction.CLOSE_UNTIL_REMINDER)
        addItem("Turn Sasi off", HideAction.TURN_OFF)
        addItem("Cancel", HideAction.CANCEL)
    }

    private fun addItem(label: String, action: HideAction) {
        val b = Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setOnClickListener { onAction?.invoke(action) }
        }
        addView(
            b,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    /** Rough height of the card, used to anchor it above Sasi. */
    fun estimatedHeightPx(): Int = dp(6 * 48 + 24)

    /** Rough width of the card, used to anchor it at the edge while peeking. */
    fun estimatedWidthPx(): Int = dp(240)

    /** Gentle pop-in when the menu appears. */
    fun animateIn() {
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }
}
