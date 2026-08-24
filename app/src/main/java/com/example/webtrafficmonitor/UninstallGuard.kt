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



// =====================================================================================
// Uninstall prevention
// =====================================================================================
class UninstallGuardAdminReceiver : DeviceAdminReceiver() {
    // You can't *stop* deactivation, but you get the last word on the system screen.
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turn off the lock inside the app first. Remove protection anyway?"
}


object UninstallGuard {
    private const val PREFS = "uninstall_guard"
    private const val KEY = "enabled"

    fun admin(ctx: Context) = ComponentName(ctx, UninstallGuardAdminReceiver::class.java)

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isAdminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))

    /** The user-facing toggle (persisted). This is what the accessibility guard checks. */
    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (!on) deactivateAdmin(ctx)   // turning the toggle OFF lifts the block immediately
    }

    /** System "activate device admin?" prompt. */
    fun activationIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets the app keep you from uninstalling it while the lock is on.")
        }

    fun deactivateAdmin(ctx: Context) {
        if (dpm(ctx).isAdminActive(admin(ctx))) dpm(ctx).removeActiveAdmin(admin(ctx))
    }
}


// #####################################################################################
// #                                                                                   #
// #   GrantWindow  —  THE LOCK HAS TO LET GO OF THE PAGE THE PERMISSIONS LIVE ON      #
// #                                                                                   #
// #####################################################################################
//
//  THE BUG THIS EXISTS FOR (2026-08-24). Super hardcore is set up in a fixed order: the
//  uninstall lock has to be on before the mode can be chosen (Mode.setMode refuses
//  otherwise). The moment that lock is on, the accessibility service bounces this app's
//  App-info page in Settings straight to the home screen - it is where Uninstall and
//  Force stop are.
//
//  But it is ALSO the only place on Android 11+ where "Allow all the time" location can
//  be granted, and the only way back for any permission that has been denied twice. So
//  the setup order left people locked out of finishing the setup: the house rule cannot
//  be turned on because the lock that Super hardcore demanded is guarding the switch.
//
//  THE RULE, and it is the same one the Colour-correction page has always had: a guard
//  that would stop you turning something ON is not armed until it IS on. While a grant
//  we run on is still outstanding, the App-info page is left alone. The moment the last
//  one lands, the bounce is back, permanently.
//
//  WHAT THIS COSTS, stated plainly rather than buried: while the window is open, that
//  page is reachable, and Force stop is on it. Uninstall is not a way out either way -
//  device admin refuses it, which is the whole point of the lock - and the VISIT is
//  still recorded as a bypass attempt exactly as before, so nothing goes unseen. The
//  window is also Settings-only: the Play Store's listing for us matches the same page
//  text and has a real Uninstall button on it, and no permission was ever granted from
//  there, so it is never let through (see AppConfig.GRANT_WINDOW_PACKAGES).
//
//  NOT A TIMER ON PURPOSE. "You have five minutes from tapping the button" would close
//  the hole tighter and would also strand anyone who took a phone call on the way, or
//  who walked into Settings themselves rather than through our button. The permission
//  state is the honest condition: it is exactly the thing that decides whether the page
//  still has a job to do for the user.
object GrantWindow {
    /**
     * The grants that can only be finished from this app's own page in Settings, and are
     * still missing. Short user-facing names - the dev console shows them, so the answer to
     * "why is my App-info page not bouncing?" is on screen rather than in this file.
     *
     * Location is here because of ACCESS_BACKGROUND_LOCATION ("Allow all the time"), which
     * from Android 11 has no runtime dialog at all; fine/coarse and the beacon scan
     * permission are here because once they have been refused twice, Settings is the only
     * road back for them too.
     */
    fun outstanding(ctx: Context): List<String> = buildList {
        if (!HomeArea.hasPermissions(ctx)) add("Location (while using)")
        else if (!HomeArea.hasBackground(ctx)) add("Location (all the time)")
        if (!RoomBeacons.hasPermissions(ctx)) add("Nearby devices (beacon scan)")
    }

    /** True while the App-info bounce should stand down. See the note above. */
    fun isOpen(ctx: Context): Boolean = outstanding(ctx).isNotEmpty()
}
