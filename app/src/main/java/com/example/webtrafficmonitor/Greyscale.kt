package com.example.webtrafficmonitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

// =====================================================================================
//  GREYSCALE  -  drain the colour out of the screen to kill the visual pull in strict mode.
// =====================================================================================
//
//  IMPORTANT REALITY: a normal (Play Store) app CANNOT turn system greyscale on by itself.
//  System greyscale is the accessibility "colour correction" daltonizer set to monochrome,
//  which is a SECURE setting. Writing it needs WRITE_SECURE_SETTINGS - a signature|privileged
//  permission. Declaring it in the manifest does nothing, and there is NO runtime prompt to
//  grant it. Only ADB / root / a device-owner (enterprise MDM) can hold it. There is also no
//  overlay trick (an overlay draws on top; it can't desaturate what's beneath).
//
//  So the PRODUCTION flow is:
//    * READ the current state  - allowed with no permission (isOn).
//    * SEND the user to the setting in one tap (openGrayscaleSetting) with clear instructions.
//    * Because this app is an accessibility blocker, it can VERIFY greyscale is on and, if you
//      want, refuse to let the user proceed at high-risk times until they enable it - that's
//      "enforcement" through the app's own leverage, not through a system permission.
//
//  setEnabled() below is a best-effort bonus: it only works on rooted / enterprise / ADB-granted
//  builds where the permission happens to be held; on a normal build it just returns false.
// =====================================================================================
object Greyscale {

    // Secure-setting keys (stable Android internals).
    private const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val KEY_MODE = "accessibility_display_daltonizer"
    private const val MONOCHROME = 0   // daltonizer value that means full greyscale

    // Power-user / enterprise only (NOT part of the normal user flow):
    //   adb shell pm grant com.example.webtrafficmonitor android.permission.WRITE_SECURE_SETTINGS
    const val WRITE_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    /** Is system greyscale currently on?  Reading is allowed with no special permission. */
    fun isOn(context: Context): Boolean = try {
        Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, 0) == 1 &&
            Settings.Secure.getInt(context.contentResolver, KEY_MODE, -1) == MONOCHROME
    } catch (t: Throwable) { false }

    /** True only on the rare build where the write permission is actually held. */
    fun canControl(context: Context): Boolean =
        context.checkCallingOrSelfPermission(WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Best-effort toggle. Returns false (does nothing) on a normal build without the permission. */
    fun setEnabled(context: Context, on: Boolean): Boolean {
        if (!canControl(context)) return false
        return try {
            val cr = context.contentResolver
            if (on) {
                Settings.Secure.putInt(cr, KEY_MODE, MONOCHROME)
                Settings.Secure.putInt(cr, KEY_ENABLED, 1)
            } else {
                Settings.Secure.putInt(cr, KEY_ENABLED, 0)
            }
            true
        } catch (t: Throwable) { Log.w("Greyscale", "setEnabled failed: ${t.message}"); false }
    }

    /** Send the user to where they can turn greyscale on. No stable public deep-link exists for
     *  the exact toggle, so we open Accessibility settings; the setup screen gives the path. */
    fun openGrayscaleSetting(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.w("Greyscale", "openGrayscaleSetting failed: ${t.message}")
        }
    }

    // ── Optional user lock: block the Colour-correction settings page (so greyscale can't
    //    be turned back off). Enforced in the accessibility service; see AppConfig.COLOR_CORRECTION_PAGE.
    private const val PREFS = "greyscale"
    private const val KEY_LOCK_COLOR_PAGE = "lock_color_page"

    fun isLockColorPage(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCK_COLOR_PAGE, false)

    fun setLockColorPage(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCK_COLOR_PAGE, on).apply()
    }
}
