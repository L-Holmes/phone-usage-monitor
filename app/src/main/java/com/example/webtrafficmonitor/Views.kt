package com.example.webtrafficmonitor

import android.graphics.PixelFormat
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.graphics.Typeface
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.graphics.Path


// =====================================================================================
// SweepPanel  (the rising panel + its driver)
// -------------------------------------------------------------------------------------
// ⚠️ 2026-08-24 - REPLACED THE BREATHING ORB, and this is now the ONLY animation the
// pause gate and the in-app "ride it out" pages have. Keep it that way, and keep it dumb.
//
// The orb was a full-screen radial gradient whose size, alpha and phase label all moved
// together, with a second animator pulsing the label. It was fiddly, it was expensive on
// mid-range phones, and every bug the app-open gate ever had was a bug about it.
//
// The whole thing now: a solid panel starts below the bottom edge, slides up until it
// covers its parent (easing off as it reaches the top), then slides back down. One view,
// one clock, one number. Nothing is re-rasterised while it moves - the panel is drawn
// ONCE and every position after that is a translationY. Do not add invalidate() to the
// movement, and do not give it a label to keep in step.
// =====================================================================================

/**
 * The panel: a solid slab of [accent] that fills its parent, moved with [progress] -
 * 0 = entirely below the parent's bottom edge, 1 = covering it. Whatever it is drawn over
 * is hidden while it is up and uncovered as it comes back down.
 *
 * It is deliberately dumb. It draws ONE rounded rectangle, once; every position after that
 * is a translationY, which is a transform on a display list the GPU already has. Nothing
 * here re-rasterises, and nothing here keeps time - see [SweepAnimator] for the clock.
 *
 * ⚠️ It draws nothing at all while it is off-screen, so do NOT hang a first-frame hook on
 * it: the parent clips it away at rest and its onDraw is never called.
 */
class SweepPanelView(context: Context, accent: Int) : View(context) {

    /** 0 = out of sight below, 1 = covering the parent. */
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); applyProgress() }

    private val fill = Paint().apply { color = accent }

    override fun hasOverlappingRendering() = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = applyProgress()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
    }

    /**
     * ⚠️ The travel distance falls back to the SCREEN height while this view has no size
     * of its own. The sweep can be started before the window's first layout, and
     * `height * (1 - progress)` is 0 in that window - which would park a panel that is
     * meant to be out of sight squarely over the screen. It draws nothing at zero size,
     * so the fallback simply keeps it off-screen until the real height arrives (and
     * onSizeChanged re-applies the current position the moment it does).
     */
    private fun applyProgress() {
        val travel = if (height > 0) height.toFloat()
                     else resources.displayMetrics.heightPixels.toFloat()
        translationY = travel * (1f - progress)
    }
}


/**
 * Drives one [SweepPanelView]: up, then down, repeat. [cycles] = null sweeps until
 * [stop]; otherwise it runs that many up+down rounds. [onCycle] fires after each
 * completed round as (done, total); [onComplete] fires once, after the last one.
 */
class SweepAnimator(
    private val panel: SweepPanelView,
    private val upMs: Long = 3_200,
    private val downMs: Long = 3_600,
) {
    // Driven off Choreographer and our own clock rather than ValueAnimator, because a
    // ValueAnimator finishes INSTANTLY when the system animator duration scale is 0 -
    // which battery saver, "Remove animations" and Developer Options all do. That is what
    // made the old breathing silently not start on some opens. Our own clock always runs.
    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var running = false

    private var cycleStart = 0L
    private var done = 0
    private var wasAttached = false

    fun start(
        cycles: Int? = null,
        onCycle: (done: Int, total: Int) -> Unit = { _, _ -> },
        onComplete: () -> Unit = {},
    ) {
        stop()
        running = true
        done = 0
        wasAttached = false
        cycleStart = SystemClock.uptimeMillis()

        val ch = Choreographer.getInstance()
        choreographer = ch
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                // A screen that went away without calling stop() must not keep a frame
                // callback alive for the rest of the process.
                //
                // ⚠️ Only AFTER it has been attached once. A view added to a window is not
                // attached until that window's first traversal, so a sweep started in the
                // same breath as addView sees false here on its first frame - and the
                // version of this check that did not wait killed the sweep outright.
                // That is the "sometimes it just never starts" bug.
                if (panel.isAttachedToWindow) wasAttached = true
                else if (wasAttached) { stop(); return }
                // EVERY frame, not a throttled subset. The old ~30fps cap was there to
                // save work back when a frame re-rasterised a gradient; a frame is now a
                // table lookup and one translationY, and moving 30 times a second on a
                // 120Hz screen is what "slightly laggy" looks like - the panel lands on
                // some vsyncs and not others, so the motion beats. Ride the display.
                tick(SystemClock.uptimeMillis(), cycles, onCycle, onComplete)
                if (running) ch.postFrameCallback(this)
            }
        }
        frameCallback = cb
        ch.postFrameCallback(cb)
    }

    private fun tick(now: Long, cycles: Int?, onCycle: (Int, Int) -> Unit, onComplete: () -> Unit) {
        val elapsed = now - cycleStart
        panel.progress = SweepCurve.progress(elapsed, upMs, downMs)

        if (elapsed < upMs + downMs) return

        // Round finished.
        done++
        onCycle(done, cycles ?: done)
        if (cycles != null && done >= cycles) {
            stop()
            panel.progress = 0f
            onComplete()
        } else {
            cycleStart = now
        }
    }

    fun stop() {
        running = false
        frameCallback?.let { choreographer?.removeFrameCallback(it) }
        frameCallback = null
        choreographer = null
    }
}

