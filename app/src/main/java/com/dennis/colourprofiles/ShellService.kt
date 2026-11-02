package com.dennis.colourprofiles

import android.content.Context
import android.os.IBinder
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/**
 * Runs in a separate process started by Shizuku, as the ADB shell user.
 * This is not a normal app process, so it only uses shell commands and binder calls.
 */
class ShellService() : IShellService.Stub() {

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        System.exit(0)
    }

    override fun applyMode(mode: Int, useMdnie: Boolean): String {
        if (mode !in 0..4) return "Rejected mode $mode"
        val before = readMode()
        val put = if (before == mode) "" else exec(SETTINGS, "put", "system", KEY, mode.toString())
        val mdnie = if (useMdnie) setViaMdnie(mode) else "not used"
        val after = readMode()
        return buildString {
            append("Setting: $before to $after")
            if (put.isNotEmpty()) append(" ($put)")
            append("\nSamsung colour service: $mdnie")
        }
    }

    override fun readMode(): Int =
        exec(SETTINGS, "get", "system", KEY).toIntOrNull() ?: -1

    override fun enableAccessibility(component: String): String {
        val current = exec(SETTINGS, "get", "secure", "enabled_accessibility_services")
        val items = current.split(':').filter { it.isNotBlank() && it != "null" }
        val next = if (component in items) items else items + component
        val put = exec(
            SETTINGS, "put", "secure", "enabled_accessibility_services", next.joinToString(":")
        )
        val on = exec(SETTINGS, "put", "secure", "accessibility_enabled", "1")
        return listOf(put, on).filter { it.isNotEmpty() }.joinToString("\n")
    }

    override fun diagnose(): String = buildString {
        val original = readMode()
        appendLine("uid: ${Process.myUid()}")
        appendLine("model: ${prop("ro.product.model")}")
        appendLine("android: ${prop("ro.build.version.release")}")
        appendLine("one ui: ${prop("ro.build.version.oneui")}")
        appendLine("build: ${prop("ro.build.display.id")}")
        appendLine("original setting: $original")
        appendLine("mdnie sysfs: ${exec(SHELL, "-c", SYSFS)}")
        appendLine("services:")
        appendLine(exec(SHELL, "-c", "service list | grep -iE 'mdnie|display|color' | head -n 12"))
        appendLine("mdnie methods: ${mdnieMethods()}")

        for (useMdnie in listOf(false, true)) {
            appendLine()
            appendLine(if (useMdnie) "PASS: setting + mDNIe" else "PASS: setting only")
            for (mode in listOf(2, 3, 4, 1, 0)) {
                val result = applyMode(mode, useMdnie).replace("\n", " | ")
                Thread.sleep(1200)
                appendLine("mode $mode: $result")
                appendLine("  sysfs: ${exec(SHELL, "-c", SYSFS)}")
                appendLine("  flinger: ${colourState()}")
            }
        }

        if (original in 0..4) applyMode(original, true)
        appendLine()
        appendLine("restored: ${readMode()}")
    }

    private fun colourState(): String = exec(
        SHELL, "-c",
        "dumpsys SurfaceFlinger | grep -iE 'colormode|color mode|colorProfile' | head -n 4"
    ).replace("\n", " / ")

    private fun prop(name: String) = exec("/system/bin/getprop", name).ifEmpty { "-" }

    private fun mdnieInterface(): Any? {
        val manager = Class.forName("android.os.ServiceManager")
        val binder = manager.getMethod("getService", String::class.java)
            .invoke(null, "mDNIe") as? IBinder ?: return null
        val stub = Class.forName("$MDNIE_INTERFACE\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }

    private fun setViaMdnie(mode: Int): String = try {
        val target = mdnieInterface()
        if (target == null) {
            "service not found"
        } else {
            // Looked up on the interface because the generated proxy class is private.
            val method = Class.forName(MDNIE_INTERFACE)
                .getMethod("setScreenMode", Int::class.javaPrimitiveType)
            "accepted (${method.invoke(target, mode)})"
        }
    } catch (e: InvocationTargetException) {
        "refused: ${e.targetException}"
    } catch (e: Exception) {
        "unavailable: $e"
    }

    private fun mdnieMethods(): String = try {
        Class.forName(MDNIE_INTERFACE).methods
            .map { it.name }
            .filter { it.contains("mode", ignoreCase = true) }
            .distinct().sorted().joinToString(", ")
    } catch (e: Exception) {
        "unavailable: $e"
    }

    private fun exec(vararg command: String): String = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
        output.trim()
    } catch (e: Exception) {
        "error: $e"
    }

    private companion object {
        const val SETTINGS = "/system/bin/settings"
        const val KEY = "screen_mode_setting"
        const val SHELL = "/system/bin/sh"
        const val MDNIE_INTERFACE = "com.samsung.android.hardware.display.ISemMdnieManager"
        const val SYSFS = "cat /sys/class/mdnie/mdnie/mode 2>&1 | head -n 1"
    }
}
