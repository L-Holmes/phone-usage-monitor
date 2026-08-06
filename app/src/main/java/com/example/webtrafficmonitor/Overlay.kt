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
// =====================================================================================

class BreathingOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var orbAnim: BreathOrbAnimator? = null
    private var controlsActive = false
    private var started = false
    /** uptimeMillis the window went up. The tap escape arms off this, not off the breath. */
    private var shownAt = 0L
    /** uptimeMillis the breath actually began (first painted frame), or 0 if it hasn't. */
    private var breathStartedAt = 0L

    val isShowing: Boolean get() = view != null

    // The breathing gate is a full-screen cover, so it uses the cover palette rather than
    // the page palette - the same dark surface as a block, for the same reason: it has to
    // read as "stop", and it has to work over whatever app is underneath it.
    private val accent = Palette.tint
    private val accentMuted = Palette.tintDeep
    private val bg = Palette.cover
    private val softText = Palette.tintSoft

    fun show(appLabel: String, onContinue: () -> Unit, onDontWant: () -> Unit) {
        if (view != null) return
        controlsActive = false
        started = false
        shownAt = SystemClock.uptimeMillis()
        val dm = context.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val root = FrameLayout(context).apply { setBackgroundColor(bg) }

        // COVER, not INSCRIBE: this overlay is the whole screen, so the orb should reach
        // the corners at full inhale like it used to. (The in-app arousal pages keep the
        // INSCRIBE default, because there the orb sits in a bounded box.)
        val orb = BreathOrbView(context, accent, OrbFill.COVER)
        root.addView(orb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val phase = TextView(context).apply {
            textSize = 16f
            setTextColor(softText)
            alpha = 0.9f
            gravity = Gravity.CENTER
            text = context.getString(R.string.overlay_breathe_in)
        }
        root.addView(phase, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = (dm.heightPixels * 0.17f).toInt()
            })

        // Bottom block: lifted ~14% off the bottom (was ~20%, now down ~6vh).
        val controls = LinearLayout(context).apply {
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
            setOnClickListener { if (controlsActive) onDontWant() }
        }
        controls.addView(dontWant, LinearLayout.LayoutParams(
            (dm.widthPixels * 0.88f).toInt(), (dm.heightPixels * 0.21f).toInt()))

        val cont = TextView(context).apply {
            text = context.getString(R.string.overlay_continue_open, appLabel)
            isAllCaps = false
            textSize = 14f
            setTextColor(Palette.tint)
            gravity = Gravity.CENTER
            // More gap above the "continue" line so it sits a bit lower.
            setPadding(dp(16), dp(28), dp(16), dp(4))
            setOnClickListener { if (controlsActive) onContinue() }
        }
        controls.addView(cont, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(controls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        // Escape hatch. Flashed on entry so the user knows the screen is tappable, then it
        // fades. If the breathing genuinely fails to run (see the watchdog below) it comes
        // back for good, and tapping anywhere releases the controls - so a broken animation
        // can never trap you behind this overlay.
        val tapHint = TextView(context).apply {
            text = context.getString(R.string.overlay_tap_hint)
            textSize = 13f
            setTextColor(softText)
            alpha = 0f
            gravity = Gravity.CENTER
        }
        root.addView(tapHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = (dm.heightPixels * 0.17f).toInt() + dp(30)
            })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE)

        try {
            windowManager.addView(root, params)
            view = root
        } catch (t: Throwable) {
            android.util.Log.e("BreathingOverlay", "could not show", t)
            view = null
            return
        }

        // ── THE TAP ESCAPE  ─────────────────────────────────────────────────────────
        // ⚠️ 2026-08-05 - armed HERE, on the way up, and it is unconditional.
        //
        // It used to be installed by the 1.5s watchdog and then guarded by `controlsActive`
        // - which is false until the breath finishes. So for the eight seconds in between,
        // the screen said "if nothing happens, tap to enter" and a tap did precisely
        // nothing. That is the "it won't disappear for a while" bug: the user tapped, got
        // no response, and sat there until the hard ceiling took the window down.
        //
        // The guard is now the OPPOSITE way round: tapping the background enters the app
        // right up until the real buttons appear, and once they do the background stops
        // being a hit target so a stray tap can't fling you into the app past a decision
        // you were in the middle of making. TAP_ESCAPE_MS keeps a stray tap from the app
        // launch itself out of it.
        root.isClickable = true
        root.setOnClickListener {
            if (controlsActive) return@setOnClickListener      // the buttons are up; use them
            if (SystemClock.uptimeMillis() - shownAt >= TAP_ESCAPE_MS) onContinue()
        }

        // Brief "you can tap" flash, then out of the way.
        tapHint.animate().alpha(0.5f).setDuration(400)
            .withEndAction { tapHint.animate().alpha(0f).setStartDelay(900).setDuration(600).start() }
            .start()

        // ── WHEN THE BREATH STARTS  ─────────────────────────────────────────────────
        // ⚠️ 2026-08-05 - ON THE FIRST PAINTED FRAME, NOT ON LAYOUT. READ BEFORE CHANGING.
        //
        // The clock used to start at addView (via a layout listener / root.post). Being
        // laid out is not the same as being ON SCREEN: adding a window while the launching
        // app is still animating in - the Play Store takes its time - means the cover can
        // be several seconds late to composite. The breath meanwhile ran on wall time
        // against a surface nobody could see, so by the time the cover DID appear the orb
        // was frozen at whatever fraction it had reached, or back at zero having finished,
        // and all you got was a dark screen with the word "Breathe in" on it.
        //
        // onFirstDraw fires from the orb's own onDraw, so the breath cannot begin before
        // there is a frame to see it in. Show up late, still get a whole breath.
        orb.onFirstDraw = { startBreathing(orb, phase, tapHint, controls, dontWant) }

        // ...and a floor under that, in case the window never paints at all: nudge the
        // window manager once to force a relayout, and if the next moment still hasn't
        // produced a frame, run the gate anyway so it can time out and let the user in.
        handler.postDelayed({
            if (view !== root || started) return@postDelayed
            try { windowManager.updateViewLayout(root, params) } catch (_: Throwable) {}
            handler.postDelayed({
                if (view === root) startBreathing(orb, phase, tapHint, controls, dontWant)
            }, FIRST_DRAW_NUDGE_MS)
        }, FIRST_DRAW_NUDGE_MS)

        // ── HARD CEILING ON HOW LONG THIS CAN EXIST ─────────────────────────────────
        // A full-screen overlay that outlives its reason is a bricked phone, and on
        // 2026-08-04 that is exactly what happened: an early return in the service meant
        // nothing ever called hide(), and the user sat looking at "Breathe in" unable to
        // use the device at all.
        //
        // The service-side ordering is fixed, but this is a FULL-SCREEN OVERLAY - it does
        // not get to depend on somebody else remembering. It comes down by itself after
        // this whatever the rest of the app is doing.
        //
        // The one thing it will not do is fire while the user still hasn't HAD the thing
        // they were made to wait for. If the window composited late the breath started late,
        // and dumping them into the app mid-inhale is its own bug - so the ceiling slides
        // out to leave a decent gap after the buttons are due, and no further.
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (view !== root) return
                val now = SystemClock.uptimeMillis()
                val floor = breathStartedAt + RELEASE_DEADLINE_MS + DECIDE_GRACE_MS
                if (breathStartedAt > 0L && now < floor) {
                    handler.postDelayed(this, floor - now)
                    return
                }
                hide()
            }
        }, MAX_LIFETIME_MS)
    }

    private fun startBreathing(
        orb: BreathOrbView, phase: TextView, tapHint: TextView, controls: View, dontWant: Button,
    ) {
        if (started) return
        val root = view ?: return       // already taken down; nothing to breathe for
        started = true
        breathStartedAt = SystemClock.uptimeMillis()
        orbAnim = BreathOrbAnimator(orb, phase).also { a ->
            a.start(
                cycles = 1,
                onExhaleStart = {
                    // controls fade in over the (long) exhale, exactly as before
                    controls.visibility = View.VISIBLE
                    controls.animate().alpha(0.55f).setDuration(3600).withLayer().start()
                },
                onComplete = { releaseControls(phase, controls, dontWant) },
            )
        }

        // Watchdog: if the orb still hasn't moved a moment after the breath was supposed to
        // begin, treat the gate as broken - bring the hint back for good and hand over the
        // buttons rather than making the user wait out an animation that isn't running.
        handler.postDelayed({
            if (view !== root) return@postDelayed
            if (orbAnim?.hasAdvanced != true) {
                tapHint.animate().cancel()
                tapHint.alpha = 0.8f
                releaseControls(phase, controls, dontWant)
            }
        }, WATCHDOG_MS)

        // ── THE RELEASE DEADLINE  ────────────────────────────────────────────────────
        // ⚠️ 2026-08-04 - THIS IS THE FIX FOR "STUCK ON BREATHE IN", READ BEFORE REMOVING.
        //
        // releaseControls() used to have exactly two callers: the animation COMPLETING, and
        // the watchdog above when the animation never STARTED. Nothing covered the case in
        // between - an animation that starts and then stalls - and that is not a rare edge
        // case, it is what the screen turning off does to a running animator. The orb froze
        // mid-breath, onComplete never fired, the controls stayed invisible, and the phone
        // was unusable behind a screen that said "Breathe in" and responded to nothing.
        //
        // So the release is on a DEADLINE rather than on an event. It is posted from HERE,
        // when the breath actually starts, not from show(): a cover that composites late
        // gets its full breath and then its buttons, instead of the deadline burning down
        // while there was nothing on screen.
        handler.postDelayed({
            if (view === root) releaseControls(phase, controls, dontWant)
        }, RELEASE_DEADLINE_MS)
    }

    /** The breath is done (or never ran): light up the buttons and let them be pressed. */
    private fun releaseControls(phase: TextView, controls: View, dontWant: Button) {
        if (controlsActive) return
        controlsActive = true
        // Whatever released us, the breath is over. Stopping the animator here is what stops
        // a stalled-but-still-running pulse from fighting the phase label back to visible.
        orbAnim?.stop()
        phase.alpha = 0f
        controls.animate().cancel()
        controls.visibility = View.VISIBLE
        controls.alpha = 1f
        (dontWant.background as? GradientDrawable)?.setColor(accent)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        orbAnim?.stop(); orbAnim = null
        controlsActive = false
        started = false
        shownAt = 0L
        breathStartedAt = 0L
        view?.let { gone ->
            // removeView can throw if the window has already gone (the service was killed
            // and restarted, say). Either way this instance must stop believing it owns a
            // window - but use removeViewImmediate as a second try first, because a view we
            // still think is up and ISN'T removed is a full-screen cover nobody can dismiss.
            try {
                windowManager.removeView(gone)
            } catch (t: Throwable) {
                try { windowManager.removeViewImmediate(gone) } catch (_: Throwable) {}
                android.util.Log.e("BreathingOverlay", "could not remove", t)
            }
            view = null
        }
    }

    private companion object {
        /**
         * How long after the cover appears a background tap starts letting you through.
         * Short - the point is that the escape is real - but not zero, so the tap that
         * opened the app can't carry through into the cover that lands on top of it.
         */
        const val TAP_ESCAPE_MS = 1200L
        /** No painted frame by now: nudge the window manager, then run the gate regardless. */
        const val FIRST_DRAW_NUDGE_MS = 700L
        const val WATCHDOG_MS = 1500L
        /** The buttons light up by this point no matter what the animation is doing. */
        const val RELEASE_DEADLINE_MS = 12_000L
        /** Time to actually choose, once the buttons are due. Only used by a late breath. */
        const val DECIDE_GRACE_MS = 8_000L
        /** Nothing this app draws over the whole screen may outlive this, for any reason. */
        const val MAX_LIFETIME_MS = 25_000L
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
