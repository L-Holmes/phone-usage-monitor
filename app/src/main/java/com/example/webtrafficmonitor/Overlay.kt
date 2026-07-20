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
     */
    fun show(
        reason: String,
        onGoBack: () -> Unit,
        onLeave: () -> Unit,
        onReport: () -> Unit,
        showGoBack: Boolean = true,
    ) {
        view?.let { existing ->
            existing.findViewById<TextView>(R.id.block_reason).text = reason
            // The cover can be re-shown for a DIFFERENT kind of block (a page cover
            // upgraded to an app cover, say) - keep the link's visibility current.
            existing.findViewById<View>(R.id.btn_go_back).visibility =
                if (showGoBack) View.VISIBLE else View.GONE
            return
        }

        val overlay = LayoutInflater.from(context).inflate(R.layout.overlay_block, null)
        overlay.findViewById<TextView>(R.id.block_reason).text = reason
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

    val isShowing: Boolean get() = view != null

    private val accent = 0xFF3E9C8E.toInt()
    private val accentMuted = 0xFF2A5E55.toInt()
    private val bg = 0xFF0A0B0D.toInt()
    private val softText = 0xFFCFEDE7.toInt()

    fun show(appLabel: String, onContinue: () -> Unit, onDontWant: () -> Unit) {
        if (view != null) return
        controlsActive = false
        started = false
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
            text = "Breathe in"
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
            text = "I don't want to access $appLabel"
            isAllCaps = false
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF06201B.toInt())
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
            text = "Continue to open $appLabel"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFF8FC2BA.toInt())
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
            text = "If nothing happens, tap to enter"
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

        // Brief "you can tap" flash, then out of the way.
        tapHint.animate().alpha(0.5f).setDuration(400)
            .withEndAction { tapHint.animate().alpha(0f).setStartDelay(900).setDuration(600).start() }
            .start()

        // Two independent triggers, first one wins (startBreathing is idempotent). The
        // global-layout listener alone was the whole start path, and if it never fired the
        // overlay just sat there dead - which is the "breathing doesn't start" bug.
        root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    startBreathing(orb, phase, controls, dontWant)
                }
            },
        )
        root.post { startBreathing(orb, phase, controls, dontWant) }

        // Watchdog: if the orb still hasn't moved a moment later, treat the gate as broken,
        // surface the hint and let a tap through rather than stranding the user.
        handler.postDelayed({
            if (view === root && orbAnim?.hasAdvanced != true) {
                tapHint.animate().cancel()
                tapHint.alpha = 0.8f
                releaseControls(phase, controls, dontWant)
                root.setOnClickListener { if (controlsActive) onContinue() }
                root.isClickable = true
            }
        }, WATCHDOG_MS)
    }

    private fun startBreathing(
        orb: BreathOrbView, phase: TextView, controls: View, dontWant: Button,
    ) {
        if (started) return
        started = true
        orbAnim = BreathOrbAnimator(orb, phase).also { a ->
            a.start(
                cycles = 1,
                onExhaleStart = {
                    // controls fade in over the (long) exhale, exactly as before
                    controls.visibility = View.VISIBLE
                    controls.animate().alpha(0.55f).setDuration(3600).start()
                },
                onComplete = { releaseControls(phase, controls, dontWant) },
            )
        }
    }

    /** The breath is done (or never ran): light up the buttons and let them be pressed. */
    private fun releaseControls(phase: TextView, controls: View, dontWant: Button) {
        if (controlsActive) return
        controlsActive = true
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
        view?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
            view = null
        }
    }

    private companion object {
        const val WATCHDOG_MS = 1500L
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