/**
 * The sweep's shape, SAMPLED ONCE for the life of the process.
 *
 * Both curves are evaluated into a table here and never again: a PathInterpolator does a
 * binary search plus a lerp per call, and a DecelerateInterpolator does a pow(). Neither
 * is expensive on its own, but this runs on the accessibility service's main thread while
 * an app is launching, and the whole point is that nothing in the frame path does work it
 * could have done earlier. A frame is now an integer index and one array read.
 *
 * Indexing is integer maths on purpose (no float division, no allocation). `elapsed <
 * upMs` guarantees `(elapsed * N) / upMs < N`, so the index can never run off the end.
 */
object SweepCurve {

    private const val N = 256
    private val up = FloatArray(N + 1)
    private val down = FloatArray(N + 1)

    init {
        val u = Motion.sweepUp
        val d = Motion.sweepDown
        for (i in 0..N) {
            val t = i.toFloat() / N
            up[i] = u.getInterpolation(t)
            down[i] = d.getInterpolation(t)
        }
    }

    /** Where the panel is at [elapsed] ms of a round: 0 = below the screen, 1 = covering. */
    fun progress(elapsed: Long, upMs: Long, downMs: Long): Float = when {
        elapsed <= 0L -> 0f
        elapsed < upMs -> up[((elapsed * N) / upMs).toInt()]
        elapsed < upMs + downMs -> 1f - down[(((elapsed - upMs) * N) / downMs).toInt()]
        else -> 0f
    }
}


// =====================================================================================
// FeelingFaceView  (overlapping feeling circles + a draggable face that reacts)
// -------------------------------------------------------------------------------------
// Used in the loosen flow: drag the face onto where you'll end up. With
// positiveInside = false the face is happiest in the clear centre and sours as it
// enters the (negative) feeling circles; with positiveInside = true it's the opposite.
// =====================================================================================
class FeelingFaceView(
    context: Context,
    private val labels: List<String>,              // STABLE values (stored / returned by nearestLabel)
    private val displayLabels: List<String>,       // localized text drawn on the circles
    private val circleColor: Int,
    private val positiveInside: Boolean,
    private val startZoneLabel: String? = null,
) : View(context) {

    var mood: Float = 0.5f
        private set
    var moved: Boolean = false
        private set
    var onMoodChange: ((Float) -> Unit)? = null

    private var fx = 0f
    private var fy = 0f
    private var placed = false

    private var dividerY = 0f
    private var cenX = 0f
    private var cenY = 0f
    private var vennR = 1f

    private class Circ(val cx: Float, val cy: Float, val r: Float, val value: String, val label: String)
    private var circles = listOf<Circ>()

    private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val circleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = circleColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.labelSecondary; textAlign = Paint.Align.CENTER
    }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.successSoft }
    private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.successText; textAlign = Paint.Align.LEFT }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val faceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.warning }
    private val faceLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.label; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }
    private val faceDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.label }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0 || h == 0) return
        val dp = resources.displayMetrics.density
        labelPaint.textSize = 13f * dp; zoneText.textSize = 14f * dp
        val W = w.toFloat(); val H = h.toFloat()
        dividerY = if (startZoneLabel != null) H * 0.24f else 0f
        cenX = W / 2f
        cenY = (dividerY + H) / 2f
        vennR = ((H - dividerY) / 2f) * 0.92f
        val off = vennR * 0.40f
        val cr = vennR * 0.56f
        circles = labels.mapIndexed { i, lab ->
            val ang = (-90.0 + i * 360.0 / labels.size) * Math.PI / 180.0
            Circ(cenX + off * kotlin.math.cos(ang).toFloat(), cenY + off * kotlin.math.sin(ang).toFloat(), cr, lab, displayLabels.getOrElse(i) { lab })
        }
        if (!placed) {
            placed = true
            fx = if (startZoneLabel != null) W * 0.82f else cenX
            fy = if (startZoneLabel != null) dividerY * 0.5f else H * 0.08f
            mood = computeMood(fx, fy)
            invalidate()
        }
    }

    // Mood is driven by how deep into the venn you are (distance to the shared centre),
    // NOT per-circle overlap - so the centre is unambiguously the most intense point.
    private fun computeMood(x: Float, y: Float): Float {
        if (startZoneLabel != null && y < dividerY) return 1f          // the one happy place
        val d = kotlin.math.hypot(x - cenX, y - cenY)
        val nd = (d / vennR).coerceIn(0f, 1f)                          // 0 = centre, 1 = edge
        return if (positiveInside) 0.5f + 0.5f * (1f - nd)             // neutral edge -> happy centre
        else 0.5f * nd                                                 // neutral edge -> sad centre
    }

    private fun recompute() {
        moved = true
        mood = computeMood(fx, fy)
        onMoodChange?.invoke(mood)
        invalidate()
    }

    fun nearestLabel(): String? {
        var best: String? = null; var bestD = Float.MAX_VALUE
        for (c in circles) {
            val d = kotlin.math.hypot(fx - c.cx, fy - c.cy)
            if (d < c.r && d < bestD) { bestD = d; best = c.value }
        }
        return best
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fx = event.x.coerceIn(0f, width.toFloat())
                fy = event.y.coerceIn(0f, height.toFloat())
                recompute()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (circles.isEmpty()) return
        val dp = resources.displayMetrics.density
        // happy start zone
        if (startZoneLabel != null) {
            canvas.drawRoundRect(0f, 0f, width.toFloat(), dividerY - 6f * dp, 16f * dp, 16f * dp, zoneFill)
            val lines = startZoneLabel.split("\n")
            var ty = dividerY * 0.5f - (lines.size - 1) * 9f * dp
            for (ln in lines) { canvas.drawText(ln, 14f * dp, ty, zoneText); ty += 18f * dp }
            canvas.drawLine(0f, dividerY, width.toFloat(), dividerY, dividerPaint)
        }
        // venn lobes
        for (c in circles) {
            circleFill.color = (circleColor and 0x00FFFFFF) or (46 shl 24)
            canvas.drawCircle(c.cx, c.cy, c.r, circleFill)
            canvas.drawCircle(c.cx, c.cy, c.r, circleStroke)
        }
        // labels pushed to the outer edge of each lobe
        for (c in circles) {
            val dx = c.cx - cenX; val dy = c.cy - cenY
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val lx = c.cx + dx / len * c.r * 0.5f
            val ly = c.cy + dy / len * c.r * 0.5f
            canvas.drawText(c.label, lx, ly + labelPaint.textSize / 3f, labelPaint)
        }
        // face
        val fr = kotlin.math.min(width, height) * 0.075f
        canvas.drawCircle(fx, fy, fr, faceFill)
        val ex = fr * 0.42f; val ey = fr * 0.28f; val er = fr * 0.12f
        canvas.drawCircle(fx - ex, fy - ey, er, faceDot)
        canvas.drawCircle(fx + ex, fy - ey, er, faceDot)
        val curve = (mood - 0.5f) * 2f
        val mw = fr * 0.5f; val my = fy + fr * 0.30f
        val path = Path().apply { moveTo(fx - mw, my); quadTo(fx, my + curve * fr * 0.6f, fx + mw, my) }
        canvas.drawPath(path, faceLine)
    }
}


