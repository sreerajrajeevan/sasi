package com.sree.sasi.overlay

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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The floating companion itself. A small WRAP_CONTENT window hosts this view;
 * the view owns its position and asks the [WindowManager] to move the window
 * via [bindWindow]. Contains a tinted body, two stepping feet, a face layer,
 * a speech bubble, rich touch gestures (tap / double-tap / rapid taps /
 * long-press menu / drag), waypoint-based movement with idle life
 * (breathing, looking around, yawning), and mood-driven faces.
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
    private val footLeft: ImageView
    private val footRight: ImageView
    private val bubble: TextView

    /** Callbacks into [CompanionService]. */
    var onUserInteraction: (() -> Unit)? = null
    var onLongPressMenu: (() -> Unit)? = null
    var onDrop: ((Float, Float) -> Unit)? = null
    /** Fired once per real tap reaction (single or double tap) — Phase 3 XP. */
    var onTapReaction: (() -> Unit)? = null

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
                longPressFired = false
                downTime = SystemClock.uptimeMillis()
                downRawX = event.rawX
                downRawY = event.rawY
                downPosX = posX
                downPosY = posY
                pauseUntil = SystemClock.uptimeMillis() + 60_000L // paused until released
                autoHideRunnable?.let { handler.removeCallbacks(it) }
                resumeRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = Runnable {
                    if (!dragging && !longPressFired) {
                        longPressFired = true
                        if (hapticEnabled) {
                            charHolder.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        onUserInteraction?.invoke()
                        onLongPressMenu?.invoke()
                    }
                }.also { handler.postDelayed(it, 700) }
                v.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    moodOverride = Mood.HAPPY // picked-up expression
                    refreshFace()
                    onUserInteraction?.invoke()
                }
                if (dragging) {
                    posX = (downPosX + dx).coerceIn(0f, maxX)
                    posY = (downPosY + dy).coerceIn(0f, maxY)
                    pushPosition()
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                when {
                    longPressFired -> {
                        longPressFired = false
                        scheduleResume(1500)
                    }
                    dragging -> {
                        dragging = false
                        moodOverride = null
                        refreshFace()
                        // Landing bounce.
                        reactionUntil = SystemClock.uptimeMillis() + 400L
                        charHolder.animate().cancel()
                        charHolder.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120)
                            .withEndAction {
                                charHolder.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                            }.start()
                        onDrop?.invoke(posX, posY)
                        onUserInteraction?.invoke()
                        scheduleResume(3000)
                        scheduleAutoHide(60_000L)
                    }
                    else -> {
                        val pressDuration = SystemClock.uptimeMillis() - downTime
                        pauseUntil = 0L
                        v.performClick()
                        if (pressDuration < 250) {
                            onUserInteraction?.invoke()
                            handleTap()
                        }
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
                if (hasTarget) {
                    targetX = targetX.coerceIn(0f, maxX)
                    targetY = targetY.coerceIn(0f, maxY)
                }
                pushPosition()
            }
        }
    }

    private fun updateBounds() {
        maxX = (screenW - width).toFloat().coerceAtLeast(0f)
        maxY = (screenH - height).toFloat().coerceAtLeast(0f)
        posX = posX.coerceIn(0f, maxX)
        posY = posY.coerceIn(0f, maxY)
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
        footLeft.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        footRight.setColorFilter(color, PorterDuff.Mode.SRC_IN)
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

    /** Make sure the overlay is on screen (idempotent). */
    fun ensureVisible() {
        hiddenByUser = false
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        if (visibility != VISIBLE) visibility = VISIBLE
        animate().cancel()
        alpha = 1f
        post { updateBounds(); pushPosition() }
    }

    /** Pop back with a fade-in, e.g. to deliver a warning. */
    fun showForWarning() {
        ensureVisible()
        animate().cancel()
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
    }

    /** Hide again automatically after [delayMillis] with no interaction. */
    fun scheduleAutoHide(delayMillis: Long) {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = Runnable { hideImmediately() }
            .also { handler.postDelayed(it, delayMillis) }
    }

    /** Hide right now, no farewell. */
    fun hideImmediately() {
        hiddenByUser = true
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        hideBubbleRunnable?.let { handler.removeCallbacks(it) }
        bubble.visibility = GONE
        bubble.alpha = 0f
        animate().cancel()
        alpha = 0f
        visibility = GONE
    }

    fun destroy() {
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
        val now = SystemClock.uptimeMillis()
        tapTimes.removeAll { now - it > 2000 }
        tapTimes.add(now)
        if (tapTimes.size >= 3) {
            // Rapid tapping: playfully annoyed, with a cooldown.
            tapTimes.clear()
            tapRunnable?.let { handler.removeCallbacks(it) }
            if (now - lastRapidMillis > 30_000L) {
                lastRapidMillis = now
                flashMood(Mood.WORRIED, 1500)
                if (speechBubblesEnabled) speak("ok ok \uD83D\uDE05")
            }
            return
        }
        // Wait 400ms to disambiguate single vs double vs rapid taps.
        tapRunnable?.let { handler.removeCallbacks(it) }
        tapRunnable = Runnable {
            when (tapTimes.size) {
                1 -> doSingleTap()
                2 -> doDoubleTap()
                else -> Unit
            }
            tapTimes.clear()
        }.also { handler.postDelayed(it, 400) }
    }

    private fun doSingleTap() {
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
                faceView.setImageResource(R.drawable.sasi_face_blink)
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
        val now = SystemClock.uptimeMillis()
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
                // Smooth glide; feet alternate lifts, body sways gently.
                posX += dx / dist * stepDist
                posY += dy / dist * stepDist
                phase += dt * 2f * PI.toFloat() * 2.4f // ~2.4 steps per second
                val lift = dp(6).toFloat()
                footLeft.translationY = -lift * max(0f, sin(phase))
                footRight.translationY = -lift * max(0f, sin(phase + PI.toFloat()))
                val sway = sin(phase * 2f) * dp(2)
                bodyView.translationY = sway
                faceView.translationY = sway
                faceView.translationX = 0f
                charHolder.scaleX = 1f
                charHolder.scaleY = 1f
            }
            pushPosition()
        } else {
            stateTimer -= dt
            if (stateTimer <= 0f) pickWaypoint()
            resetFeet()
            idleLife(now)
        }
    }

    private fun resetFeet() {
        footLeft.translationY = 0f
        footRight.translationY = 0f
        bodyView.translationY = 0f
        faceView.translationY = 0f
    }

    private fun resetPose() {
        resetFeet()
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
    }

    private fun scheduleBlink() {
        handler.postDelayed({
            if (!sleeping && !dragging && !hiddenByUser && moodOverride == null) {
                faceView.setImageResource(R.drawable.sasi_face_blink)
                handler.postDelayed({ refreshFace() }, 140)
            }
            scheduleBlink()
        }, (2000 + random.nextInt(4000)).toLong())
    }

    private fun refreshFace() {
        faceView.setImageResource(
            when (moodOverride ?: baseMood) {
                Mood.HAPPY -> R.drawable.sasi_face_happy
                Mood.EXCITED -> R.drawable.sasi_face_excited
                Mood.PROUD -> R.drawable.sasi_face_excited
                Mood.PLAYFUL -> R.drawable.sasi_face_happy
                Mood.TIRED -> R.drawable.sasi_face_tired
                Mood.SLEEPY -> R.drawable.sasi_face_sleepy
                Mood.WORRIED -> R.drawable.sasi_face_worried
                Mood.RESTING -> R.drawable.sasi_face_sleepy
                Mood.FOCUSED -> R.drawable.sasi_face_happy
                else -> R.drawable.sasi_face_normal // NORMAL, BORED
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
