package com.dennisp.s23lab.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.dennisp.s23lab.core.MemoryReport
import com.dennisp.s23lab.core.ThermalPolicy
import java.util.concurrent.Executor

/** Device readings: thermal status and headroom, battery, and phase memory snapshots. Read-only. */
class DeviceMonitor(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activity = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val headroomCache = ThermalPolicy.HeadroomCache(
        read = { if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(HEADROOM_FORECAST_S) else Float.NaN },
        clock = { SystemClock.elapsedRealtime() },
    )

    /** PowerManager thermal status, or -1 below Android 10. */
    fun thermalStatus(): Int = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1

    /** Forecast headroom HEADROOM_FORECAST_S seconds ahead; polled at most every 10 s; NaN when unsupported. */
    fun thermalHeadroom(): Double = headroomCache.get()

    val headroomSupported: Boolean get() = headroomCache.supported

    private var listener: Any? = null

    /** Calls back on every thermal status change (Android 10+). */
    fun watchThermal(executor: Executor, onChange: (Int) -> Unit) {
        if (Build.VERSION.SDK_INT < 29 || listener != null) return
        val l = PowerManager.OnThermalStatusChangedListener { onChange(it) }
        power.addThermalStatusListener(executor, l)
        listener = l
    }

    fun stopWatchingThermal() {
        if (Build.VERSION.SDK_INT >= 29) (listener as? PowerManager.OnThermalStatusChangedListener)?.let { power.removeThermalStatusListener(it) }
        listener = null
    }

    data class Battery(val percent: Int, val tempC: Double, val charging: Boolean, val currentMicroA: Long?, val chargeCounterMicroAh: Long?)

    fun battery(): Battery {
        val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        // Units and sign of CURRENT_NOW vary by manufacturer; recorded raw, not interpreted as energy here.
        val current = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Long.MIN_VALUE && it != 0L }
        val counter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it != Long.MIN_VALUE && it > 0L }
        return Battery(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            tempC = if (temp != Int.MIN_VALUE) temp / 10.0 else Double.NaN,
            charging = plugged || batteryManager.isCharging,
            currentMicroA = current,
            chargeCounterMicroAh = counter,
        )
    }

    fun lowMemory(): Boolean = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }.lowMemory

    /** One memory snapshot of this process. Takes tens of milliseconds; do not call from the UI thread in a loop. */
    fun memory(phase: String): MemoryReport.Snapshot {
        val d = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val a = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        fun stat(key: String): Double = (d.getMemoryStat(key)?.toDoubleOrNull() ?: Double.NaN) / 1024.0
        return MemoryReport.Snapshot(
            phase = phase,
            totalPssMb = stat("summary.total-pss").takeIf { it.isFinite() } ?: d.totalPss / 1024.0,
            javaHeapMb = stat("summary.java-heap"),
            nativeHeapMb = stat("summary.native-heap"),
            graphicsMb = stat("summary.graphics"),
            codeMb = stat("summary.code"),
            stackMb = stat("summary.stack"),
            privateOtherMb = stat("summary.private-other"),
            systemMb = stat("summary.system"),
            availMemMb = a.availMem / 1048576.0,
            lowMemory = a.lowMemory,
        )
    }

    fun gate(): ThermalPolicy.Gate {
        val b = battery()
        return ThermalPolicy.beforeRun(thermalStatus(), thermalHeadroom(), b.percent, b.charging, lowMemory())
    }

    fun summaryLine(): String {
        val b = battery()
        val h = thermalHeadroom()
        return "Thermal: " + ThermalPolicy.statusName(thermalStatus()) +
            (if (h.isFinite()) " · headroom %.2f".format(java.util.Locale.US, h) else " · headroom not reported") +
            " · battery " + (if (b.percent >= 0) "${b.percent}%" else "?") +
            (if (b.tempC.isFinite()) " · battery %.1f °C".format(java.util.Locale.US, b.tempC) else "") +
            (if (b.charging) " · CHARGING (results not comparable)" else "")
    }

    companion object {
        const val HEADROOM_FORECAST_S = 10
    }
}