// =====================================================================================
// PeakCurveView  (urge over time: spikes, then falls - and you're already past the peak)
// =====================================================================================
class PeakCurveView(
    context: Context,
    private val showMarker: Boolean = true,
    private val labelTop: String? = null,
    private val labelBot: String? = null,
) : View(context) {
    private var anim = 0f
    private val accent = Palette.tint
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.labelSecondary; textAlign = Paint.Align.RIGHT }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.tint; textAlign = Paint.Align.CENTER }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.labelTertiary; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // urge vs time: quick rise to a peak, slower decay back toward baseline
    private fun u(x: Float): Float {
        val xc = 0.22f; val amp = 0.80f; val base = 0.12f
        val sigma = if (x < xc) 0.10f else 0.26f
        val d = (x - xc).toDouble()
        return base + amp * Math.exp(-(d * d) / (2.0 * sigma * sigma)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val xL = 10f * dp; val xR = w - 10f * dp; val yB = h - 26f * dp; val yT = 14f * dp
        fun px(x: Float) = xL + (xR - xL) * x
        fun py(uu: Float) = yB - (yB - yT) * uu
        curve.strokeWidth = 3f * dp; axis.strokeWidth = 1f * dp; dotRing.strokeWidth = 3f * dp; arrow.strokeWidth = 1.6f * dp

        canvas.drawLine(xL, yB, xR, yB, axis)

        val path = Path(); val fillPath = Path()
        val n = 72
        for (i in 0..n) {
            val x = i / n.toFloat(); val xx = px(x); val yy = py(u(x))
            if (i == 0) { path.moveTo(xx, yy); fillPath.moveTo(xx, yB); fillPath.lineTo(xx, yy) }
            else { path.lineTo(xx, yy); fillPath.lineTo(xx, yy) }
        }
        fillPath.lineTo(px(1f), yB); fillPath.close()
        fill.shader = android.graphics.LinearGradient(
            0f, yT, 0f, yB,
            (accent and 0x00FFFFFF) or (60 shl 24), (accent and 0x00FFFFFF) or (8 shl 24),
            Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fill)
        canvas.drawPath(path, curve)

        if (showMarker) {
            val mx = 0.42f * anim
            val MX = px(mx); val MY = py(u(mx))
            canvas.drawCircle(MX, MY, 7f * dp, dotFill)
            canvas.drawCircle(MX, MY, 7f * dp, dotRing)
        }

        val la = ((anim - 0.45f) / 0.55f).coerceIn(0f, 1f)
        if (la > 0f && showMarker) {
            tag.textSize = 11f * dp; tag.alpha = (la * 200).toInt()
            canvas.drawText(context.getString(R.string.chart_past_peak), px(0.42f), yB + 18f * dp, tag)
        }
        val topText = labelTop ?: context.getString(R.string.peak_strong)
        val botText = labelBot ?: context.getString(R.string.peak_get_here)
        if (la > 0f) {
            // label sits up high, clear of the curve, with an arrow down to the faded tail
            label.textSize = 12.5f * dp; label.alpha = (la * 255).toInt()
            val tx = xR
            val ty = yT + 13f * dp
            canvas.drawText(topText, tx, ty, label)
            canvas.drawText(botText, tx, ty + 16f * dp, label)
            // arrow from just below the label down to the curve's end
            arrow.alpha = (la * 200).toInt()
            val ax = px(0.9f); val aTopY = ty + 26f * dp; val aEndY = py(u(0.9f)) - 8f * dp
            if (aEndY > aTopY) {
                canvas.drawLine(ax, aTopY, ax, aEndY, arrow)
                canvas.drawLine(ax, aEndY, ax - 4f * dp, aEndY - 6f * dp, arrow)
                canvas.drawLine(ax, aEndY, ax + 4f * dp, aEndY - 6f * dp, arrow)
            }
        }
    }
}


