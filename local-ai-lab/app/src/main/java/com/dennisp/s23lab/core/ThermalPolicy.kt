package com.dennisp.s23lab.core

/**
 * Safety gate for AI work, using Android's thermal status and headroom (PowerManager, API 29/30+).
 * Status values follow PowerManager.THERMAL_STATUS_*: 0 none, 1 light, 2 moderate, 3 severe,
 * 4 critical, 5 emergency, 6 shutdown; -1 means unavailable.
 * Headroom: 0 = no throttling, 1.0 = severe throttling; NaN or 0 on first read = unsupported.
 */
object ThermalPolicy {
    const val SEVERE = 3

    /** Do not start a run when the forecast headroom is at or above this (Google's "possibly moderate or higher"). */
    const val START_HEADROOM_LIMIT = 0.95

    const val MIN_BATTERY_PERCENT = 15

    fun statusName(status: Int): String = when (status) {
        0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"
        else -> "unavailable"
    }

    data class Gate(val ok: Boolean, val reason: String)

    /** Check before starting a run. */
    fun beforeRun(status: Int, headroom: Double, batteryPercent: Int, charging: Boolean, lowMemory: Boolean): Gate = when {
        status >= SEVERE -> Gate(false, "Thermal status is " + statusName(status) + ". Let the phone cool down.")
        headroom.isFinite() && headroom >= START_HEADROOM_LIMIT ->
            Gate(false, "Thermal headroom forecast is " + "%.2f".format(java.util.Locale.US, headroom) + " (1.0 = severe throttling). Let the phone cool down.")
        batteryPercent in 0 until MIN_BATTERY_PERCENT && !charging -> Gate(false, "Battery is at $batteryPercent%. Charge to at least $MIN_BATTERY_PERCENT%.")
        lowMemory -> Gate(false, "Android reports low memory. Close other apps first.")
        else -> Gate(true, "OK")
    }

    /** Check while generating: cancel at severe throttling or worse. */
    fun duringRun(status: Int): Gate =
        if (status >= SEVERE) Gate(false, "Stopped: thermal status reached " + statusName(status) + ".") else Gate(true, "OK")

    /**
     * Rate limiter for getThermalHeadroom(), which returns NaN if called more than once every 10 seconds.
     * Returns the cached value inside the window. Clock is injected for tests.
     */
    class HeadroomCache(private val minIntervalMs: Long = 10_000, private val read: () -> Float, private val clock: () -> Long) {
        private var lastAt = Long.MIN_VALUE
        private var last = Float.NaN

        /** Google's guidance: a first reading of NaN or 0 suggests the API is unsupported. True once any reading is above 0. */
        var supported = false
            private set

        @Synchronized
        fun get(): Double {
            val now = clock()
            if (lastAt == Long.MIN_VALUE || now - lastAt >= minIntervalMs) {
                val v = read()
                // A NaN later on (for example a too-early call) keeps the previous good value.
                if (v.isFinite()) last = v
                if (v.isFinite() && v > 0f) supported = true
                lastAt = now
            }
            return if (supported) last.toDouble() else Double.NaN
        }
    }
}
