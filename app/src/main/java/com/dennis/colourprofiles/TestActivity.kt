package com.dennis.colourprofiles

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class TestActivity : Activity() {
    private lateinit var result: TextView
    private lateinit var runReport: Button

    private val bridgeListener = ShizukuBridge.Listener { showStatusIfIdle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)
        result = findViewById(R.id.result)
        runReport = findViewById(R.id.runReport)

        val buttons = findViewById<LinearLayout>(R.id.modeButtons)
        for (mode in DisplayMode.entries) {
            val button = Button(this)
            button.text = mode.label
            button.isAllCaps = false
            button.setOnClickListener { setMode(mode) }
            buttons.addView(button)
        }

        val mdnie = findViewById<Switch>(R.id.mdnieSwitch)
        mdnie.isChecked = Prefs.useMdnie(this)
        mdnie.setOnCheckedChangeListener { _, on -> Prefs.setUseMdnie(this, on) }

        runReport.setOnClickListener { runFullTest() }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.addListener(bridgeListener)
        ShizukuBridge.connect()
        showStatusIfIdle()
    }

    override fun onPause() {
        ShizukuBridge.removeListener(bridgeListener)
        super.onPause()
    }

    private fun showStatusIfIdle() {
        val current = result.text.toString()
        if (current == getString(R.string.result_idle) ||
            current == getString(R.string.status_not_running)
        ) {
            result.setText(
                if (ShizukuBridge.status() == ShizukuBridge.Status.NOT_RUNNING) {
                    R.string.status_not_running
                } else {
                    R.string.result_idle
                }
            )
        }
    }

    private fun setMode(mode: DisplayMode) {
        ShizukuBridge.applyMode(mode.id, Prefs.useMdnie(this)) {
            result.text = "${mode.label}\n$it"
        }
    }

    private fun runFullTest() {
        runReport.isEnabled = false
        result.setText(R.string.report_running)
        ShizukuBridge.call({ it.diagnose() }) { outcome ->
            runReport.isEnabled = true
            val report = outcome.getOrElse { "Failed: $it" }
            result.text = report
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Colour Profiles report", report))
            android.widget.Toast.makeText(
                this, R.string.report_copied, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