// =====================================================================================
// PeakTapView  (same urge curve, but the user taps where they think they are)
// =====================================================================================
class PeakTapView(
    context: Context,
    private val threshold: Float,
    private val onPick: (Float, Boolean) -> Unit,
) : View(context) {
    private val accent = Palette.tint
    private val gold = Palette.warning
    private val dull = Palette.divider
    private var tappedX: Float? = null
    private var correct = false
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.labelTertiary; textAlign = Paint.Align.CENTER }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }

    private fun u(x: Float): Float {
        val xc = 0.22f; val amp = 0.80f; val base = 0.12f
        val sigma = if (x < xc) 0.10f else 0.26f
        val d = (x - xc).toDouble()
        return base + amp * Math.exp(-(d * d) / (2.0 * sigma * sigma)).toFloat()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_MOVE) {
            val dp = resources.displayMetrics.density
            val xL = 10f * dp; val xR = width - 10f * dp
            val x = ((event.x - xL) / (xR - xL)).coerceIn(0f, 1f)
            tappedX = x
            if (x > threshold) correct = true       // once they get it right, it stays gold
            invalidate(); onPick(x, x > threshold)
            return true
        }
        return super.onTouchEvent(event)
    }

    // draws a curve segment over [x0,x1] in the given colour
    private fun segment(canvas: Canvas, x0: Float, x1: Float, color: Int,
                        px: (Float) -> Float, py: (Float) -> Float) {
        curve.color = color
        val p = Path(); val n = 48
        for (i in 0..n) {
            val x = x0 + (x1 - x0) * i / n; val xx = px(x); val yy = py(u(x))
            if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
        }
        canvas.drawPath(p, curve)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val xL = 10f * dp; val xR = w - 10f * dp; val yB = h - 26f * dp; val yT = 14f * dp
        fun px(x: Float) = xL + (xR - xL) * x
        fun py(uu: Float) = yB - (yB - yT) * uu
        curve.strokeWidth = 3f * dp; axis.strokeWidth = 1f * dp; dotRing.strokeWidth = 3f * dp
        canvas.drawLine(xL, yB, xR, yB, axis)

        // soft fill under the whole curve
        val fillPath = Path(); val n = 72
        for (i in 0..n) {
            val x = i / n.toFloat(); val xx = px(x); val yy = py(u(x))
            if (i == 0) { fillPath.moveTo(xx, yB); fillPath.lineTo(xx, yy) } else fillPath.lineTo(xx, yy)
        }
        fillPath.lineTo(px(1f), yB); fillPath.close()
        val fillColor = if (correct) gold else accent
        val a0 = if (correct) 70 else 60
        fill.shader = android.graphics.LinearGradient(
            0f, yT, 0f, yB,
            (fillColor and 0x00FFFFFF) or (a0 shl 24),
            (fillColor and 0x00FFFFFF) or (8 shl 24),
            Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fill)

        if (correct) {
            // past-the-peak tail turns gold; the rising left half is dulled back
            segment(canvas, 0f, threshold, dull, ::px, ::py)
            segment(canvas, threshold, 1f, gold, ::px, ::py)
        } else {
            segment(canvas, 0f, 1f, accent, ::px, ::py)
        }

        val tx = tappedX
        if (tx == null) {
            hint.textSize = 13f * dp
            canvas.drawText(context.getString(R.string.chart_tap_where), w / 2f, py(u(0.5f)) - 8f * dp, hint)
        } else {
            val mx = px(tx); val my = py(u(tx))
            dotFill.color = if (correct) gold else accent
            canvas.drawCircle(mx, my, 8f * dp, dotFill)
            canvas.drawCircle(mx, my, 8f * dp, dotRing)
        }
    }
}


// =====================================================================================
// GlowButton  (a filled button with a soft light tracing its edge, to invite a tap)
// =====================================================================================
class GlowButton(context: Context, private val label: String, onClick: () -> Unit) : View(context) {
    private var phase = 0f
    private var anim: android.animation.ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.warning }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val dp = resources.displayMetrics.density
        val r = 14f * dp; val inset = 2f * dp
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, bg)
        txt.textSize = 16f * dp
        canvas.drawText(label, w / 2f, h / 2f + txt.textSize / 3f, txt)
        // a warm bright band that travels around the rounded-rect edge
        edge.strokeWidth = 4f * dp
        val sweep = android.graphics.SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0x00FFF6D8, 0x00FFF6D8, Palette.warningSoft, 0x00FFF6D8, 0x00FFF6D8),
            floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f))
        sweep.setLocalMatrix(android.graphics.Matrix().apply { postRotate(phase * 360f, w / 2f, h / 2f) })
        edge.shader = sweep
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, edge)
    }
}


