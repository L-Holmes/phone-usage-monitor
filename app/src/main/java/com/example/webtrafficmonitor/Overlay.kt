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
     * CAN A COVER BE LEFT BEHIND?  (asked directly, 2026-08-27, and worth answering here)
     *
     * Not by the process dying: this is a TYPE_ACCESSIBILITY_OVERLAY window, and the window
     * token belongs to this process. When the process goes - killed for an ANR, killed under
     * memory pressure, force-stopped - the window goes with it. There is no path by which a
     * cover outlives the app that drew it, so nothing needs cleaning up at the next start.
     *
     * The real risk was never a leaked WINDOW, it was a leaked STATE: a cover still on screen
     * while the thing that should take it down had stopped running. Two ways that happened,
     * both now closed:
     *
     *   1. the service's main thread wedged, so the cover's buttons could not be delivered
     *      and the recheck loop never ran (see the stall watchdog in AccessibilityService);
     *   2. `view` here disagreed with reality, because addView threw half way and we kept a
     *      reference to a window that was never added - or added one on top of an existing
     *      one. detach() below makes both states impossible to hold.
     */
    private fun detach() {
        val existing = view ?: return
        view = null
        runCatching { windowManager.removeViewImmediate(existing) }
            .onFailure { android.util.Log.w("OverlayController", "stale cover would not detach", it) }
    }

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
            // upgraded to an app cover, say) - keep the link's visibility current...
            existing.findViewById<View>(R.id.btn_go_back).visibility =
                if (showGoBack) View.VISIBLE else View.GONE
            // ...and its BUTTONS current with it. They used to be bound once, on the first
            // cover, and never again - so after an upgrade, "Go to home screen" was still
            // running the previous block's exit, aimed at the previous block's app.
            bindButtons(existing, onGoBack, onLeave, onReport)
            return
        }

        val overlay = LayoutInflater.from(context).inflate(R.layout.overlay_block, null)
        overlay.findViewById<TextView>(R.id.block_reason).text = reason
        setDetailsOn(overlay, details)
        overlay.findViewById<View>(R.id.btn_go_back).visibility =
            if (showGoBack) View.VISIBLE else View.GONE
        bindButtons(overlay, onGoBack, onLeave, onReport)

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

        // Belt: nothing of ours may already be attached. `view` is null here (we returned
        // early above if it was not), but an addView that threw part-way in the past could
        // have left a window up with no reference to it - and a second full-screen opaque
        // cover on top of an invisible first one is a phone nobody can use.
        detach()
        try {
            windowManager.addView(container, params)
            view = container
        } catch (t: Throwable) {
            // Never crash the service over a cover; log it instead.
            android.util.Log.e("OverlayController", "could not show block cover", t)
            // addView can throw AFTER the window was registered (BadTokenException happens
            // before, an inflation failure can happen after). Ask for it to go either way.
            view = container
            detach()
        }


    }

    /**
     * (Re)bind the cover's three actions. Typed as View, not Button: "go back" and "report"
     * are quiet TextView links now, and only "leave" is still an actual Button.
     */
    private fun bindButtons(
        root: View,
        onGoBack: () -> Unit,
        onLeave: () -> Unit,
        onReport: () -> Unit,
    ) {
        root.findViewById<View>(R.id.btn_go_back).setOnClickListener {
            pressAnimation(it)
            onGoBack()
        }
        // ⚠️ 2026-08-27. The press animation and the status line go on BEFORE onLeave(),
        // deliberately. Leaving takes a few hundred milliseconds of real work - handing a
        // browser a fresh tab, asking the system for Home - and if the service's main thread
        // happens to be busy reading a heavy screen, that work starts late. With no
        // acknowledgement in between, a tap that registered perfectly is indistinguishable
        // from a dead button, and the report that follows is "I press it and nothing
        // happens". One frame of feedback costs nothing and answers that.
        root.findViewById<View>(R.id.btn_leave).setOnClickListener {
            pressAnimation(it)
            flashStatusOn(root, context.getString(R.string.block_leaving))
            onLeave()
        }
        root.findViewById<View>(R.id.btn_report).setOnClickListener { onReport() }
    }

    /**
     * Take the cover down. Idempotent, and safe to call when nothing is up.
     *
     * removeViewImmediate rather than removeView, deliberately: removeView schedules the
     * teardown through the view hierarchy's own traversal, so a cover asked to go while the
     * main thread is under pressure can stay on screen for another frame or several. This is
     * the one window in the app where "eventually" is not good enough - it is covering the
     * whole screen and the user is trying to get out from under it.
     */
    fun hide() = detach()

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
     * Only ever a RESPONSE TO A TAP - "Go back", or "Go to home screen" acknowledging
     * itself. The status line stays empty otherwise. The flash restarts on every tap, even
     * if the text is identical, so a user mashing a button on a cover that won't budge can
     * SEE that each press registered. Without that it looks frozen and they assume the
     * button is broken.
     */
    fun flashStatus(message: String) {
        flashStatusOn(view ?: return, message)
    }

    /** [flashStatus], against a specific root - used while the cover is still being built. */
    private fun flashStatusOn(root: View, message: String) {
        val status = root.findViewById<TextView>(R.id.block_status) ?: return
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
