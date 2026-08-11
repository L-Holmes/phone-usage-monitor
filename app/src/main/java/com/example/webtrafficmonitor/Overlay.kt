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
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.graphics.Typeface
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.graphics.Path





// --------------------------------------------------------------
// OverlayController
// --------------------------------------------------------------


/**
 * Draws and removes the full-screen "blocked" cover over whatever app is in
 * front. The cover is opaque, so the content underneath is hidden, but it is not
 * focusable, so the system Back action still reaches the app underneath (that is
 * how the "Go back" button navigates the browser).
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    /**
     * [showGoBack] - whether the quiet "Go back one page instead" link is offered at all.
     * Only a distracting WEB PAGE in a browser gets it: pressing Back there plausibly
     * lands somewhere fine. For everything else - a blocked app, the night guard's
     * lying-down/dark covers, a protected room - there is no "previous page" to go back
     * to, so the only way out is the big exit button.
     *
     * [details] is the block SHOWING ITS WORKING: for a scored block, the handful of words
     * that actually carried the score. A cover that says only "blocked" is one the user can
     * only read as arbitrary, and when the block IS wrong this is the line that says which
     * word to go and fix. Null (and hidden) for blocks that are their own explanation - a
     * banned domain, a screen guard, an app that is simply on the list.
     */
    fun show(
        reason: String,
        onGoBack: () -> Unit,
        onLeave: () -> Unit,
        onReport: () -> Unit,
        showGoBack: Boolean = true,
        details: String? = null,
    ) {
        view?.let { existing ->
            existing.findViewById<TextView>(R.id.block_reason).text = reason
            setDetailsOn(existing, details)
            // The cover can be re-shown for a DIFFERENT kind of block (a page cover
            // upgraded to an app cover, say) - keep the link's visibility current.
            existing.findViewById<View>(R.id.btn_go_back).visibility =
                if (showGoBack) View.VISIBLE else View.GONE
            return
        }

        val overlay = LayoutInflater.from(context).inflate(R.layout.overlay_block, null)
        overlay.findViewById<TextView>(R.id.block_reason).text = reason
        setDetailsOn(overlay, details)
        // Typed as View, not Button: "go back" and "report" are quiet TextView links now,
        // and only "leave" is still an actual Button.
        val goBack = overlay.findViewById<View>(R.id.btn_go_back)
        goBack.visibility = if (showGoBack) View.VISIBLE else View.GONE
        goBack.setOnClickListener {
            pressAnimation(it)
            onGoBack()
        }
        overlay.findViewById<View>(R.id.btn_leave).setOnClickListener { onLeave() }
        overlay.findViewById<View>(R.id.btn_report).setOnClickListener { onReport() }

        // Wrap the cover in a FrameLayout we control, so the temporary image layer
        // can be laid ON TOP of the cover (and removed) without touching the XML.
        // findViewById still reaches block_reason/buttons since they're descendants.
        val container = FrameLayout(context).apply {
            addView(
                overlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )

        try {
            windowManager.addView(container, params)
            view = container
        } catch (t: Throwable) {
            // Never crash the service over a cover; log it instead.
            android.util.Log.e("OverlayController", "could not show block cover", t)
            view = null
        }


    }

    fun hide() {
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (t: Throwable) {
                android.util.Log.e("OverlayController", "could not remove cover", t)
            }
            view = null
        }
    }

    /** Update just the cover's reason text (used by the live block countdown). */
    fun setReason(reason: String) {
        view?.findViewById<TextView>(R.id.block_reason)?.text = reason
    }

    /** The breakdown line: filled in and shown, or emptied and taken out of the layout. */
    private fun setDetailsOn(root: View, details: String?) {
        val view = root.findViewById<TextView>(R.id.block_details) ?: return
        view.text = details.orEmpty()
        view.visibility = if (details.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Show [message] on the status line and FLASH it.
     *
     * Only ever called when the user has actually tapped "Go back" - the status line stays
     * empty otherwise. The flash restarts on every tap, even if the text is identical, so a
     * user mashing Back on a page that won't budge can SEE that each press registered and the
     * cover re-checked. Without that it looks frozen and they assume the button is broken.
     */
    fun flashStatus(message: String) {
        val status = view?.findViewById<TextView>(R.id.block_status) ?: return
        status.animate().cancel()
        status.text = message
        status.alpha = 0f
        status.animate().alpha(1f).setDuration(160).start()
    }

    /** A quick squeeze so a tap on a plain TextView link still feels like a button press. */
    private fun pressAnimation(v: View) {
        v.animate().cancel()
        v.scaleX = 0.92f; v.scaleY = 0.92f; v.alpha = 0.55f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
    }

    private fun overlayType(): Int =
        // An accessibility service may draw TYPE_ACCESSIBILITY_OVERLAY windows
        // WITHOUT the "display over other apps" permission - so a revoked overlay
        // permission can no longer crash the service or silently kill blocking.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}



// =====================================================================================
// BreathingOverlay - a calming "take a breath" gate shown before chosen apps open
// -------------------------------------------------------------------------------------
// ⚠️ 2026-08-11 - REWRITTEN FROM SCRATCH. Read this before touching anything below.
//
// The old version had no single description of what was on screen. The breath could start
// from a first-draw hook, a window-manager nudge, or a timed fallback; the buttons could be
// handed over by an animation-completed callback, or a "has the orb moved yet?" watchdog,
// or a deadline; and the window came down on a ceiling that rescheduled ITSELF depending on
// which of those had happened. Every one of those paths was a guess about what had already
// gone wrong, and when two of them disagreed you got the bug that matters: a full-screen
// cover reading "Breathe in", frozen, with a phone behind it that could not be used.
//
// So there is now ONE clock and ONE function. [frame] derives the ENTIRE screen - orb size,
// phase label, controls, whether the buttons are live - from milliseconds elapsed since the
// breath began, and from nothing else. It keeps no state between calls, so no call can
// leave the screen half-updated: a tick that arrives late (screen off, animator duration
// scale 0, render thread stalled) just computes the state for the time it actually is now.
// A tick that never arrives at all is covered by absolute deadlines posted up front, which
// is the whole reason they are absolute and posted up front.
//
// The rules, each of which is a bug that already happened - do not drop one without reading
// the comment attached to it:
//   • the escape tap is live from TAP_ESCAPE_MS onwards, and the hint that says so STAYS on
//     screen until the real buttons replace it;
//   • the window has an unconditional lifetime measured from the moment it went up, and
//     nothing may slide it;
//   • the clock starts on the first PAINTED frame - but capped, because waiting on a frame
//     that never comes is how you end up staring at a static word.
// =====================================================================================

class BreathingOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var orb: BreathOrbView? = null
    private var phaseLabel: TextView? = null
    private var tapHint: TextView? = null
    private var controls: View? = null
    private var dontWantBtn: Button? = null

    /** uptimeMillis the window went up. The escape tap and the lifetime arm off this. */
    private var shownAt = 0L
    /** uptimeMillis the breath's clock started, or 0 while we're still waiting for a frame. */
    private var startedAt = 0L
    /** True once the buttons are live. The screen stops changing after this. */
    private var released = false
    /** What the phase label currently says, so [frame] isn't re-setting text 60 times a second. */
    private var phaseShown: Boolean? = null

    val isShowing: Boolean get() = view != null

    // The breathing gate is a full-screen cover, so it uses the cover palette rather than
    // the page palette - the same dark surface as a block, for the same reason: it has to
    // read as "stop", and it has to work over whatever app is underneath it.
    private val accent = Palette.tint
    private val accentMuted = Palette.tintDeep
    private val bg = Palette.cover
    private val softText = Palette.tintSoft

    private val inhaleEase = PathInterpolator(0.4f, 0f, 0.5f, 1f)
    private val exhaleEase = PathInterpolator(0.2f, 0f, 0.45f, 1f)

    fun show(appLabel: String, onContinue: () -> Unit, onDontWant: () -> Unit) {
        if (view != null) return
        released = false
        startedAt = 0L
        phaseShown = null
        shownAt = SystemClock.uptimeMillis()
        val dm = context.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val root = FrameLayout(context).apply { setBackgroundColor(bg) }

        // A CIRCLE. The orb used to be sized off the screen's DIAGONAL, so at full inhale
        // its gradient ran past all four corners and what you actually watched was the
        // rectangle brightening - there was no edge anywhere on screen. Sized off the short
        // edge (see OrbFill.COVER) it stays a disc with a visible rim, which is the only
        // reason breathing WITH something works: you need to see where it is going.
        val orbView = BreathOrbView(context, accent, OrbFill.COVER)
        root.addView(orbView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val phase = TextView(context).apply {
            textSize = 16f
            setTextColor(softText)
            alpha = 0.55f
            gravity = Gravity.CENTER
            text = context.getString(R.string.overlay_breathe_in)
        }
        root.addView(phase, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = (dm.heightPixels * 0.17f).toInt()
            })

        // THE HINT STAYS UP. It used to flash for a moment and fade to nothing, so for the
        // rest of the breath the screen offered no way out and no sign that there was one -
        // which is what being trapped behind this thing feels like even on the runs where it
        // is working perfectly. It is quiet, and it leaves when the real buttons arrive.
        val hint = TextView(context).apply {
            text = context.getString(R.string.overlay_tap_hint)
            textSize = 13f
            setTextColor(softText)
            alpha = 0f
            gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = (dm.heightPixels * 0.17f).toInt() + dp(30)
            })

        // Bottom block: lifted ~14% off the bottom (was ~20%, now down ~6vh).
        //
        // hasOverlappingRendering is refused deliberately. [frame] fades this in over the
        // whole exhale, and a ViewGroup at alpha < 1 that admits to overlapping children
        // gets an offscreen buffer allocated and thrown away EVERY FRAME. Its two children
        // are stacked, not overlapping, so per-child alpha is both correct and free.
        val controlBlock = object : LinearLayout(context) {
            override fun hasOverlappingRendering() = false
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0f
            visibility = View.INVISIBLE
            setPadding(dp(20), 0, dp(20), (dm.heightPixels * 0.14f).toInt())
        }
        val dontWant = Button(context).apply {
            text = context.getString(R.string.overlay_dont_want, appLabel)
            isAllCaps = false
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.cover)
            background = GradientDrawable().apply {
                cornerRadius = dp(34).toFloat()
                setColor(accentMuted)
            }
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { if (released) onDontWant() }
        }
        controlBlock.addView(dontWant, LinearLayout.LayoutParams(
            (dm.widthPixels * 0.88f).toInt(), (dm.heightPixels * 0.21f).toInt()))

        val cont = TextView(context).apply {
            text = context.getString(R.string.overlay_continue_open, appLabel)
            isAllCaps = false
            textSize = 14f
            setTextColor(Palette.tint)
            gravity = Gravity.CENTER
            // More gap above the "continue" line so it sits a bit lower.
            setPadding(dp(16), dp(28), dp(16), dp(4))
            setOnClickListener { if (released) onContinue() }
        }
        controlBlock.addView(cont, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(controlBlock, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE)

        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            android.util.Log.e("BreathingOverlay", "could not show", t)
            return                                  // nothing was adopted; nothing to undo
        }
        view = root
        orb = orbView
        phaseLabel = phase
        tapHint = hint
        controls = controlBlock
        dontWantBtn = dontWant

        // ── THE ESCAPE ──────────────────────────────────────────────────────────────
        // Armed here, on the way up, and never conditional on the breath: whatever the
        // animation is or isn't doing, a tap goes through. Once the real buttons are live
        // the background stops being a target, so a stray tap can't fling you into the app
        // past a decision you were halfway through making. TAP_ESCAPE_MS keeps the tap that
        // launched the app in the first place from carrying into the cover on top of it.
        root.isClickable = true
        root.setOnClickListener {
            if (released) return@setOnClickListener            // the buttons are up; use them
            if (SystemClock.uptimeMillis() - shownAt >= TAP_ESCAPE_MS) onContinue()
        }
        hint.animate().alpha(0.45f).setStartDelay(TAP_ESCAPE_MS).setDuration(500).start()

        // ── THE CLOCK ───────────────────────────────────────────────────────────────
        // First PAINTED frame, or CLOCK_CAP_MS, whichever comes first.
        //
        // Being laid out is not the same as being on screen: adding a window while the
        // launching app is still animating in means the cover can composite late, and a
        // breath timed from addView would then be half over (or wholly over) by the time
        // anyone could see it. So the clock waits for a real frame - but only briefly,
        // because "wait for a frame that never comes" is the other half of the same bug,
        // and it is the half that leaves a word sitting motionless on a dark screen.
        orbView.onFirstDraw = { begin() }
        handler.postDelayed({ begin() }, CLOCK_CAP_MS)

        // ── THE TWO DEADLINES ───────────────────────────────────────────────────────
        // Absolute, posted now, and deliberately ignorant of everything above. If the
        // ticker never runs a single frame, THESE are what hand the phone back.
        handler.postDelayed(
            { if (view === root) release() },
            CLOCK_CAP_MS + BREATH_MS + RELEASE_SLACK_MS,
        )
        handler.postDelayed({
            if (view !== root) return@postDelayed
            // The window comes down FIRST and by our own hand, then the service is told, so
            // that a caller who mishandles onContinue still cannot leave a cover on screen.
            hide()
            onContinue()
        }, MAX_LIFETIME_MS)
    }

    /** Start the breath's clock. Idempotent - both of its callers race deliberately. */
    private fun begin() {
        if (view == null || startedAt != 0L) return
        startedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (view == null || released) return
            frame(SystemClock.uptimeMillis() - startedAt)
            if (view != null && !released) handler.postDelayed(this, FRAME_MS)
        }
    }

    /**
     * The whole screen, as a pure function of [elapsed] milliseconds of breath.
     *
     * Call it at any moment, out of order, twice with the same value: the result is the
     * same. That is the property the old version lacked and the reason it could get stuck -
     * there is no "we already did the exhale" state to be wrong about.
     */
    private fun frame(elapsed: Long) {
        val orbView = orb ?: return
        val controlBlock = controls ?: return
        when {
            elapsed < INHALE_MS -> {
                orbView.progress = inhaleEase.getInterpolation(elapsed.toFloat() / INHALE_MS)
                setPhase(inhaling = true)
            }
            elapsed < BREATH_MS -> {
                val t = (elapsed - INHALE_MS).toFloat() / EXHALE_MS
                orbView.progress = 1f - exhaleEase.getInterpolation(t)
                setPhase(inhaling = false)
                // The choice arrives WITH the exhale rather than after it, so the last
                // seconds are spent looking at a way out instead of at a screen that might
                // as well be broken. It is not pressable yet - see [released].
                controlBlock.visibility = View.VISIBLE
                controlBlock.alpha = 0.25f + 0.55f * t
            }
            else -> { release(); return }
        }
        // The label breathes with the orb, off the same clock. The old one had its own
        // second animator for this, which is one more thing that could be left running.
        phaseLabel?.alpha = 0.55f + 0.45f * orbView.progress
    }

    private fun setPhase(inhaling: Boolean) {
        if (phaseShown == inhaling) return
        phaseShown = inhaling
        phaseLabel?.setText(
            if (inhaling) R.string.overlay_breathe_in else R.string.overlay_breathe_out
        )
    }

    /** The breath is over, or was never going to happen: hand the phone back. */
    private fun release() {
        if (released || view == null) return
        released = true
        handler.removeCallbacks(tick)
        orb?.progress = 0f
        phaseLabel?.animate()?.alpha(0f)?.setDuration(300)?.start()
        tapHint?.let { it.animate().cancel(); it.animate().alpha(0f).setDuration(300).start() }
        controls?.let {
            it.animate().cancel()
            it.visibility = View.VISIBLE
            it.alpha = 1f
        }
        (dontWantBtn?.background as? GradientDrawable)?.setColor(accent)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        val gone = view
        // Dropped BEFORE the removeView, so anything re-entering through a listener (the
        // lifetime deadline calls hide() and then the service, which calls hide() again)
        // sees a controller that already owns nothing.
        view = null
        orb = null; phaseLabel = null; tapHint = null; controls = null; dontWantBtn = null
        released = false
        startedAt = 0L
        shownAt = 0L
        phaseShown = null
        if (gone != null) {
            // removeView can throw if the window has already gone (the service was killed
            // and restarted, say) - but try removeViewImmediate as a second attempt first,
            // because a full-screen cover we have stopped tracking and have NOT removed is
            // the one outcome that leaves a phone unusable.
            try {
                windowManager.removeView(gone)
            } catch (t: Throwable) {
                try { windowManager.removeViewImmediate(gone) } catch (_: Throwable) {}
                android.util.Log.e("BreathingOverlay", "could not remove", t)
            }
        }
    }

    private companion object {
        /**
         * How long after the cover appears a background tap starts letting you through.
         * Short - the point is that the escape is real - but not zero, so the tap that
         * opened the app can't carry through into the cover that lands on top of it.
         */
        const val TAP_ESCAPE_MS = 900L
        /** No painted frame by this point: start the breath anyway rather than wait on one. */
        const val CLOCK_CAP_MS = 600L
        const val INHALE_MS = 3_500L
        const val EXHALE_MS = 4_500L
        const val BREATH_MS = INHALE_MS + EXHALE_MS
        /** Slop the deadline release allows on top of a whole breath before it steps in. */
        const val RELEASE_SLACK_MS = 1_500L
        /** ~60fps of view-property sets. Nothing is re-rasterised, so this is cheap. */
        const val FRAME_MS = 16L
        /** Nothing this app draws over the whole screen may outlive this, for any reason. */
        const val MAX_LIFETIME_MS = 20_000L
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