// =====================================================================================
// RecoveryBrainView  (your progress so far, then the fork: a one-off vs keeping going)
// =====================================================================================
class RecoveryBrainView(context: Context) : View(context) {
    private var anim = 0f
    private val amber = Palette.warning
    private val green = Palette.successText
    private val past = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.labelTertiary; strokeCap = Paint.Cap.ROUND
    }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = amber; strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = green; strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000 }
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val axisLab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.labelQuaternary }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400; startDelay = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val xL = 12f * dp; val xR = w - 12f * dp; val yT = 16f * dp; val yB = h - 26f * dp
        fun px(x: Float) = xL + (xR - xL) * x
        fun py(f: Float) = yT + (yB - yT) * f      // f: 0 = high pull (top), 1 = free (bottom)
        past.strokeWidth = 3.5f * dp; up.strokeWidth = 3f * dp; down.strokeWidth = 3f * dp; axis.strokeWidth = 1f * dp

        // faint frame + axis hints
        canvas.drawLine(xL, yB, xR, yB, axis)
        axisLab.textSize = 10.5f * dp
        axisLab.textAlign = Paint.Align.LEFT; canvas.drawText(context.getString(R.string.chart_more_pull), xL, yT + 4f * dp, axisLab)
        canvas.drawText(context.getString(R.string.chart_free), xL, yB - 4f * dp, axisLab)

        // progress so far: coming down from a high point to "now"
        val nowX = 0.40f; val nowF = 0.56f
        val pPath = Path().apply {
            moveTo(px(0.05f), py(0.20f))
            cubicTo(px(0.18f), py(0.22f), px(0.28f), py(0.48f), px(nowX), py(nowF))
        }
        canvas.drawPath(pPath, past)

        // the fork, drawn growing out from "now"
        val t = anim
        val upPath = Path().apply {
            moveTo(px(nowX), py(nowF))
            val ex = nowX + (0.95f - nowX) * t; val ef = nowF + (0.30f - nowF) * t
            cubicTo(px(nowX + 0.18f * t), py(nowF - 0.04f * t), px(nowX + 0.38f * t), py(nowF - 0.18f * t), px(ex), py(ef))
        }
        canvas.drawPath(upPath, up)
        val downPath = Path().apply {
            moveTo(px(nowX), py(nowF))
            val ex = nowX + (0.95f - nowX) * t; val ef = nowF + (0.88f - nowF) * t
            cubicTo(px(nowX + 0.20f * t), py(nowF + 0.10f * t), px(nowX + 0.40f * t), py(nowF + 0.22f * t), px(ex), py(ef))
        }
        canvas.drawPath(downPath, down)

        // branch labels (fade in)
        val la = ((anim - 0.5f) / 0.5f).coerceIn(0f, 1f)
        lab.textSize = 12.5f * dp; lab.textAlign = Paint.Align.RIGHT; lab.alpha = (la * 255).toInt()
        lab.color = amber; canvas.drawText(context.getString(R.string.chart_oneoff_backup), px(0.95f), py(0.30f) - 6f * dp, lab)
        lab.color = green; canvas.drawText(context.getString(R.string.chart_keep_going), px(0.95f), py(0.88f) + 16f * dp, lab)

        // a brain at "now"
        emoji.textSize = 26f * dp
        canvas.drawText("\uD83E\uDDE0", px(nowX), py(nowF) + 9f * dp, emoji)
    }
}


/**
 * The one back control, on every page (see the big comment in Main.kt's setContentView).
 *
 * Glass: near-white translucent fill, a hairline rim, a soft shadow, and the arrow in the
 * app's tint rather than grey. It floats over the content, so it has to read on both a
 * white card and a chart - which is exactly what a light fill plus a defined edge does,
 * and a flat grey circle did not.
 */
class ThumbBackView(context: Context) : View(context) {
    private val dp = context.resources.displayMetrics.density
    // Translucent, NOT opaque. This button floats over the page at a fixed spot, so it
    // will sometimes land on top of a line of text - it has to be readable itself without
    // hiding what is underneath it. 85% white is the point where it still reads as a solid
    // control on a white card but you can see a word passing behind it.
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt(); style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A000000; style = Paint.Style.STROKE; strokeWidth = 1f * dp
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14000000; style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.tintDeep; style = Paint.Style.STROKE; strokeWidth = 2f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = Math.min(width, height) / 2f - strokePaint.strokeWidth - 1.5f * dp
        canvas.drawCircle(cx, cy + 1.5f * dp, r, shadowPaint)   // the lift, not a drop shadow
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)
        val a = r * 0.24f      // shaft half-length
        val h = r * 0.17f      // arrowhead size
        canvas.drawLine(cx - a, cy, cx + a, cy, arrowPaint)
        val head = Path().apply {
            moveTo(cx - a + h, cy - h); lineTo(cx - a, cy); lineTo(cx - a + h, cy + h)
        }
        canvas.drawPath(head, arrowPaint)
    }
}


class WastedDonutView(context: Context) : View(context) {
    private var frac = 0f                 // 0..1 share of waking hours
    private var anim = 0f
    private val ringBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.hairline; strokeCap = Paint.Cap.ROUND }
    private val ringFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.danger; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.label; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.labelTertiary; textAlign = Paint.Align.CENTER }

    fun setFraction(f: Float) {
        val target = f.coerceIn(0f, 1f)
        android.animation.ValueAnimator.ofFloat(anim, target).apply {
            duration = 450; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim = it.animatedValue as Float; invalidate() }
            start()
        }
        frac = target
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val dp = resources.displayMetrics.density
        val sw = 16f * dp
        ringBg.strokeWidth = sw; ringFg.strokeWidth = sw
        val r = (kotlin.math.min(width, height) / 2f) - sw
        val cx = width / 2f; val cy = height / 2f
        val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 0f, 360f, false, ringBg)
        canvas.drawArc(rect, -90f, 360f * anim, false, ringFg)
        big.textSize = 30f * dp
        canvas.drawText(Units.percent(context, anim), cx, cy + 4f * dp, big)
        small.textSize = 12.5f * dp
        canvas.drawText(context.getString(R.string.chart_waking_life), cx, cy + 24f * dp, small)
    }
}



