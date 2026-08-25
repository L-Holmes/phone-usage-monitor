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
// PauseOverlay - the pause gate shown before a chosen app opens
// -------------------------------------------------------------------------------------
// ⚠️ 2026-08-24 - REWRITTEN, and the second rewrite of the day. What is on screen is:
//
//   dark cover  ->  a solid panel rises from the bottom and covers it  ->  the panel
//   comes back down, uncovering the buttons underneath  ->  the buttons go live.
//
// That is the whole thing. No orb, no phase label, no "tap if nothing happens" hint, and
// no per-frame work of our own: the panel is ONE view moved by [SweepAnimator], the same
// driver the in-app pages use.
//
// THE TWO BUGS THE FIRST SWEEP REWRITE HAD - do not reintroduce either:
//
//  1. NO FLAG_HARDWARE_ACCELERATED. A window added by a SERVICE does not get hardware
//     acceleration by default the way an Activity's does, so every frame re-rasterised a
//     full-screen panel in software on the service's main thread. That is what made the
//     phone feel frozen with a blank cover on it. The flag below is not an optimisation,
//     it is the difference between a translationY the GPU applies to a cached display
//     list and a full-screen software redraw thirty times a second.
//
//  2. IT WAITED TO START. The sweep hung off a first-draw hook on the panel - which, at
//     rest, sits entirely below the screen, gets clipped away, and never draws - so the
//     start always fell through to a 600ms timer. There is no hook and no timer now: the
//     sweep starts on the same line that puts the window up.
//
//  3. THE ANIMATOR'S "still attached?" GUARD FIRED BEFORE THE FIRST ATTACH. See
//     SweepAnimator - a view is not attached until its window's first traversal, so a
//     sweep started this early saw "detached" on frame one and stopped itself for good.
//     That was "sometimes it never starts at all".
//
// The buttons must become live and the window must come down even if the animation never
// runs a single frame, so both of those are absolute Handler deadlines posted up front -
// they know nothing about the sweep and nothing may slide them.
// =====================================================================================

class PauseOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var panel: SweepPanelView? = null
    private var sweep: SweepAnimator? = null
    private var controls: View? = null
    private var dontWantBtn: Button? = null

    /** uptimeMillis the window went up. The escape tap arms off this. */
    private var shownAt = 0L
    /** True once the buttons are live. Nothing on screen changes after this. */
    private var released = false

    val isShowing: Boolean get() = view != null

    // A full-screen cover, so it uses the cover palette rather than the page palette - the
    // same dark surface as a block, for the same reason: it has to read as "stop", and it
    // has to work over whatever app is underneath it.
    private val accent = Palette.tint
    private val accentMuted = Palette.tintDeep
    private val bg = Palette.cover

    fun show(appLabel: String, onContinue: () -> Unit, onDontWant: () -> Unit) {
        if (view != null) return
        released = false
        shownAt = SystemClock.uptimeMillis()
        val dm = context.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val root = FrameLayout(context).apply { setBackgroundColor(bg) }

        // ── THE BUTTONS, UNDERNEATH ─────────────────────────────────────────────────
        // Added BEFORE the panel, so the panel covers them and the way down uncovers
        // them. They are INVISIBLE until the panel is over the top of them - otherwise
        // the gate opens with its exit already on screen, which is the one thing it must
        // not do. Not pressable until [released] either way.
        val controlBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
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
            setPadding(dp(16), dp(28), dp(16), dp(4))
            setOnClickListener { if (released) onContinue() }
        }
        controlBlock.addView(cont, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(controlBlock, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        // ── THE PANEL, ON TOP ───────────────────────────────────────────────────────
        // Palette.sweep, not the brand accent: this panel is a solid object crossing the
        // screen for ten seconds, and the teal glowed.
        val sweepPanel = SweepPanelView(context, Palette.sweep)
        root.addView(sweepPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // See bug 1 in the header. Without this the whole cover is drawn in
                // software, on the service's main thread, every single frame.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE)

        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            android.util.Log.e("PauseOverlay", "could not show", t)
            return                                  // nothing was adopted; nothing to undo
        }
        view = root
        panel = sweepPanel
        controls = controlBlock
        dontWantBtn = dontWant

        // ── THE ESCAPE ──────────────────────────────────────────────────────────────
        // Silent, and never conditional on the animation: whatever the panel is or isn't
        // doing, a tap after TAP_ESCAPE_MS goes through. (The gap keeps the tap that
        // launched the app from carrying into the cover that lands on top of it.) There
        // is no label for it any more - it is a safety valve, not an instruction.
        root.isClickable = true
        root.setOnClickListener {
            if (released) return@setOnClickListener            // the buttons are up; use them
            if (SystemClock.uptimeMillis() - shownAt >= TAP_ESCAPE_MS) onContinue()
        }

        // ── THE START ───────────────────────────────────────────────────────────────
        // Now. Not on the first painted frame, not after a safety cap - the sweep starts
        // in the same breath as the window goes up, because every millisecond between
        // opening an app and something moving on screen reads as the gate being broken.
        //
        // The old first-frame hook was there so a late-compositing window would not show
        // a sweep that was already part-way through. The trade was wrong: it bought a
        // fraction of a second of correctness at the top of the rise and paid for it with
        // a blank screen every single time. The panel handles being started before layout
        // (see SweepPanelView.applyProgress).
        begin(root, sweepPanel)

        // ── THE TWO DEADLINES ───────────────────────────────────────────────────────
        // Absolute, posted now, and deliberately ignorant of everything above. If the
        // sweep never runs a frame, THESE are what hand the phone back.
        handler.postDelayed(
            { if (view === root) release() },
            UP_MS + DOWN_MS + RELEASE_SLACK_MS,
        )
        handler.postDelayed({
            if (view !== root) return@postDelayed
            // The window comes down FIRST and by our own hand, then the service is told,
            // so a caller who mishandles onContinue still cannot leave a cover on screen.
            hide()
            onContinue()
        }, MAX_LIFETIME_MS)
    }

    /** Run the one sweep. Idempotent - its two callers race deliberately. */
    private fun begin(root: View, sweepPanel: SweepPanelView) {
        if (view !== root || sweep != null || released) return
        // ramp = false: this one sweep's length is written into the absolute deadlines
        // below, and a longer round would be cut off by them. The gentler START that the
        // breathing screens get from the ramp, this gets from the curve itself - see
        // Motion.sweepUp, which no longer launches on the first frame.
        sweep = SweepAnimator(sweepPanel, UP_MS, DOWN_MS, ramp = false).also {
            it.start(cycles = 1, onComplete = { release() })
        }
        // The buttons appear UNDER the panel at the top of the sweep, so the way down is
        // what uncovers them. One posted message, no per-frame work.
        handler.postDelayed({ controls?.visibility = View.VISIBLE }, UP_MS)
    }

    /**
     * The sweep is over, or was never going to happen: hand the phone back.
     *
     * The panel is PUT BACK DOWN by hand rather than left wherever the animation got to.
     * A sweep that stalled while covering the screen would otherwise sit on top of the
     * buttons this just made live - a cover with no way out is the one failure this
     * screen must never produce, and it is not allowed to depend on the animation.
     */
    private fun release() {
        if (released || view == null) return
        released = true
        sweep?.stop()
        panel?.progress = 0f
        controls?.visibility = View.VISIBLE
        (dontWantBtn?.background as? GradientDrawable)?.setColor(accent)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        sweep?.stop()
        sweep = null
        val gone = view
        // Dropped BEFORE the removeView, so anything re-entering through a listener (the
        // lifetime deadline calls hide() and then the service, which calls hide() again)
        // sees a controller that already owns nothing.
        view = null
        panel = null; controls = null; dontWantBtn = null
        released = false
        shownAt = 0L
        if (gone != null) {
            // removeView can throw if the window has already gone (the service was killed
            // and restarted, say) - but try removeViewImmediate as a second attempt first,
            // because a full-screen cover we have stopped tracking and have NOT removed is
            // the one outcome that leaves a phone unusable.
            try {
                windowManager.removeView(gone)
            } catch (t: Throwable) {
                try { windowManager.removeViewImmediate(gone) } catch (_: Throwable) {}
                android.util.Log.e("PauseOverlay", "could not remove", t)
            }
        }
    }

    private companion object {
        /**
         * How long after the cover appears a background tap starts letting you through.
         * Short - the escape is real - but not zero, so the tap that opened the app can't
         * carry through into the cover that lands on top of it.
         */
        const val TAP_ESCAPE_MS = 900L
        const val UP_MS = 3_500L
        /**
         * Longer than the way up, on purpose: coming down is what you sit through while
         * the buttons are uncovered, and hurrying it makes the gate feel like a loading
         * screen. See Motion.sweepDown for the curve.
         */
        const val DOWN_MS = 6_200L
        /** Slop the deadline release allows on top of a whole sweep before it steps in. */
        const val RELEASE_SLACK_MS = 1_500L
        /** Nothing this app draws over the whole screen may outlive this, for any reason. */
        const val MAX_LIFETIME_MS = 20_000L
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
