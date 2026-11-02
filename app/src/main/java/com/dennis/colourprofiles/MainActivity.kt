package com.dennis.colourprofiles

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuAction: Button
    private lateinit var watcherStatus: TextView
    private lateinit var watcherAction: Button
    private lateinit var watcherHint: TextView
    private lateinit var ruleList: LinearLayout
    private lateinit var noRules: TextView

    private val bridgeListener = ShizukuBridge.Listener { refreshSetup() }

    private data class InstalledApp(val pkg: String, val label: String) {
        override fun toString() = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shizukuStatus = findViewById(R.id.shizukuStatus)
        shizukuAction = findViewById(R.id.shizukuAction)
        watcherStatus = findViewById(R.id.watcherStatus)
        watcherAction = findViewById(R.id.watcherAction)
        watcherHint = findViewById(R.id.watcherHint)
        ruleList = findViewById(R.id.ruleList)
        noRules = findViewById(R.id.noRules)

        watcherAction.setOnClickListener { turnOnWatcher() }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }
        findViewById<Button>(R.id.addApp).setOnClickListener { pickApp() }

        val auto = findViewById<Switch>(R.id.autoSwitch)
        auto.isChecked = Prefs.enabled(this)
        auto.setOnCheckedChangeListener { _, on ->
            Prefs.setEnabled(this, on)
            if (!on) ShizukuBridge.applyMode(Prefs.defaultMode(this), Prefs.useMdnie(this))
        }

        bindSpinner(findViewById(R.id.defaultSpinner), Prefs.defaultMode(this)) {
            Prefs.setDefaultMode(this, it)
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.addListener(bridgeListener)
        ShizukuBridge.connect()
        refreshSetup()
        refreshRules()
    }

    override fun onPause() {
        ShizukuBridge.removeListener(bridgeListener)
        super.onPause()
    }

    private fun refreshSetup() {
        val status = ShizukuBridge.status()
        shizukuStatus.setText(
            when (status) {
                ShizukuBridge.Status.READY -> R.string.status_ready
                ShizukuBridge.Status.CONNECTING -> R.string.status_connecting
                ShizukuBridge.Status.NO_PERMISSION -> R.string.status_no_permission
                ShizukuBridge.Status.TOO_OLD -> R.string.status_too_old
                ShizukuBridge.Status.NOT_RUNNING -> R.string.status_not_running
            }
        )
        when (status) {
            ShizukuBridge.Status.READY, ShizukuBridge.Status.CONNECTING -> {
                shizukuAction.visibility = View.GONE
            }
            ShizukuBridge.Status.NO_PERMISSION -> {
                shizukuAction.visibility = View.VISIBLE
                shizukuAction.setText(R.string.action_allow)
                shizukuAction.setOnClickListener { ShizukuBridge.requestPermission() }
            }
            ShizukuBridge.Status.TOO_OLD, ShizukuBridge.Status.NOT_RUNNING -> {
                val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                shizukuAction.visibility = View.VISIBLE
                shizukuAction.setText(
                    if (launch != null) R.string.action_open_shizuku else R.string.action_get_shizuku
                )
                shizukuAction.setOnClickListener {
                    startActivity(
                        launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD))
                    )
                }
            }
        }

        val watcherOn = isWatcherEnabled()
        watcherStatus.setText(if (watcherOn) R.string.watcher_on else R.string.watcher_off)
        watcherAction.visibility = if (watcherOn) View.GONE else View.VISIBLE
        if (watcherOn) watcherHint.visibility = View.GONE
    }

    private fun isWatcherEnabled(): Boolean {
        val component = ComponentName(this, ForegroundWatcher::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':')
        return component.flattenToString() in enabled || component.flattenToShortString() in enabled
    }

    private fun turnOnWatcher() {
        if (ShizukuBridge.status() != ShizukuBridge.Status.READY) {
            openAccessibilitySettings()
            return
        }
        val component = ComponentName(this, ForegroundWatcher::class.java).flattenToString()
        ShizukuBridge.call({ it.enableAccessibility(component) }) { result ->
            refreshSetup()
            if (result.isFailure || !isWatcherEnabled()) openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        watcherHint.visibility = View.VISIBLE
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun refreshRules() {
        ruleList.removeAllViews()
        val rules = Prefs.rules(this).entries.sortedBy { appLabel(it.key).lowercase() }
        noRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for ((pkg, mode) in rules) {
            val row = inflater.inflate(R.layout.row_rule, ruleList, false)
            row.findViewById<TextView>(R.id.name).text = appLabel(pkg)
            appIcon(pkg)?.let { row.findViewById<ImageView>(R.id.icon).setImageDrawable(it) }
            row.findViewById<Button>(R.id.remove).setOnClickListener {
                Prefs.removeRule(this, pkg)
                refreshRules()
            }
            bindSpinner(row.findViewById(R.id.modeSpinner), mode) {
                Prefs.setRule(this, pkg, it)
            }
            ruleList.addView(row)
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        if (pkg == Prefs.LIGHTROOM) "Lightroom" else pkg
    }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun bindSpinner(spinner: Spinner, currentId: Int, onPick: (Int) -> Unit) {
        val modes = DisplayMode.entries
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, modes.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(modes.indexOf(DisplayMode.fromId(currentId)), false)
        var selected = currentId
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val picked = modes[position].id
                if (picked != selected) {
                    selected = picked
                    onPick(picked)
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun pickApp() {
        Thread {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(launcher, 0)
                .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                .filter { it.pkg != packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { if (!isFinishing) showAppPicker(apps) }
        }.start()
    }

    private fun showAppPicker(apps: List<InstalledApp>) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, apps.toMutableList())
        val search = EditText(this).apply {
            setHint(R.string.search_apps)
            setSingleLine()
        }
        val list = ListView(this)
        list.adapter = adapter
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(search)
            addView(
                list,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (360 * resources.displayMetrics.density).toInt()
                )
            )
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.add_app).setView(body).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().trim()
                adapter.clear()
                adapter.addAll(apps.filter { it.label.contains(query, ignoreCase = true) })
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position) ?: return@setOnItemClickListener
            Prefs.setRule(this, app.pkg, DisplayMode.BASIC.id)
            dialog.dismiss()
            refreshRules()
            Toast.makeText(this, app.label, Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
    }
}