// =====================================================================================
// TrendView  (a small line chart for "urges ridden out per week" - shows direction)
// =====================================================================================
class TrendView(context: Context, private val values: FloatArray) : View(context) {
    private val accent = Palette.successText
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000 }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0 || values.isEmpty()) return
        val dp = resources.displayMetrics.density
        val xL = 10f * dp; val xR = width - 10f * dp; val yT = 12f * dp; val yB = height - 12f * dp
        val n = values.size
        val mx = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
        fun px(i: Int) = if (n == 1) (xL + xR) / 2f else xL + (xR - xL) * i / (n - 1)
        fun py(v: Float) = yB - (yB - yT) * (v / mx)
        line.strokeWidth = 3f * dp; axis.strokeWidth = 1f * dp; ring.strokeWidth = 3f * dp
        canvas.drawLine(xL, yB, xR, yB, axis)

        val path = Path(); val fill = Path()
        for (i in 0 until n) {
            val xx = px(i); val yy = py(values[i])
            if (i == 0) { path.moveTo(xx, yy); fill.moveTo(xx, yB); fill.lineTo(xx, yy) }
            else { path.lineTo(xx, yy); fill.lineTo(xx, yy) }
        }
        fill.lineTo(px(n - 1), yB); fill.close()
        fillP.shader = android.graphics.LinearGradient(
            0f, yT, 0f, yB, (accent and 0x00FFFFFF) or (44 shl 24), (accent and 0x00FFFFFF) or (6 shl 24),
            Shader.TileMode.CLAMP)
        canvas.drawPath(fill, fillP)
        canvas.drawPath(path, line)
        for (i in 0 until n) canvas.drawCircle(px(i), py(values[i]), 3.5f * dp, dot)
        val li = n - 1
        canvas.drawCircle(px(li), py(values[li]), 6f * dp, dot)
        canvas.drawCircle(px(li), py(values[li]), 6f * dp, ring)
    }
}


// =====================================================================================
// DopamineScaleView  (the vertical "where you sit" gauge)
// =====================================================================================
// A single column, calm green at the bottom through to red at the top, with a marker at
// the score. Vertical on purpose: it reads as a level, not as progress toward a goal -
// there is no goal here, and a bar you could "fill" would say the wrong thing entirely.
class DopamineScaleView(context: Context, private val score: Int) : View(context) {

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.label }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt()
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val dp = resources.displayMetrics.density
        val w = 18f * dp
        val cx = width / 2f
        val top = 10f * dp
        val bottom = height - 10f * dp

        // Top of the column is 100 (worst), bottom is 0 (calm).
        bar.shader = android.graphics.LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Palette.dangerText, Palette.danger, Palette.warning,
                Palette.warning, Palette.success, Palette.successText,
            ),
            floatArrayOf(0f, 0.2f, 0.4f, 0.55f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        val rect = android.graphics.RectF(cx - w / 2f, top, cx + w / 2f, bottom)
        canvas.drawRoundRect(rect, w / 2f, w / 2f, bar)

        // Quarter ticks, just for a sense of scale.
        for (i in 1..3) {
            val y = bottom - (bottom - top) * (i / 4f)
            canvas.drawRect(cx - w / 2f, y - 0.5f * dp, cx + w / 2f, y + 0.5f * dp, tick)
        }

        val y = bottom - (bottom - top) * (score.coerceIn(0, 100) / 100f)
        markerRing.strokeWidth = 3f * dp
        canvas.drawCircle(cx, y, 8f * dp, marker)
        canvas.drawCircle(cx, y, 8f * dp, markerRing)
    }
}


// =====================================================================================
// StatLineChartView  (the Strava-style line graph used across the home page)
// =====================================================================================
/**
 * One line, filled underneath, dots on data days, weekday/month labels under the x
 * axis, labelled hour gridlines (auto-stepped unless [gridStep] is given), an optional
 * dashed goal, and a dashed [dotted] continuation for projections.
 *
 * SCRUBBABLE: set [onScrub] and the whole view becomes the hitbox - touch or drag
 * anywhere and the nearest point is selected (marker + vertical guide drawn), with the
 * index reported so the caller can show the values IN the page (never a toast). The
 * selection sticks after the finger lifts.
 *
 * BARS: [bars] swaps the line for one bar per slot, sitting side by side. Used for the
 * charts that plot a value PER DAY, where a line implies a continuity between days that
 * isn't there - Monday's screen time doesn't flow into Tuesday's. Cumulative charts (the
 * year total, the reclaimed-time week) keep the line, because there the slope IS the story.
 *
 * EXAMPLE DATA: [watermark] writes one grey word across the middle of the plot. Charts fall
 * back to made-up numbers until there is enough real data, and this is how they say so -
 * one quiet mark ON the graph, rather than an amber caption under it and a "[Example data]"
 * tag glued onto every readout.
 */
