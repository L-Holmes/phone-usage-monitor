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
// WastedDonutView  (share of your waking life going to the scroll — updates live)
// =====================================================================================
// Reads the accelerometer (for tilt / "lying down") and the light sensor (lux), and
// exposes derived values. Register with start(), release with stop(). onUpdate fires
// (throttled) on the main thread whenever a fresh reading arrives.
class SensorMonitor(context: Context) : SensorEventListener {
    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    val hasAccel: Boolean = accel != null
    val hasLight: Boolean = light != null

    var lux: Float = -1f; private set          // -1 until a reading arrives
    private var gx = 0f; private var gy = -1f; private var gz = 0f   // normalised gravity
    private var gotAccel = false
    var onUpdate: (() -> Unit)? = null
    private var lastEmit = 0L

    fun start() {
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    fun stop() { sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = e.values[0]; val ay = e.values[1]; val az = e.values[2]
                val n = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat().coerceAtLeast(0.0001f)
                gx = ax / n; gy = ay / n; gz = az / n; gotAccel = true
            }
            Sensor.TYPE_LIGHT -> lux = e.values[0]
        }
        val now = System.currentTimeMillis()
        if (now - lastEmit >= 150) { lastEmit = now; onUpdate?.invoke() }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // Angle away from upright: 0deg held upright, 90deg flat or on its side.
    val tiltDeg: Float get() = Math.toDegrees(Math.acos((-gy).coerceIn(-1f, 1f).toDouble())).toFloat()
    // Side roll: 0deg upright, ~±90deg lying on a side.
    val rollDeg: Float get() = Math.toDegrees(Math.atan2(gx.toDouble(), (-gy).toDouble())).toFloat()
    val lyingDown: Boolean get() = gotAccel &&
        (tiltDeg >= AppConfig.LYING_TILT_DEG || Math.abs(rollDeg) >= AppConfig.LYING_SIDE_ROLL_DEG)
    val lightLevel: AppConfig.LightLevel? get() = if (lux < 0f) null else AppConfig.lightLevel(lux)
}
