package com.dennis.colourprofiles

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

class ForegroundWatcher : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var requestedMode = -1
    private var keyboards = emptySet<String>()

    private val applyRequested = Runnable {
        ShizukuBridge.applyMode(requestedMode, Prefs.useMdnie(this))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        requestedMode = -1
        keyboards = enabledKeyboards()
        ShizukuBridge.connect()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || !Prefs.enabled(this)) {
            // The test screen and the tile can change the mode, so re-apply on the next app.
            forgetRequest()
            return
        }
        if (pkg in IGNORED || pkg in keyboards) return

        val rule = Prefs.rule(this, pkg)
        val mode = when {
            rule != null -> rule
            isActivity(pkg, event.className?.toString()) -> Prefs.defaultMode(this)
            else -> return
        }
        request(mode)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(applyRequested)
        super.onDestroy()
    }

    private fun request(mode: Int) {
        if (mode == requestedMode) return
        handler.removeCallbacks(applyRequested)
        requestedMode = mode
        handler.postDelayed(applyRequested, SETTLE_MS)
    }

    private fun forgetRequest() {
        handler.removeCallbacks(applyRequested)
        requestedMode = -1
    }

    private fun isActivity(pkg: String, className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        return try {
            packageManager.getActivityInfo(ComponentName(pkg, className), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun enabledKeyboards(): Set<String> {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return manager.enabledInputMethodList.map { it.packageName }.toSet()
    }

    private companion object {
        const val SETTLE_MS = 180L
        val IGNORED = setOf("android", "com.android.systemui")
    }
}