class StatLineChartView(
    context: Context,
    private val values: FloatArray,
    private val labels: List<String>,
    // Y axis in hours: labels get the localised "h"/"m" suffix (see Units). False = a bare
    // number (the dopamine score, which has no unit at all).
    private val hoursUnit: Boolean = true,
    private val goal: Float? = null,
    private val dotted: FloatArray = FloatArray(0),
    private val goalPerSlot: Float? = null,
    private val accent: Int = Palette.tint,   // the app's primary teal
    private val dottedColour: Int? = null,          // projection colour (grey for "estimated")
    private val gridStep: Float? = null,            // labelled y gridline every this many units
    private val minorStep: Float? = null,           // unlabelled y gridline (e.g. half-hours)
    // One colour per point: each segment/dot takes its point's colour (the dopamine
    // trend uses the gauge's band colours, so a bad stretch literally goes red).
    private val segmentColours: IntArray? = null,
    // Bars side by side instead of a line - for per-day values. See the note above.
    private val bars: Boolean = false,
    // One grey word over the plot, for charts showing example data.
    private val watermark: String? = null,
) : View(context) {

    /** Fired with the selected slot index while touching/dragging. Whole view = hitbox. */
    var onScrub: ((Int) -> Unit)? = null
    private var selIndex = -1

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val scrub = onScrub ?: return super.onTouchEvent(event)
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                // Own the gesture so the page doesn't scroll out from under the finger.
                parent?.requestDisallowInterceptTouchEvent(true)
                val dp = resources.displayMetrics.density
                val xL = 30f * dp; val xR = width - 8f * dp
                val n = values.size + dotted.size
                if (n > 0 && xR > xL) {
                    // Bars own a slot each, so the hitbox is the slot the finger is over;
                    // a line's points sit ON the edges, so it is the nearest point.
                    val i = when {
                        bars -> ((event.x - xL) / (xR - xL) * n).toInt().coerceIn(0, n - 1)
                        n > 1 -> Math.round((event.x - xL) / (xR - xL) * (n - 1)).coerceIn(0, n - 1)
                        else -> 0
                    }
                    if (i != selIndex) { selIndex = i; invalidate(); scrub(i) }
                }
            }
            android.view.MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val goalP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.successText
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val barEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3D000000; strokeCap = Paint.Cap.ROUND }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000 }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun fmtVal(v: Float): String {
        // Under an hour, an hours axis counts in minutes ("45m") rather than "0.8h".
        if (hoursUnit && v < 1f && v > 0f) return Units.mins(context, Math.round(v * 60))
        val n = if (v == Math.floor(v.toDouble()).toFloat()) Units.number(context, v.toInt())
                else Units.decimal1(context, v)
        return if (hoursUnit) Units.hours(context, n) else n
    }

    /** A round gridline step (1/2/5 × 10^k) giving 3-5 lines up the axis. */
    private fun niceStep(mx: Float): Float {
        val raw = mx / 4f
        var pow = 1f
        while (pow * 10 <= raw) pow *= 10
        while (pow > raw && pow > 0.001f) pow /= 10
        for (m in floatArrayOf(1f, 2f, 5f, 10f)) {
            val step = m * pow
            if (mx / step <= 5f) return step
        }
        return pow * 10
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0 || values.isEmpty()) return
        val dp = resources.displayMetrics.density
        val padL = 34f * dp; val padR = 8f * dp; val padB = 18f * dp; val padT = 8f * dp
        val xL = padL; val xR = width - padR; val yB = height - padB; val yT = padT
        val n = values.size + dotted.size
        val all = (values + dotted).filter { !it.isNaN() }
        val goalTop = if (goalPerSlot != null) goalPerSlot * n else (goal ?: 0f)
        val mx = maxOf(all.maxOrNull() ?: 1f, goalTop, 0.5f) * 1.12f
        // Bars are centred in their own slot; line points sit on the edges of the plot.
        fun px(i: Int) = when {
            bars -> xL + (xR - xL) * (i + 0.5f) / n
            n == 1 -> (xL + xR) / 2f
            else -> xL + (xR - xL) * i / (n - 1)
        }
        fun py(v: Float) = yB - (yB - yT) * (v / mx)
        line.strokeWidth = 2.5f * dp; axis.strokeWidth = 1f * dp
        dashed.strokeWidth = 2.5f * dp
        dashed.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * dp, 5f * dp), 0f)
        dashed.color = dottedColour ?: accent
        goalP.strokeWidth = 1.5f * dp
        goalP.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * dp, 5f * dp), 0f)

        // y axis: labelled gridlines all the way up. gridStep pins the spacing (with
        // optional unlabelled minors between); otherwise a round step is auto-picked -
        // so an hours chart counts up in real hours whatever data is passed in.
        canvas.drawLine(xL, yB, xR, yB, axis)
        text.textSize = 10f * dp; text.color = Palette.labelTertiary; text.textAlign = Paint.Align.RIGHT
        canvas.drawText(Units.number(context, 0), xL - 4f * dp, yB + 3.5f * dp, text)
        val stepMain = gridStep ?: niceStep(mx)
        val stepDraw = minorStep ?: stepMain
        var v = stepDraw
        while (v <= mx) {
            canvas.drawLine(xL, py(v), xR, py(v), axis)
            val labelled = Math.abs(v / stepMain - Math.round(v / stepMain)) < 0.01f
            if (labelled) canvas.drawText(fmtVal(v), xL - 4f * dp, py(v) + 3.5f * dp, text)
            v += stepDraw
        }

        // goal: horizontal line, or a rising slope on cumulative charts - always
        // labelled "goal" so nobody wonders what the green line is.
        text.textSize = 9f * dp
        if (goalPerSlot != null) {
            canvas.drawLine(px(0), py(0f), px(n - 1), py(goalPerSlot * n), goalP)
            text.textAlign = Paint.Align.RIGHT; text.color = Palette.successText
            canvas.drawText(context.getString(R.string.chart_goal), xR, py(goalPerSlot * n) - 3f * dp, text)
        } else if (goal != null) {
            canvas.drawLine(xL, py(goal), xR, py(goal), goalP)
            text.textAlign = Paint.Align.RIGHT; text.color = Palette.successText
            canvas.drawText(context.getString(R.string.chart_goal_val, fmtVal(goal)), xR, py(goal) - 3f * dp, text)
        }

        // BARS: one per slot, sitting side by side. A day's usage is its own quantity, not a
        // point on a curve, so the bar starts at zero and its height IS the value - no fill,
        // no dots, nothing implying Monday runs into Tuesday.
        if (bars) {
            val slot = (xR - xL) / n
            val bw = minOf(slot * 0.66f, 30f * dp)
            val r = minOf(bw * 0.3f, 5f * dp)
            for (i in 0 until n) {
                val vv = if (i < values.size) values[i] else dotted.getOrElse(i - values.size) { Float.NaN }
                if (vv.isNaN() || vv <= 0f) continue
                bar.color = when {
                    i >= values.size -> dottedColour ?: accent
                    segmentColours != null -> segmentColours.getOrElse(i) { accent }
                    else -> accent
                }
                // A hairline of height on a nearly-zero day, so "barely any" still reads as a bar.
                val top = minOf(py(vv), yB - 1.5f * dp)
                canvas.drawRoundRect(px(i) - bw / 2f, top, px(i) + bw / 2f, yB, r, r, bar)
                if (i == selIndex) {
                    barEdge.strokeWidth = 2f * dp; barEdge.color = Palette.label
                    canvas.drawRoundRect(px(i) - bw / 2f, top, px(i) + bw / 2f, yB, r, r, barEdge)
                }
            }
        }

        // the real line (skipping NaN gaps), filled underneath
        val path = Path(); val fill = Path()
        var started = false; var lastX = 0f
        if (!bars) for (i in values.indices) {
            val vv = values[i]; if (vv.isNaN()) continue
            val xx = px(i); val yy = py(vv)
            if (!started) { path.moveTo(xx, yy); fill.moveTo(xx, yB); fill.lineTo(xx, yy); started = true }
            else { path.lineTo(xx, yy); fill.lineTo(xx, yy) }
            lastX = xx
        }
        if (started) {
            fill.lineTo(lastX, yB); fill.close()
            fillP.shader = android.graphics.LinearGradient(
                0f, yT, 0f, yB, (accent and 0x00FFFFFF) or (40 shl 24), (accent and 0x00FFFFFF) or (5 shl 24),
                Shader.TileMode.CLAMP)
            canvas.drawPath(fill, fillP)
            if (segmentColours == null) {
                canvas.drawPath(path, line)
            } else {
                // Per-segment colouring: each stretch takes the colour of the point it
                // arrives at, so the line changes colour as the level changes band.
                var prev = -1
                for (i in values.indices) {
                    if (values[i].isNaN()) continue
                    if (prev >= 0) {
                        line.color = segmentColours.getOrElse(i) { accent }
                        canvas.drawLine(px(prev), py(values[prev]), px(i), py(values[i]), line)
                    }
                    prev = i
                }
                line.color = accent
            }
            // dots only when they won't turn the line into a caterpillar
            if (values.size <= 40) {
                for (i in values.indices) if (!values[i].isNaN()) {
                    dot.color = segmentColours?.getOrElse(i) { accent } ?: accent
                    canvas.drawCircle(px(i), py(values[i]), 3f * dp, dot)
                }
            }
            val li = values.indexOfLast { !it.isNaN() }
            if (li >= 0) {
                dot.color = segmentColours?.getOrElse(li) { accent } ?: accent
                canvas.drawCircle(px(li), py(values[li]), 5f * dp, dot)
            }
            dot.color = accent
        }

        // the dotted projection, continuing from the last real point
        if (dotted.isNotEmpty() && started) {
            val li = values.indexOfLast { !it.isNaN() }
            val proj = Path()
            proj.moveTo(px(li), py(values[li]))
            for (j in dotted.indices) proj.lineTo(px(values.size + j), py(dotted[j]))
            canvas.drawPath(proj, dashed)
        }

        // scrub selection: vertical guide + ring on the selected point (line charts only -
        // a bar marks itself with an outline, drawn with the bars above)
        if (selIndex in 0 until n && !bars) {
            val sv = if (selIndex < values.size) values[selIndex] else dotted[selIndex - values.size]
            if (!sv.isNaN()) {
                guide.strokeWidth = 1.5f * dp
                canvas.drawLine(px(selIndex), yT, px(selIndex), yB, guide)
                dot.color = when {
                    selIndex >= values.size -> dottedColour ?: accent
                    segmentColours != null -> segmentColours.getOrElse(selIndex) { accent }
                    else -> accent
                }
                ring.strokeWidth = 2.5f * dp
                canvas.drawCircle(px(selIndex), py(sv), 6.5f * dp, dot)
                canvas.drawCircle(px(selIndex), py(sv), 6.5f * dp, ring)
                dot.color = accent
            }
        }

        // x labels (weekdays / months) - sparse, under the axis
        text.textSize = 10f * dp; text.textAlign = Paint.Align.CENTER; text.color = Palette.labelTertiary
        for (i in 0 until minOf(n, labels.size)) {
            val l = labels[i]
            if (l.isNotEmpty()) canvas.drawText(l, px(i), height - 4f * dp, text)
        }

        // EXAMPLE DATA: one grey word straight across the plot, drawn last so it sits over
        // everything. Shrunk to fit rather than clipped - a cut-off watermark reads as a bug.
        val wm = watermark
        if (!wm.isNullOrEmpty()) {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textAlign = Paint.Align.CENTER
            text.color = (Palette.labelTertiary and 0x00FFFFFF) or (0x8C shl 24)
            text.textSize = 16f * dp
            val maxW = (xR - xL) * 0.86f
            val w = text.measureText(wm)
            if (w > maxW && w > 0f) text.textSize = 16f * dp * (maxW / w)
            canvas.drawText(wm, (xL + xR) / 2f, (yT + yB) / 2f + text.textSize / 3f, text)
            text.typeface = Typeface.DEFAULT
        }
    }
}
