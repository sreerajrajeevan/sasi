package com.sree.sasi.overlay

import android.content.Context
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.sree.sasi.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The floating companion itself. A small WRAP_CONTENT window hosts this view;
 * the view owns its position and asks the [WindowManager] to move the window
 * via [bindWindow]. Contains a tinted body, two stepping feet, a face layer
 * (normal/happy/sleepy/blink), a speech bubble, tap-to-bye, drag handling,
 * smooth glide movement and sleep mode.
 *
 * Visibility model: the user can tap Sasi to say bye and hide it. It pops back
 * (with [showForWarning]) only when the service has a warning to deliver, then
 * hides itself again after [scheduleAutoHide] elapses.
 */
class CompanionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Mood { NORMAL, HAPPY, SLEEPY }

    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val charHolder: FrameLayout
    private val bodyView: ImageView
    private val faceView: ImageView
    private val footLeft: ImageView
    private val footRight: ImageView
    private val bubble: TextView

    private var wm: WindowManager? = null
    private var winParams: WindowManager.LayoutParams? = null

    private var mood = Mood.NORMAL
    private var sleeping = false
    private var dragging = false
    private var hiddenByUser = false

    private var posX = 0f
    private var posY = 200f
    private var velX = 0f
    private var velY = 0f
    private var speedPxPerSec = 0f
    private var moving = true
    private var stateTimer = 5f
    private var phase = 0f
    private var pauseUntil = 0L

    private var screenW = 0
    private var screenH = 0
    private var maxX = 0f
    private var maxY = 0f
    private var charSizePx = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var downPosX = 0f
    private var downPosY = 0f

    private var hideBubbleRunnable: Runnable? = null
    private var autoHideRunnable: Runnable? = null
    private var resumeRunnable: Runnable? = null

    private val moveLoop = object : Runnable {
        override fun run() {
            step(0.05f)
            handler.postDelayed(this, 50)
        }
    }

    private val dragTouchListener = OnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downPosX = posX
                downPosY = posY
                pauseUntil = SystemClock.uptimeMillis() + 60_000L // paused until released
                autoHideRunnable?.let { handler.removeCallbacks(it) }
                resumeRunnable?.let { handler.removeCallbacks(it) }
                v.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    posX = (downPosX + dx).coerceIn(0f, maxX)
                    posY = (downPosY + dy).coerceIn(0f, maxY)
                    pushPosition()
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    pauseUntil = 0L
                    v.performClick()
                    byeAndHide()
                } else {
                    dragging = false
                    resumeRunnable?.let { handler.removeCallbacks(it) }
                    resumeRunnable = Runnable {
                        pauseUntil = 0L
                        scheduleAutoHide(60_000L)
                    }.also { handler.postDelayed(it, 3000) } // resume auto-walk after 3s
                }
                true
            }
            else -> false
        }
    }

    init {
        charSizePx = dp(96)
        speedPxPerSec = dp(55).toFloat()

        bubble = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            setBackgroundResource(R.drawable.bubble_bg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            maxWidth = dp(220)
            visibility = GONE
            alpha = 0f
            isClickable = false
            isFocusable = false
        }

        charHolder = FrameLayout(context).apply {
            layoutParams = LayoutParams(charSizePx, charSizePx)
                .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            isClickable = true
            isFocusable = false
        }
        // Feet first so the body draws over them; they peek out below.
        footLeft = ImageView(context).apply {
            layoutParams = LayoutParams(dp(30), dp(18)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(18)
                bottomMargin = dp(2)
            }
            setImageResource(R.drawable.sasi_foot)
            isClickable = false
            isFocusable = false
        }
        footRight = ImageView(context).apply {
            layoutParams = LayoutParams(dp(30), dp(18)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(18)
                bottomMargin = dp(2)
            }
            setImageResource(R.drawable.sasi_foot)
            isClickable = false
            isFocusable = false
        }
        bodyView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setImageResource(R.drawable.sasi_body)
            isClickable = false
            isFocusable = false
        }
        faceView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setImageResource(R.drawable.sasi_face_normal)
            isClickable = false
            isFocusable = false
        }
        charHolder.addView(footLeft)
        charHolder.addView(footRight)
        charHolder.addView(bodyView)
        charHolder.addView(faceView)
        charHolder.setOnTouchListener(dragTouchListener)

        addView(bubble)
        addView(charHolder)

        pickDirection()
        handler.post(moveLoop)
        scheduleBlink()
    }

    /** Reserves headroom above the character for the speech bubble. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight + dp(64))
    }

    fun bindWindow(
        windowManager: WindowManager,
        params: WindowManager.LayoutParams,
        screenWidth: Int,
        screenHeight: Int,
        startX: Float,
        startY: Float,
    ) {
        wm = windowManager
        winParams = params
        screenW = screenWidth
        screenH = screenHeight
        post {
            updateBounds()
            posX = startX.coerceIn(0f, maxX)
            posY = startY.coerceIn(0f, maxY)
            pushPosition()
        }
    }

    private fun updateBounds() {
        maxX = (screenW - width).toFloat().coerceAtLeast(0f)
        maxY = (screenH - height).toFloat().coerceAtLeast(0f)
        posX = posX.coerceIn(0f, maxX)
        posY = posY.coerceIn(0f, maxY)
    }

    fun applyPrefs(sizePreset: Int, speedPreset: Int, @ColorInt color: Int) {
        val newSize = dp(
            when (sizePreset) {
                0 -> 72
                2 -> 128
                else -> 96
            },
        )
        if (newSize != charSizePx) {
            charSizePx = newSize
            charHolder.layoutParams = (charHolder.layoutParams as LayoutParams).apply {
                width = charSizePx
                height = charSizePx
            }
            charHolder.requestLayout()
            post { updateBounds(); pushPosition() }
        }
        speedPxPerSec = dp(
            when (speedPreset) {
                0 -> 30
                2 -> 90
                else -> 55
            },
        ).toFloat()
        val angle = atan2(velY, velX)
        velX = cos(angle) * speedPxPerSec
        velY = sin(angle) * speedPxPerSec
        bodyView.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        footLeft.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        footRight.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    fun setSleeping(isSleeping: Boolean) {
        if (sleeping == isSleeping) return
        sleeping = isSleeping
        mood = if (isSleeping) Mood.SLEEPY else Mood.NORMAL
        alpha = if (isSleeping && !hiddenByUser) 0.9f else 1f
        refreshFace()
        if (!isSleeping) {
            moving = true
            stateTimer = 4f
            pickDirection()
        }
    }

    fun isHiddenByUser(): Boolean = hiddenByUser

    fun speak(text: String) {
        bubble.text = text
        bubble.visibility = VISIBLE
        bubble.animate().cancel()
        bubble.animate().alpha(1f).setDuration(200).start()
        hideBubbleRunnable?.let { handler.removeCallbacks(it) }
        hideBubbleRunnable = Runnable {
            bubble.animate().alpha(0f).setDuration(300)
                .withEndAction { bubble.visibility = GONE }
                .start()
        }.also { handler.postDelayed(it, 4500) }
    }

    /** Tap behavior: say bye, then hide until the next warning. */
    fun byeAndHide() {
        if (hiddenByUser) return
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        speak("Bye! \uD83D\uDC4B")
        handler.postDelayed({ hideNow() }, 1100)
    }

    /** Pop back onto the screen to deliver a warning. */
    fun showForWarning() {
        hiddenByUser = false
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        if (visibility != VISIBLE) {
            visibility = VISIBLE
        }
        animate().cancel()
        if (alpha < 1f) {
            alpha = 0f
            animate().alpha(1f).setDuration(300).start()
        }
        post { updateBounds(); pushPosition() }
    }

    /** Hide again automatically after [delayMillis] with no interaction. */
    fun scheduleAutoHide(delayMillis: Long) {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = Runnable { hideNow() }
            .also { handler.postDelayed(it, delayMillis) }
    }

    private fun hideNow() {
        if (hiddenByUser && visibility == GONE) return
        hiddenByUser = true
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        animate().cancel()
        animate().alpha(0f).setDuration(250)
            .withEndAction { visibility = GONE }
            .start()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private fun pickDirection() {
        val angle = random.nextFloat() * 2f * PI.toFloat()
        velX = cos(angle) * speedPxPerSec
        velY = sin(angle) * speedPxPerSec
    }

    private fun pushPosition() {
        val params = winParams ?: return
        params.x = posX.toInt()
        params.y = posY.toInt()
        try {
            wm?.updateViewLayout(this, params)
        } catch (e: Exception) {
            // Window already removed.
        }
    }

    private fun step(dt: Float) {
        if (sleeping || hiddenByUser || SystemClock.uptimeMillis() < pauseUntil) {
            resetPose()
            return
        }
        if (dragging) return

        stateTimer -= dt
        if (stateTimer <= 0f) {
            if (moving) {
                moving = false
                stateTimer = 3f + random.nextFloat() * 5f // idle 3-8s
            } else {
                moving = true
                pickDirection()
                stateTimer = 4f + random.nextFloat() * 6f // walk 4-10s
            }
        }

        if (moving) {
            // Smooth glide; feet alternate lifts, body sways gently.
            val margin = dp(16).toFloat()
            posX += velX * dt
            posY += velY * dt
            if (posX < margin) { posX = margin; velX = abs(velX) }
            if (posX > maxX - margin) { posX = maxX - margin; velX = -abs(velX) }
            if (posY < margin) { posY = margin; velY = abs(velY) }
            if (posY > maxY - margin) { posY = maxY - margin; velY = -abs(velY) }
            phase += dt * 2f * PI.toFloat() * 2.4f // ~2.4 steps per second
            val lift = dp(6).toFloat()
            footLeft.translationY = -lift * max(0f, sin(phase))
            footRight.translationY = -lift * max(0f, sin(phase + PI.toFloat()))
            val sway = sin(phase * 2f) * dp(1.5f)
            bodyView.translationY = sway
            faceView.translationY = sway
            pushPosition()
        } else {
            resetPose()
        }
    }

    private fun resetPose() {
        footLeft.translationY = 0f
        footRight.translationY = 0f
        bodyView.translationY = 0f
        faceView.translationY = 0f
        charHolder.translationY = 0f
        charHolder.rotation = 0f
    }

    private fun scheduleBlink() {
        handler.postDelayed({
            if (!sleeping && !dragging && !hiddenByUser) {
                faceView.setImageResource(R.drawable.sasi_face_blink)
                handler.postDelayed({ refreshFace() }, 140)
            }
            scheduleBlink()
        }, (2000 + random.nextInt(4000)).toLong())
    }

    private fun refreshFace() {
        faceView.setImageResource(
            when (mood) {
                Mood.HAPPY -> R.drawable.sasi_face_happy
                Mood.SLEEPY -> R.drawable.sasi_face_sleepy
                Mood.NORMAL -> R.drawable.sasi_face_normal
            },
        )
    }

    companion object {
        fun colorForTheme(index: Int, context: Context): Int =
            ContextCompat.getColor(
                context,
                when (index) {
                    1 -> R.color.theme_mint
                    2 -> R.color.theme_lavender
                    3 -> R.color.theme_sky
                    else -> R.color.theme_peach
                },
            )
    }
}
