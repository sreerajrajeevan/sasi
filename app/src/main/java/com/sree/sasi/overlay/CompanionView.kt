package com.sree.sasi.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.sree.sasi.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The floating companion itself — a cute cat. A small WRAP_CONTENT window
 * hosts this view; the view owns its position and asks the [WindowManager]
 * to move the window via [bindWindow]. Contains a tinted cat body with ears,
 * a wagging tail, two paws, a face layer, a speech bubble, rich touch
 * gestures (tap / double-tap / rapid taps / long-press menu / drag), and
 * mood-driven cat faces.
 *
 * Default presence is the edge peek: only a sliver of the cat (one ear, one
 * eye, whiskers) shows at a screen edge; a tap slides it out for a visit,
 * and it slides back when the visit ends. Peek mode pauses waypoint movement
 * and idle life (only blinking stays on) to save battery.
 *
 * Visibility is driven by [CompanionService] through [ensureVisible] /
 * [hideImmediately] based on the persisted [VisibilityState]; the view never
 * decides on its own when to come back.
 */
class CompanionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val charHolder: FrameLayout
    private val bodyView: ImageView
    private val faceView: ImageView
    private val pawLeft: ImageView
    private val pawRight: ImageView
    private val tailView: ImageView
    private val bubble: TextView

    /** Callbacks into [CompanionService]. */
    var onUserInteraction: (() -> Unit)? = null
    var onLongPressMenu: (() -> Unit)? = null
    var onDrop: ((Float, Float) -> Unit)? = null
    /** Fired once per real tap reaction (single or double tap) — Phase 3 XP. */
    var onTapReaction: (() -> Unit)? = null
    /** Fired when a drag ends snapped to an edge, so the side persists. */
    var onPeekSideChanged: ((Int) -> Unit)? = null

    /** Behavior prefs, refreshed by the service on every tick. */
    var tapReactionsEnabled: Boolean = true
    var speechBubblesEnabled: Boolean = true
    var hapticEnabled: Boolean = true

    /** Paused by the system (screen off) or while the hide menu is open. */
    var systemPaused: Boolean = false
    var externalPause: Boolean = false

    private var wm: WindowManager? = null
    private var winParams: WindowManager.LayoutParams? = null

    private var baseMood: Mood = Mood.NORMAL
    private var moodOverride: Mood? = null
    private var sleeping = false
    private var dragging = false
    private var hiddenByUser = false

    // Waypoint movement state.
    private var posX = 0f
    private var posY = 200f
    private var targetX = 0f
    private var targetY = 0f
    private var hasTarget = false
    private var moving = false
    private var stateTimer = 1f
    private var phase = 0f
    private var speedPxPerSec = 0f
    private var movementMode = 0 // 0 free, 1 edge, 2 calm, 3 locked
    private var movementFreq = 1 // 0 low, 1 normal, 2 high
    private var pauseUntil = 0L

    private var screenW = 0
    private var screenH = 0
    private var maxX = 0f
    private var maxY = 0f
    private var charSizePx = 0

    // Touch disambiguation state.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downPosX = 0f
    private var downPosY = 0f
    private var downTime = 0L
    private var longPressFired = false
    private var longPressRunnable: Runnable? = null
    private val tapTimes = mutableListOf<Long>()
    private var tapRunnable: Runnable? = null
    private var lastRapidMillis = 0L
    private var reactionUntil = 0L

    // Idle-life timers.
    private var nextLookAt = 0L
    private var lastYawnMillis = 0L

    private var hideBubbleRunnable: Runnable? = null
    private var autoHideRunnable: Runnable? = null
    private var resumeRunnable: Runnable? = null

    // Edge-peek presence: Sasi lives as a sliver at a screen edge instead of
    // floating around. peekMode is the setting; peeking is the live state.
    private var peekMode = false
    private var peeking = false
    /**
     * Reminders-only mode: the cat stays completely hidden and only walks
     * in from the screen edge to deliver a reminder, then walks back out.
     * True while the cat is out for a reminder.
     */
    private var showingReminder = false
    private var walkOutRunnable: Runnable? = null
    private var walkPhase = 0f
    private var walkAnimRunning = false
    /** Active character: 0 = Spider-Man (drops from above), 1 = Cat (runs in). */
    var character: Int = 0
    // Spider-Man views: a web line + the hero, inside a swing holder.
    private lateinit var spideyContainer: FrameLayout
    private lateinit var spideySwing: FrameLayout
    private lateinit var webLine: View
    private lateinit var spideyView: ImageView
    private lateinit var spideyBubble: TextView
    private var spideyDropAnim: ValueAnimator? = null
    private var spideySwingAnim: ValueAnimator? = null
    private var peekSide = 1 // 0 = left edge, 1 = right edge, 2 = bottom edge
    private var geomF = 0f // window geometry fraction: 0 = peeked (sliver), 1 = visiting (full)
    private var slideAnim: ValueAnimator? = null
    private var autoHideToPeek = false
    private var moveLoopRunning = false

    private val moveLoop = object : Runnable {
        override fun run() {
            step(0.05f)
            if (moveLoopRunning) handler.postDelayed(this, 50)
        }
    }

    /** Starts/stops the 50ms step loop. Stopped while peeking (battery). */
    /** Fast paw-cycle run used while the cat sprints in/out for a reminder. */
    private val walkLoop = object : Runnable {
        override fun run() {
            if (!walkAnimRunning) return
            walkPhase += 0.38f // fast sprint cadence
            val lift = dp(10).toFloat()
            pawLeft.translationY = -lift * max(0f, sin(walkPhase))
            pawRight.translationY = -lift * max(0f, sin(walkPhase + PI.toFloat()))
            // Bouncy gallop: body hops with each stride.
            val hop = -abs(sin(walkPhase)) * dp(6)
            bodyView.translationY = hop
            faceView.translationY = hop
            tailView.rotation = sin(walkPhase).toFloat() * 18f // streams behind
            handler.postDelayed(this, 40)
        }
    }

    private fun startRunAnimation() {
        if (walkAnimRunning) return
        walkAnimRunning = true
        handler.post(walkLoop)
    }

    private fun stopRunAnimation() {
        walkAnimRunning = false
        handler.removeCallbacks(walkLoop)
        resetPaws()
        bodyView.translationY = 0f
        faceView.translationY = 0f
        tailView.rotation = 0f
    }

    /**
     * Delivers a reminder via the active character. This is the ONLY way a
     * character appears on screen — everything stays completely hidden
     * otherwise.
     * - Spider-Man (0): drops down from above on a web line, sways, tells
     *   the reminder, then retracts back up.
     * - Cat (1): runs in from the right edge, tells the reminder, then
     *   dashes back out.
     */
    fun walkInForReminder(text: String, visitMillis: Long = 10_000L) {
        walkOutRunnable?.let { handler.removeCallbacks(it) }
        walkOutRunnable = null
        if (character == 0) {
            spideyDropForReminder(text, visitMillis)
        } else {
            catRunInForReminder(text, visitMillis)
        }
        onUserInteraction?.invoke()
    }

    /** Cat: runs in from the right edge with a bouncy sprint. */
    private fun catRunInForReminder(text: String, visitMillis: Long) {
        positionForCatReminder()
        spideyContainer.visibility = GONE
        charHolder.visibility = VISIBLE
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 1f
        }

        // Start shifted right (clipped by the window = invisible), sprint left.
        val cs = charSizePx.toFloat()
        charHolder.translationX = cs
        charHolder.rotation = -10f // lean into the run
        showingReminder = true
        setMoveLoopRunning(true)
        startRunAnimation()

        charHolder.animate().cancel()
        charHolder.animate()
            .translationX(0f)
            .setDuration(900)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                stopRunAnimation()
                charHolder.rotation = 0f
                if (text.isNotEmpty() && speechBubblesEnabled) {
                    speak(text)
                }
                scheduleWalkOut(visitMillis)
            }
            .start()
    }

    /** Spider-Man: drops from above on a web line, sways gently. */
    private fun spideyDropForReminder(text: String, visitMillis: Long) {
        positionForSpideyReminder()
        charHolder.visibility = GONE
        spideyContainer.visibility = VISIBLE
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 1f
        }
        showingReminder = true
        setMoveLoopRunning(true)

        // Reset: web fully retracted, Spidey above the window (invisible).
        spideyDropAnim?.cancel()
        spideySwingAnim?.cancel()
        val dropPx = dp(240).toFloat()
        webLine.scaleY = 0f
        webLine.pivotY = 0f
        spideyView.translationY = -dropPx
        spideySwing.rotation = 0f
        spideyBubble.visibility = GONE
        spideyBubble.alpha = 0f

        // Drop: web extends as Spidey descends (single synced animator).
        spideyDropAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                webLine.scaleY = f
                spideyView.translationY = -dropPx + dropPx * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startSpideySway()
                    if (text.isNotEmpty() && speechBubblesEnabled) {
                        spideySpeak(text)
                    }
                    scheduleWalkOut(visitMillis)
                }
            })
            start()
        }
    }

    /** Gentle side-to-side sway while hanging. */
    private fun startSpideySway() {
        spideySwingAnim?.cancel()
        spideySwing.pivotX = (spideySwing.width / 2).toFloat()
        spideySwing.pivotY = 0f
        spideySwingAnim = ValueAnimator.ofFloat(-7f, 7f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                spideySwing.rotation = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun spideySpeak(text: String) {
        spideyBubble.text = text
        spideyBubble.visibility = VISIBLE
        spideyBubble.animate().cancel()
        spideyBubble.animate().alpha(1f).setDuration(200).start()
    }

    private fun spideyHideBubbleNow() {
        spideyBubble.animate().cancel()
        spideyBubble.visibility = GONE
        spideyBubble.alpha = 0f
    }

    /** Window at the right edge, vertically centered, full cat size. */
    private fun positionForCatReminder() {
        val w = winParams ?: return
        val cs = charSizePx
        val hr = headroomPx()
        w.x = (screenW - cs - edgeMarginPx().toInt()).coerceAtLeast(0)
        w.y = ((screenH - cs - hr) / 2).toInt().coerceAtLeast(0)
        w.width = cs
        w.height = (cs + hr).toInt()
        try {
            wm?.updateViewLayout(this, w)
        } catch (e: Exception) {
        }
    }

    /** Tall window at the top-center for the web drop. */
    private fun positionForSpideyReminder() {
        val w = winParams ?: return
        val ww = dp(220)
        val wh = dp(240 + 96 + 90) // web + hero + bubble
        w.x = (screenW - ww) / 2
        w.y = 0
        w.width = ww
        w.height = wh
        try {
            wm?.updateViewLayout(this, w)
        } catch (e: Exception) {
        }
    }

    private fun scheduleWalkOut(delayMillis: Long) {
        walkOutRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { walkOutAndHide() }
        walkOutRunnable = r
        handler.postDelayed(r, delayMillis)
    }

    /**
     * Sends the character back out and hides completely.
     * Called after the reminder timeout or when the user taps.
     */
    fun walkOutAndHide() {
        walkOutRunnable?.let { handler.removeCallbacks(it) }
        walkOutRunnable = null
        if (!showingReminder) {
            hideCompletely()
            return
        }
        // Dismiss whichever character is actually on screen (the setting
        // may have changed mid-reminder).
        if (spideyContainer.visibility == VISIBLE) {
            spideyRetractAndHide()
        } else {
            catDashOutAndHide()
        }
    }

    /** Cat: dashes back out to the right. */
    private fun catDashOutAndHide() {
        hideBubbleNow()
        startRunAnimation()
        charHolder.animate().cancel()
        charHolder.animate()
            .translationX(charSizePx.toFloat())
            .setDuration(700)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                stopRunAnimation()
                charHolder.rotation = 0f
                hideCompletely()
            }
            .start()
    }

    /** Spider-Man: retracts the web and rises back up. */
    private fun spideyRetractAndHide() {
        spideyHideBubbleNow()
        spideySwingAnim?.cancel()
        spideySwing.rotation = 0f
        spideyDropAnim?.cancel()
        val dropPx = dp(240).toFloat()
        spideyDropAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                webLine.scaleY = f
                spideyView.translationY = -dropPx + dropPx * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    hideCompletely()
                }
            })
            start()
        }
    }

    private fun hideCompletely() {
        showingReminder = false
        charHolder.animate().cancel()
        charHolder.translationX = 0f
        charHolder.rotation = 0f
        resetPaws()
        spideyDropAnim?.cancel()
        spideySwingAnim?.cancel()
        spideyContainer.visibility = GONE
        visibility = GONE
        setMoveLoopRunning(false)
    }

    fun isShowingReminder(): Boolean = showingReminder

    private fun setMoveLoopRunning(running: Boolean) {
        if (running == moveLoopRunning) return
        moveLoopRunning = running
        if (running) handler.post(moveLoop) else handler.removeCallbacks(moveLoop)
    }

    private val dragTouchListener = OnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                longPressFired = false
                downTime = SystemClock.uptimeMillis()
                pauseUntil = SystemClock.uptimeMillis() + 60_000L
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = Runnable {
                    if (!longPressFired) {
                        longPressFired = true
                        if (hapticEnabled) {
                            charHolder.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        onUserInteraction?.invoke()
                        onLongPressMenu?.invoke()
                    }
                }.also { handler.postDelayed(it, 700) }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // Reminders-only: no dragging; the cat is only out briefly.
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                if (longPressFired) {
                    longPressFired = false
                    pauseUntil = 0L
                } else {
                    val pressDuration = SystemClock.uptimeMillis() - downTime
                    pauseUntil = 0L
                    if (pressDuration < 250) {
                        onUserInteraction?.invoke()
                        handleTap()
                    }
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
        // Paws first so the body draws over them; they peek out below.
        pawLeft = ImageView(context).apply {
            layoutParams = LayoutParams(dp(30), dp(18)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(18)
                bottomMargin = dp(2)
            }
            setImageResource(R.drawable.cat_paw)
            isClickable = false
            isFocusable = false
        }
        pawRight = ImageView(context).apply {
            layoutParams = LayoutParams(dp(30), dp(18)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(18)
                bottomMargin = dp(2)
            }
            setImageResource(R.drawable.cat_paw)
            isClickable = false
            isFocusable = false
        }
        // Tail behind the body, sticking out at the bottom-right; wags when idle.
        tailView = ImageView(context).apply {
            layoutParams = LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = -dp(14)
                bottomMargin = dp(2)
            }
            setImageResource(R.drawable.cat_tail)
            pivotX = dp(10).toFloat()
            pivotY = dp(42).toFloat()
            isClickable = false
            isFocusable = false
        }
        bodyView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setImageResource(R.drawable.cat_body)
            isClickable = false
            isFocusable = false
        }
        faceView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setImageResource(R.drawable.cat_face_normal)
            isClickable = false
            isFocusable = false
        }
        charHolder.addView(tailView)
        charHolder.addView(pawLeft)
        charHolder.addView(pawRight)
        charHolder.addView(bodyView)
        charHolder.addView(faceView)
        charHolder.setOnTouchListener(dragTouchListener)

        // Spider-Man: hangs from a web line that extends from the top.
        // The swing holder pivots at the top-center for the sway animation.
        spideyContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = GONE
        }
        spideySwing = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        webLine = View(context).apply {
            val w = dp(4)
            layoutParams = LayoutParams(w, dp(240)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.ink))
        }
        spideyView = ImageView(context).apply {
            val sz = dp(96)
            layoutParams = LayoutParams(sz, sz).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(240)
            }
            setImageResource(R.drawable.spidey)
            isClickable = false
            isFocusable = false
        }
        spideyBubble = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
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
        spideySwing.addView(webLine)
        spideySwing.addView(spideyView)
        spideyContainer.addView(spideySwing)
        spideyContainer.addView(spideyBubble)
        spideyContainer.setOnTouchListener(dragTouchListener)

        addView(bubble)
        addView(charHolder)
        addView(spideyContainer)

        setMoveLoopRunning(true)
        scheduleBlink()
    }

    // Window size is set explicitly via LayoutParams (peek sliver vs full visit),
    // so no headroom hack is needed here; the bubble space is part of the visit geometry.

    fun bindWindow(
        windowManager: WindowManager,
        params: WindowManager.LayoutParams,
        screenWidth: Int,
        screenHeight: Int,
        startX: Float,
        startY: Float,
        peekMode: Boolean,
        peekSide: Int,
    ) {
        wm = windowManager
        winParams = params
        screenW = screenWidth
        screenH = screenHeight
        this.peekMode = peekMode
        this.peekSide = peekSide.coerceIn(0, 2)
        post {
            maxX = (screenW - charSizePx).toFloat().coerceAtLeast(0f)
            maxY = (screenH - charSizePx - headroomPx()).toFloat().coerceAtLeast(0f)
            posX = startX.coerceIn(0f, maxX)
            posY = startY.coerceIn(0f, maxY)
            // Reminders-only: the cat stays completely hidden until a
            // reminder walks it in. No peek sliver, no idle floating.
            this.peekMode = false
            this.peeking = false
            visibility = GONE
        }
    }

    /** Re-reads the real screen size (rotation/resize); call periodically. */
    fun refreshScreenSize() {
        val wmx = wm ?: return
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wmx.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wmx.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        if (w != screenW || h != screenH) {
            screenW = w
            screenH = h
            post {
                updateBounds()
                if (!peeking && hasTarget) {
                    targetX = targetX.coerceIn(0f, maxX)
                    targetY = targetY.coerceIn(0f, maxY)
                }
                // Re-glues the sliver (or visit) to the edge across rotation.
                pushPosition()
            }
        }
    }

    private fun updateBounds() {
        // Bounds for the full-size visit window; the sliver is always inside.
        maxX = (screenW - charSizePx).toFloat().coerceAtLeast(0f)
        maxY = (screenH - charSizePx - headroomPx()).toFloat().coerceAtLeast(0f)
        if (!peeking) {
            posX = posX.coerceIn(0f, maxX)
            posY = posY.coerceIn(0f, maxY)
        }
    }

    fun applyPrefs(
        sizePreset: Int,
        speedPreset: Int,
        @ColorInt color: Int,
        movementMode: Int,
        movementFreq: Int,
    ) {
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
        this.movementMode = movementMode.coerceIn(0, 3)
        this.movementFreq = movementFreq.coerceIn(0, 2)
        if (this.movementMode == 3) {
            moving = false
            hasTarget = false
        }
        bodyView.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        pawLeft.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        pawRight.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        tailView.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /** Mood from [MoodEngine]; SLEEPY also pauses movement. */
    fun setMood(mood: Mood) {
        if (mood == baseMood) return
        baseMood = mood
        sleeping = (mood == Mood.SLEEPY)
        alpha = if (sleeping && !hiddenByUser) 0.9f else 1f
        refreshFace()
    }

    /** Brief expressive override (tap reactions, yawns); auto-clears. */
    fun flashMood(mood: Mood, durationMs: Long) {
        moodOverride = mood
        refreshFace()
        handler.postDelayed({
            moodOverride = null
            refreshFace()
        }, durationMs)
    }

    fun isHiddenByUser(): Boolean = hiddenByUser

    fun currentX(): Float = posX
    fun currentY(): Float = posY

    // ------------------------------------------------------------------
    // Edge-peek presence
    // ------------------------------------------------------------------

    fun isPeeking(): Boolean = peeking

    fun getPeekSide(): Int = peekSide

    /** Setting toggle; takes effect immediately if Sasi is on screen. */
    fun setPeekMode(enabled: Boolean) {
        if (enabled == peekMode) return
        peekMode = enabled
        if (enabled) {
            if (visibility == VISIBLE && !hiddenByUser && !dragging) slideOut()
        } else {
            slideAnim?.let { it.removeAllListeners(); it.cancel() }
            slideAnim = null
            if (peeking) {
                peeking = false
                geomF = 1f
                val vr = visitGeom()
                posX = vr.x.coerceIn(0f, maxX)
                posY = vr.y.coerceIn(0f, maxY)
                pushPosition()
            }
            setMoveLoopRunning(true)
        }
    }

    /** Side change; re-glues the sliver if currently peeking. */
    fun setPeekSide(side: Int) {
        val s = side.coerceIn(0, 2)
        if (s == peekSide) return
        peekSide = s
        pushPosition()
    }

    /**
     * Immediate peek positioning (boot/restore) — no animation, no flash.
     * Only a ~40dp sliver of the cat stays visible at the chosen edge.
     */
    fun setPeeking(peek: Boolean, side: Int) {
        peekSide = side.coerceIn(0, 2)
        slideAnim?.let { it.removeAllListeners(); it.cancel() }
        slideAnim = null
        peeking = peek
        geomF = if (peek) 0f else 1f
        if (peek) {
            moving = false
            hasTarget = false
            resetPose()
            setMoveLoopRunning(false)
        } else {
            setMoveLoopRunning(true)
        }
        pushPosition()
    }

    /** Slide the cat fully on screen for a visit (from peek). */
    fun slideIn() {
        if (!peekMode || !peeking) return
        peeking = false
        setMoveLoopRunning(true)
        startSlide(toVisit = true)
    }

    /** Slide back to the edge sliver (animated). */
    fun slideOut() {
        if (!peekMode) return
        hideBubbleNow()
        peeking = true
        startSlide(toVisit = false)
    }

    private fun startSlide(toVisit: Boolean) {
        slideAnim?.let { it.removeAllListeners(); it.cancel() }
        val fromF = geomF
        val toF = if (toVisit) 1f else 0f
        moving = false
        hasTarget = false
        if (fromF == toF) {
            if (!toVisit) {
                resetPose()
                setMoveLoopRunning(false)
            }
            pushPosition()
            return
        }
        slideAnim = ValueAnimator.ofFloat(fromF, toF).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                applyGeom(anim.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (slideAnim === animation) {
                        slideAnim = null
                        if (!toVisit) {
                            resetPose()
                            setMoveLoopRunning(false)
                        }
                        pushPosition()
                    }
                }
            })
            start()
        }
    }

    // ---- Clamp-proof peek geometry ----
    // Some devices clamp overlay windows fully on-screen, so a peek sliver
    // cannot be achieved by positioning the window off-screen. Instead the
    // window itself is sized to the sliver (the cat clips at the window
    // bounds) and the window never leaves the screen.
    private fun sliverPx() = dp(40).toFloat()
    private fun edgeMarginPx() = dp(16).toFloat()
    private fun headroomPx() = dp(64).toFloat()

    private data class WinGeom(val x: Float, val y: Float, val w: Int, val h: Int)

    /** Window geometry for the peeked state: sliver at the edge, always on-screen. */
    private fun peekGeom(): WinGeom {
        val s = sliverPx()
        val cs = charSizePx.toFloat()
        return when (peekSide) {
            0 -> WinGeom(0f, posY.coerceIn(0f, maxY.coerceAtLeast(0f)), s.toInt(), cs.toInt())
            2 -> WinGeom(
                posX.coerceIn(0f, maxX.coerceAtLeast(0f)),
                screenH - s, cs.toInt(), s.toInt()
            )
            else -> WinGeom(
                screenW - s,
                posY.coerceIn(0f, maxY.coerceAtLeast(0f)),
                s.toInt(), cs.toInt()
            )
        }
    }

    /** Window geometry for the visiting state: full cat, on-screen. */
    private fun visitGeom(): WinGeom {
        val cs = charSizePx.toFloat()
        val m = edgeMarginPx()
        val hr = headroomPx()
        return when (peekSide) {
            0 -> WinGeom(
                m,
                (posY.coerceIn(0f, maxY.coerceAtLeast(0f)) - hr).coerceAtLeast(0f),
                cs.toInt(), (cs + hr).toInt()
            )
            2 -> WinGeom(
                posX.coerceIn(0f, maxX.coerceAtLeast(0f)),
                (screenH - cs - hr - m).coerceAtLeast(0f),
                cs.toInt(), (cs + hr).toInt()
            )
            else -> WinGeom(
                (screenW - cs - m).coerceAtLeast(0f),
                (posY.coerceIn(0f, maxY.coerceAtLeast(0f)) - hr).coerceAtLeast(0f),
                cs.toInt(), (cs + hr).toInt()
            )
        }
    }

    /** Applies the interpolated window geometry for fraction f (0=peek, 1=visit). */
    private fun applyGeom(f: Float) {
        geomF = f
        val params = winParams ?: return
        val pr = peekGeom()
        val vr = visitGeom()
        params.x = (pr.x + (vr.x - pr.x) * f).toInt()
        params.y = (pr.y + (vr.y - pr.y) * f).toInt()
        params.width = (pr.w + (vr.w - pr.w) * f).toInt()
        params.height = (pr.h + (vr.h - pr.h) * f).toInt()
        try {
            wm?.updateViewLayout(this, params)
        } catch (e: Exception) {
            // Window already removed.
        }
    }

    /** Like [scheduleAutoHide], but returns to the peek sliver instead. */
    fun scheduleReturnToPeek(delayMillis: Long) {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideToPeek = true
        autoHideRunnable = Runnable { slideOut() }
            .also { handler.postDelayed(it, delayMillis) }
    }

    /** A tap on the peek sliver invites Sasi out; true if consumed. */
    private fun consumePeekTap(): Boolean {
        if (!peeking) return false
        slideIn()
        if (tapReactionsEnabled) flashMood(Mood.HAPPY, 1200)
        scheduleReturnToPeek(30_000L)
        onTapReaction?.invoke()
        return true
    }

    /**
     * Shows a speech bubble. While peeking, only bubbles tied to a slide-out
     * (tap visits, real warnings) are shown — idle chatter stays silent so
     * the cat truly rests at the edge.
     */
    fun speak(text: String, allowWhilePeeking: Boolean = false) {
        if (peeking && !allowWhilePeeking) return
        bubble.text = text
        bubble.translationX = 0f
        bubble.visibility = VISIBLE
        bubble.animate().cancel()
        bubble.animate().alpha(1f).setDuration(200).start()
        hideBubbleRunnable?.let { handler.removeCallbacks(it) }
        hideBubbleRunnable = Runnable {
            bubble.animate().alpha(0f).setDuration(300)
                .withEndAction {
                    bubble.visibility = GONE
                    bubble.translationX = 0f
                }
                .start()
        }.also { handler.postDelayed(it, 4500) }
        // While peeking the window sits mostly off-screen; pull the bubble
        // fully on-screen so timer/warning bubbles stay readable.
        post { clampBubbleOnScreen() }
    }

    /** Keeps the speech bubble inside the display (peek mode). */
    private fun clampBubbleOnScreen() {
        if (bubble.visibility != VISIBLE || bubble.width == 0 || screenW == 0) return
        val loc = IntArray(2)
        bubble.getLocationOnScreen(loc)
        val margin = dp(8)
        val dx = when {
            loc[0] < margin -> (margin - loc[0]).toFloat()
            loc[0] + bubble.width > screenW - margin ->
                (screenW - margin - loc[0] - bubble.width).toFloat()
            else -> 0f
        }
        bubble.translationX = dx
    }

    private fun hideBubbleNow() {
        hideBubbleRunnable?.let { handler.removeCallbacks(it) }
        hideBubbleRunnable = null
        bubble.animate().cancel()
        bubble.visibility = GONE
        bubble.alpha = 0f
        bubble.translationX = 0f
    }

    /** Make sure the overlay is on screen (idempotent). */
    fun ensureVisible() {
        // Reminders-only: "visible" just means enabled. The cat stays hidden
        // until a reminder walks it in via walkInForReminder().
        hiddenByUser = false
        autoHideRunnable?.let { handler.removeCallbacks(it) }
    }

    /** Pop back to deliver a warning: slide out, then return to peek. */
    fun showForWarning() {
        ensureVisible()
        animate().cancel()
        if (peekMode) {
            slideIn()
        } else {
            alpha = 0f
            animate().alpha(1f).setDuration(300).start()
        }
    }

    /** Hide again automatically after [delayMillis] with no interaction. */
    fun scheduleAutoHide(delayMillis: Long) {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        // In peek mode a visit ends by sliding back to the edge sliver.
        autoHideToPeek = peekMode
        autoHideRunnable = Runnable {
            if (autoHideToPeek && peekMode) slideOut() else hideImmediately()
        }.also { handler.postDelayed(it, delayMillis) }
    }

    /** Hide right now, no farewell. */
    fun hideImmediately() {
        hiddenByUser = true
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        hideBubbleNow()
        slideAnim?.let { it.removeAllListeners(); it.cancel() }
        slideAnim = null
        animate().cancel()
        alpha = 0f
        visibility = GONE
    }

    fun destroy() {
        slideAnim?.let { it.removeAllListeners(); it.cancel() }
        slideAnim = null
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------
    // Touch gestures
    // ------------------------------------------------------------------

    private fun scheduleResume(delayMs: Long) {
        resumeRunnable?.let { handler.removeCallbacks(it) }
        resumeRunnable = Runnable { pauseUntil = 0L }
            .also { handler.postDelayed(it, delayMs) }
    }

    private fun handleTap() {
        // Reminders-only: a tap dismisses the reminder early (cat walks out).
        if (showingReminder) {
            if (hapticEnabled) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            walkOutAndHide()
            return
        }
    }

    private fun doSingleTap() {
        if (consumePeekTap()) return
        if (!tapReactionsEnabled) return
        when (random.nextInt(4)) {
            0 -> {
                // Happy flash + tiny jump.
                flashMood(Mood.HAPPY, 900)
                reactionUntil = SystemClock.uptimeMillis() + 400L
                charHolder.animate().cancel()
                charHolder.animate().scaleX(1.12f).scaleY(1.12f).setDuration(130)
                    .withEndAction {
                        charHolder.animate().scaleX(1f).scaleY(1f).setDuration(170).start()
                    }.start()
            }
            1 -> {
                // Blink right now.
                faceView.setImageResource(R.drawable.cat_face_blink)
                handler.postDelayed({ refreshFace() }, 140)
            }
            2 -> {
                // Wave: rotation wiggle.
                charHolder.animate().cancel()
                charHolder.animate().rotationBy(10f).setDuration(120).withEndAction {
                    charHolder.animate().rotationBy(-20f).setDuration(120).withEndAction {
                        charHolder.animate().rotationBy(10f).setDuration(120).withEndAction {
                            charHolder.rotation = 0f
                        }.start()
                    }.start()
                }.start()
            }
            else -> {
                if (speechBubblesEnabled) {
                    speak(listOf("\uD83D\uDC40", "hehe", "*boop*").random(random))
                }
            }
        }
        onTapReaction?.invoke()
    }

    private fun doDoubleTap() {
        if (consumePeekTap()) return
        if (!tapReactionsEnabled) return
        flashMood(Mood.EXCITED, 1000)
        reactionUntil = SystemClock.uptimeMillis() + 500L
        charHolder.animate().cancel()
        charHolder.animate().scaleX(1.25f).scaleY(1.25f).setDuration(150).withEndAction {
            charHolder.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
        onTapReaction?.invoke()
    }

    // ------------------------------------------------------------------
    // Movement + idle life (driven by the existing 50ms moveLoop)
    // ------------------------------------------------------------------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private fun pickWaypoint() {
        val margin = dp(24).toFloat()
        val w = maxX - margin
        val h = maxY - margin
        if (w <= margin || h <= margin) {
            hasTarget = false
            moving = false
            stateTimer = idlePause()
            return
        }
        when (movementMode) {
            3 -> { // locked: no autonomous movement
                hasTarget = false
                moving = false
                return
            }
            2 -> { // mostly still: wander within 60dp
                val r = dp(60).toFloat()
                targetX = (posX + (random.nextFloat() * 2 - 1) * r).coerceIn(margin, maxX - margin)
                targetY = (posY + (random.nextFloat() * 2 - 1) * r).coerceIn(margin, maxY - margin)
            }
            1 -> { // edge only: snap to an edge
                when (random.nextInt(4)) {
                    0 -> { targetX = margin; targetY = margin + random.nextFloat() * (h - margin) }
                    1 -> { targetX = maxX - margin; targetY = margin + random.nextFloat() * (h - margin) }
                    2 -> { targetX = margin + random.nextFloat() * (w - margin); targetY = margin }
                    else -> { targetX = margin + random.nextFloat() * (w - margin); targetY = maxY - margin }
                }
            }
            else -> { // free roam: 70% edges/corners/lower third
                if (random.nextFloat() < 0.7f) {
                    when (random.nextInt(3)) {
                        0 -> { // left/right edge
                            targetX = if (random.nextBoolean()) margin else maxX - margin
                            targetY = margin + random.nextFloat() * (h - margin)
                        }
                        1 -> { // top/bottom edge
                            targetX = margin + random.nextFloat() * (w - margin)
                            targetY = if (random.nextBoolean()) margin else maxY - margin
                        }
                        else -> { // lower third
                            targetX = margin + random.nextFloat() * (w - margin)
                            targetY = h * 0.66f + random.nextFloat() * (h - margin) * 0.34f
                        }
                    }
                } else {
                    targetX = margin + random.nextFloat() * (w - margin)
                    targetY = margin + random.nextFloat() * (h - margin)
                }
            }
        }
        hasTarget = true
        moving = true
    }

    private fun idlePause(): Float = when (movementFreq) {
        0 -> 8f + random.nextFloat() * 7f // low: 8-15s
        2 -> 1f + random.nextFloat() * 2f // high: 1-3s
        else -> 3f + random.nextFloat() * 5f // normal: 3-8s
    }

    private fun pushPosition() {
        applyGeom(if (peeking) 0f else 1f)
    }

    private fun step(dt: Float) {
        val now = SystemClock.uptimeMillis()
        // Peeking: fully still except blinking (separate cheap handler).
        if (peeking) {
            resetPose()
            return
        }
        if (sleeping || hiddenByUser || systemPaused || externalPause || now < pauseUntil) {
            resetPose()
            return
        }
        if (dragging) return

        if (moving && hasTarget) {
            val dx = targetX - posX
            val dy = targetY - posY
            val dist = hypot(dx, dy)
            val stepDist = speedPxPerSec * dt
            if (dist <= max(stepDist, dp(2).toFloat())) {
                posX = targetX
                posY = targetY
                hasTarget = false
                moving = false
                stateTimer = idlePause()
                resetPose()
            } else {
                // Smooth glide; paws alternate lifts, body sways gently.
                posX += dx / dist * stepDist
                posY += dy / dist * stepDist
                phase += dt * 2f * PI.toFloat() * 2.4f // ~2.4 steps per second
                val lift = dp(6).toFloat()
                pawLeft.translationY = -lift * max(0f, sin(phase))
                pawRight.translationY = -lift * max(0f, sin(phase + PI.toFloat()))
                val sway = sin(phase * 2f) * dp(2)
                bodyView.translationY = sway
                faceView.translationY = sway
                faceView.translationX = 0f
                charHolder.scaleX = 1f
                charHolder.scaleY = 1f
            }
            pushPosition()
        } else {
            if (peekMode) {
                // Peek presence replaces waypoint wandering entirely; idle
                // life (breathing, tail) only runs while visiting.
                stateTimer = idlePause()
                resetPaws()
                idleLife(now)
            } else {
                stateTimer -= dt
                if (stateTimer <= 0f) pickWaypoint()
                resetPaws()
                idleLife(now)
            }
        }
    }

    private fun resetPaws() {
        pawLeft.translationY = 0f
        pawRight.translationY = 0f
        bodyView.translationY = 0f
        faceView.translationY = 0f
    }

    private fun resetPose() {
        resetPaws()
        tailView.rotation = 0f
        charHolder.translationY = 0f
        charHolder.rotation = 0f
        charHolder.scaleX = 1f
        charHolder.scaleY = 1f
        faceView.translationX = 0f
    }

    /** Subtle idle behaviors: breathing, looking around, occasional yawns. */
    private fun idleLife(now: Long) {
        if (hiddenByUser || dragging || sleeping || moving) return
        // Breathing: gentle 1.00 <-> 1.03 scale (paused during tap reactions).
        if (now >= reactionUntil) {
            val s = 1f + 0.015f * (0.5f + 0.5f * sin(now / 900.0).toFloat())
            charHolder.scaleX = s
            charHolder.scaleY = s
        }
        // Look left/right every 40-100s.
        if (nextLookAt == 0L) nextLookAt = now + 40_000L + random.nextInt(60_000)
        if (now >= nextLookAt) {
            nextLookAt = now + 40_000L + random.nextInt(60_000)
            faceView.translationX = (if (random.nextBoolean()) 1f else -1f) * dp(5)
            handler.postDelayed({ faceView.translationX = 0f }, 1200)
        }
        // Rare yawn when tired or sleepy: first one no sooner than 3 min in.
        val tired = baseMood == Mood.TIRED || baseMood == Mood.SLEEPY
        if (!tired) {
            lastYawnMillis = 0L
        } else if (lastYawnMillis == 0L) {
            lastYawnMillis = now
        } else if (now - lastYawnMillis > 180_000L && random.nextFloat() < 0.02f) {
            lastYawnMillis = now
            flashMood(Mood.TIRED, 2000)
            if (speechBubblesEnabled) speak("yawn\u2026")
        }
        // Tail wag: slow and content, only when fully on screen.
        if (!peeking && !hiddenByUser && visibility == VISIBLE) {
            tailView.rotation = sin(now / 700.0).toFloat() * 12f
        }
    }

    private fun scheduleBlink() {
        handler.postDelayed({
            if (!sleeping && !dragging && !hiddenByUser && moodOverride == null) {
                faceView.setImageResource(R.drawable.cat_face_blink)
                handler.postDelayed({ refreshFace() }, 140)
            }
            scheduleBlink()
        }, (2000 + random.nextInt(4000)).toLong())
    }

    private fun refreshFace() {
        faceView.setImageResource(
            when (moodOverride ?: baseMood) {
                Mood.HAPPY -> R.drawable.cat_face_happy
                Mood.EXCITED -> R.drawable.cat_face_excited
                Mood.PROUD -> R.drawable.cat_face_excited
                Mood.PLAYFUL -> R.drawable.cat_face_happy
                Mood.TIRED -> R.drawable.cat_face_tired
                Mood.SLEEPY -> R.drawable.cat_face_sleepy
                Mood.WORRIED -> R.drawable.cat_face_worried
                Mood.RESTING -> R.drawable.cat_face_sleepy
                Mood.FOCUSED -> R.drawable.cat_face_happy
                else -> R.drawable.cat_face_normal // NORMAL, BORED
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
