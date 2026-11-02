package com.dennis.colourprofiles

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object ShizukuBridge {
    enum class Status { NOT_RUNNING, TOO_OLD, NO_PERMISSION, CONNECTING, READY }

    fun interface Listener {
        fun onBridgeChanged()
    }

    private class Request(val mode: Int, val useMdnie: Boolean)

    private const val PERMISSION_REQUEST = 4201

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private lateinit var args: Shizuku.UserServiceArgs

    @Volatile private var service: IShellService? = null
    @Volatile private var binding = false
    @Volatile private var pending: Request? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) {
                IShellService.Stub.asInterface(binder)
            } else {
                null
            }
            notifyChanged()
            pending?.let {
                pending = null
                applyMode(it.mode, it.useMdnie)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            notifyChanged()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        notifyChanged()
        connect()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        notifyChanged()
    }

    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            notifyChanged()
            connect()
        }

    fun init(context: Context) {
        args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellService::class.java.name)
        )
            .tag("shell")
            .processNameSuffix("shell")
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun status(): Status = when {
        !Shizuku.pingBinder() -> Status.NOT_RUNNING
        Shizuku.isPreV11() -> Status.TOO_OLD
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
        service != null -> Status.READY
        else -> Status.CONNECTING
    }

    fun requestPermission() {
        if (status() == Status.NO_PERMISSION) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun connect() {
        main.post {
            if (service != null || binding || status() != Status.CONNECTING) return@post
            binding = true
            try {
                Shizuku.bindUserService(args, connection)
            } catch (e: RuntimeException) {
                binding = false
            }
            main.postDelayed({ if (service == null) binding = false }, 8000)
        }
    }

    fun applyMode(mode: Int, useMdnie: Boolean, done: ((String) -> Unit)? = null) {
        worker.execute {
            val target = service
            if (target == null) {
                pending = Request(mode, useMdnie)
                connect()
                deliver(done, "Waiting for Shizuku")
                return@execute
            }
            val result = try {
                target.applyMode(mode, useMdnie)
            } catch (e: Exception) {
                service = null
                connect()
                "Failed: $e"
            }
            deliver(done, result)
        }
    }

    fun <T> call(block: (IShellService) -> T, done: (Result<T>) -> Unit) {
        worker.execute {
            val target = service
            val result: Result<T> = if (target == null) {
                connect()
                Result.failure(IllegalStateException("Shizuku isn't connected"))
            } else {
                runCatching { block(target) }
            }
            main.post { done(result) }
        }
    }

    private fun deliver(done: ((String) -> Unit)?, text: String) {
        if (done != null) main.post { done(text) }
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it.onBridgeChanged() } }
    }
}
