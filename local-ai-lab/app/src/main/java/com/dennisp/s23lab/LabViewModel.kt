package com.dennisp.s23lab

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dennisp.s23lab.core.BenchProtocol
import com.dennisp.s23lab.core.MemoryReport
import com.dennisp.s23lab.core.ModelCheck
import com.dennisp.s23lab.core.ThermalPolicy
import com.dennisp.s23lab.device.DeviceMonitor
import com.dennisp.s23lab.engine.LabEngine
import com.dennisp.s23lab.model.ModelStore
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outlives the Activity, so the model survives rotation, theme changes and resizing. */
class LabViewModel(application: Application) : AndroidViewModel(application) {
    val store = ModelStore(application)
    val device = DeviceMonitor(application)
    val engine = LabEngine(application.cacheDir.path)
    val engineState: StateFlow<LabEngine.State> = engine.state

    enum class Backend(val label: String) { AUTO("Auto GPU → CPU"), CPU("CPU"), GPU("GPU") }

    data class Ui(
        val manifest: ModelStore.Manifest? = null,
        val hasModel: Boolean = false,
        val importing: String? = null,
        val backend: Backend = Backend.AUTO,
        /** null = the model's built-in default. */
        val maxNumTokens: Int? = 1280,
        val reply: String = "",
        val replyMetrics: String = "",
        val checkOutcomes: List<ModelCheck.Outcome> = emptyList(),
        val checkVerdict: String = "",
        val task: String? = null,
        val progress: String = "",
        val benchRuns: List<BenchProtocol.Run> = emptyList(),
        val benchConfig: String? = null,
        val memoryPhases: List<MemoryReport.Snapshot> = emptyList(),
        val memoryCycles: List<MemoryReport.Snapshot> = emptyList(),
        val memoryVerdict: MemoryReport.LeakVerdict? = null,
        val deviceLine: String = "",
        val thermalStatus: Int = -1,
        val notice: String = "",
    ) {
        val working: Boolean get() = task != null || importing != null
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    @Volatile private var stopRequested = false
    private var taskJob: Job? = null
    private val thermalExecutor = Executors.newSingleThreadExecutor()

    init {
        refreshModel()
        device.watchThermal(thermalExecutor) { status ->
            _ui.update { it.copy(thermalStatus = status) }
            if (!ThermalPolicy.duringRun(status).ok) {
                stopRequested = true
                engine.cancelCurrent()
                _ui.update { it.copy(notice = ThermalPolicy.duringRun(status).reason) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val line = withContext(Dispatchers.Default) { device.summaryLine() }
                _ui.update { it.copy(deviceLine = line, thermalStatus = device.thermalStatus()) }
                delay(10_000)
            }
        }
    }

    fun setBackend(b: Backend) = _ui.update { it.copy(backend = b) }
    fun setMaxNumTokens(n: Int?) = _ui.update { it.copy(maxNumTokens = n) }

    private fun refreshModel() = _ui.update { it.copy(manifest = store.manifest(), hasModel = store.hasModel()) }

    // ---------- Model ----------

    fun importModel(uri: Uri) {
        if (ui.value.working || engineState.value.loaded) return
        _ui.update { it.copy(importing = "Importing…", notice = "") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.import(uri) { bytes -> _ui.update { it.copy(importing = "Importing… " + bytes / 1048576 + " MB") } } }
            }
            _ui.update {
                it.copy(importing = null, notice = result.fold(
                    { m -> "Imported " + m.displayName + " (" + m.sizeBytes / 1048576 + " MB, SHA-256 " + m.shortHash + "…). Next: load it and run the model check." },
                    { e -> "Import failed: " + e.message + ". The previous model, if any, is unchanged." },
                ), checkOutcomes = emptyList(), checkVerdict = "")
            }
            refreshModel()
        }
    }

    /** Five factual prompts, thinking off, greedy, fresh conversation each. Needs a loaded model. */
    fun runModelCheck() = startTask("Model check") {
        val outcomes = mutableListOf<ModelCheck.Outcome>()
        for ((i, probe) in ModelCheck.PROBES.withIndex()) {
            if (stopRequested) break
            progress("Model check " + (i + 1) + "/" + ModelCheck.PROBES.size)
            val r = engine.generate(probe.prompt, LabEngine.GenOptions(system = "Answer briefly.", maxOutputTokens = 48))
            outcomes += ModelCheck.evaluate(probe, r.text, r.channels, r.error)
            _ui.update { it.copy(checkOutcomes = outcomes.toList()) }
        }
        val v = ModelCheck.verdict(outcomes)
        if (outcomes.size == ModelCheck.PROBES.size) withContext(Dispatchers.IO) { store.recordCheck(v.passed, v.text, v.thinkingSeen) }
        refreshModel()
        _ui.update { it.copy(checkVerdict = v.text) }
    }

    // ---------- Engine and chat ----------

    fun load() = startTask("Loading") {
        val s = ui.value
        engine.load(store.modelFile.path, gpu = s.backend != Backend.CPU, maxNumTokens = s.maxNumTokens, fallbackToCpu = s.backend == Backend.AUTO)
    }

    fun unload() = startTask("Unloading") { engine.unload() }

    fun ask(prompt: String) {
        if (prompt.isBlank()) return
        val tool = OfflineTools.run(prompt)
        if (tool != null) {
            _ui.update { it.copy(reply = tool.output, replyMetrics = "Validated CPU tool (" + tool.label + "). No AI inference used.") }
            return
        }
        if (!engineState.value.loaded) {
            _ui.update { it.copy(reply = "", replyMetrics = "No validated tool matches and no model is loaded. No answer was invented.") }
            return
        }
        startTask("Generating") {
            _ui.update { it.copy(reply = "", replyMetrics = "") }
            val r = engine.generate(prompt, LabEngine.GenOptions(
                system = "You are a private offline assistant. Be accurate, state uncertainty, and do not claim you learn during inference.",
                maxOutputTokens = 256,
            )) { text -> _ui.update { it.copy(reply = text) } }
            val i = r.info
            val metrics = buildString {
                append(engineState.value.backend ?: "?")
                if (i != null) {
                    append(" · first token ").append(BenchProtocol.f2(i.timeToFirstTokenInSecond)).append(" s")
                    append(" · prefill ").append(i.lastPrefillTokenCount).append(" tokens at ").append(BenchProtocol.f1(i.lastPrefillTokensPerSecond)).append(" tok/s")
                    append(" · decode ").append(i.lastDecodeTokenCount).append(" tokens at ").append(BenchProtocol.f1(i.lastDecodeTokensPerSecond)).append(" tok/s")
                } else append(" · token counts unavailable")
                append(" · total ").append(r.wallMs).append(" ms")
                if (r.cancelled) append(" · stopped")
                r.error?.let { append(" · error: ").append(it) }
            }
            _ui.update { it.copy(reply = r.text, replyMetrics = metrics) }
        }
    }

    /** Stops the current reply now, and any benchmark or profile after its current step. */
    fun stop() {
        stopRequested = true
        engine.cancelCurrent()
        progress("Stopping after the current step…")
    }

    // ---------- Benchmark ----------

    fun runBenchmark(config: BenchProtocol.Config, backends: List<Backend>) = startTask("Benchmark") {
        val runs = mutableListOf<BenchProtocol.Run>()
        _ui.update { it.copy(benchRuns = emptyList(), benchConfig = config.name) }
        val path = store.modelFile.path
        val ctx = ui.value.maxNumTokens
        val failed = mutableSetOf<Backend>()
        fun nameOf(b: Backend) = if (b == Backend.GPU) "GPU" else "CPU"
        // 1) Synthetic: exact token counts, fresh engine each run. Backends are interleaved and the order flips
        // every run, so slow drifts in clocks and temperature affect CPU and GPU equally. (Run in blocks, the
        // GPU slowed to about 60% after the CPU block, then recovered over 10 runs.)
        synthetic@ for ((li, len) in config.prefillLengths.withIndex()) {
            val warmups = config.warmupsFor(li)
            for (i in 0 until warmups + config.runs) {
                for (b in if (i % 2 == 0) backends else backends.reversed()) {
                    if (b in failed) continue
                    if (stopRequested) break@synthetic
                    val gate = device.gate()
                    if (!gate.ok) { notice(gate.reason); break@synthetic }
                    val name = nameOf(b)
                    progress(name + " synthetic, prefill " + len + ": run " + (i + 1) + "/" + (warmups + config.runs) + (if (i < warmups) " (warm-up)" else ""))
                    val run = measured(name, BenchProtocol.SYNTHETIC, "prefill $len", len, i < warmups, i, ctx) {
                        // Same context as the prompt runs, so the two kinds are comparable.
                        val info = engine.syntheticBenchmark(path, b == Backend.GPU, len, config.decodeTokens, ctx)
                        Measured(info.initTimeInSecond, info.timeToFirstTokenInSecond, info.lastPrefillTokenCount,
                            info.lastDecodeTokenCount, info.lastPrefillTokensPerSecond, info.lastDecodeTokensPerSecond, "")
                    }
                    runs += run
                    _ui.update { it.copy(benchRuns = runs.toList()) }
                    // A GPU that cannot start will not start on the next run either.
                    if (!run.ok && i == 0) { failed += b; notice(name + " failed: " + run.error) }
                }
            }
        }
        // 2) Real prompts on a loaded engine, one backend at a time (loading is too slow to interleave).
        if (config.promptRuns > 0 && !stopRequested) promptLoop@ for (b in backends) {
            if (b in failed) continue
            val name = nameOf(b)
            val state = engine.load(path, b == Backend.GPU, ctx, fallbackToCpu = false)
            if (!state.loaded) { notice(state.message); continue@promptLoop }
            for ((label, prompt) in BenchProtocol.PROMPTS) {
                for (i in 0 until 1 + config.promptRuns) {
                    if (stopRequested) break@promptLoop
                    val gate = device.gate()
                    if (!gate.ok) { notice(gate.reason); break@promptLoop }
                    progress(name + " prompt '" + label + "': run " + (i + 1) + "/" + (1 + config.promptRuns) + (if (i == 0) " (warm-up)" else ""))
                    runs += measured(name, BenchProtocol.PROMPT, label, 0, i == 0, i, ctx) {
                        val r = engine.generate(prompt, LabEngine.GenOptions(system = "Answer briefly and factually.", maxOutputTokens = config.maxOutputTokens))
                        val info = r.info
                        if (r.error != null || info == null) error(r.error ?: "No benchmark info")
                        Measured(if (i == 0) state.loadMs / 1000.0 else Double.NaN, info.timeToFirstTokenInSecond,
                            info.lastPrefillTokenCount, info.lastDecodeTokenCount, info.lastPrefillTokensPerSecond,
                            info.lastDecodeTokensPerSecond, r.text.take(160))
                    }
                    _ui.update { it.copy(benchRuns = runs.toList()) }
                }
            }
        }
        engine.unload("Benchmark finished; model unloaded.")
        progress(if (stopRequested) "Stopped. " + runs.size + " runs recorded." else "Finished: " + runs.size + " runs.")
    }

    private data class Measured(val init: Double, val ttft: Double, val prefillTokens: Int, val decodeTokens: Int,
                                val prefillTps: Double, val decodeTps: Double, val preview: String)

    private suspend fun measured(backend: String, kind: String, label: String, target: Int, warmup: Boolean, index: Int,
                                 maxNumTokens: Int?, block: suspend () -> Measured): BenchProtocol.Run {
        val thermalBefore = device.thermalStatus()
        val headroom = device.thermalHeadroom()
        val battery = device.battery()
        val sampler = PeakSampler(device).also { it.start(CoroutineScope(Dispatchers.Default)) }
        val t0 = SystemClock.elapsedRealtime()
        val result = runCatching { block() }
        val wall = SystemClock.elapsedRealtime() - t0
        val peak = sampler.stop()
        val m = result.getOrNull()
        return BenchProtocol.Run(
            backend = backend, kind = kind, label = label, prefillTarget = target, warmup = warmup, index = index,
            initSeconds = m?.init ?: Double.NaN, ttftSeconds = m?.ttft ?: Double.NaN,
            prefillTokens = m?.prefillTokens ?: 0, decodeTokens = m?.decodeTokens ?: 0,
            prefillTps = m?.prefillTps ?: Double.NaN, decodeTps = m?.decodeTps ?: Double.NaN, wallMs = wall,
            peakPssMb = peak?.totalPssMb ?: Double.NaN, peakNativeMb = peak?.nativeHeapMb ?: Double.NaN,
            peakGraphicsMb = peak?.graphicsMb ?: Double.NaN, thermalBefore = thermalBefore, thermalAfter = device.thermalStatus(),
            headroomBefore = headroom, batteryTempC = battery.tempC, charging = battery.charging, currentMicroA = battery.currentMicroA,
            outputPreview = m?.preview ?: "", error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            maxNumTokens = maxNumTokens,
        )
    }

    fun benchmarkJson(): String = BenchProtocol.toJson(meta("benchmark"), ui.value.benchRuns)
    fun benchmarkCsv(): String = BenchProtocol.toCsv(ui.value.benchRuns)
    fun benchmarkSummary(): String =
        "S23 Local AI Lab " + BuildConfig.VERSION_NAME + " · " + (ui.value.benchConfig ?: "") + " benchmark · model " +
            (ui.value.manifest?.let { it.displayName + " " + it.shortHash } ?: "unknown") + "\n" +
            BenchProtocol.summaryText(BenchProtocol.summarise(ui.value.benchRuns))

    // ---------- Memory ----------

    fun runMemoryProfile(cycles: Int) = startTask("Memory profile") {
        val s = ui.value
        val gpu = s.backend != Backend.CPU
        val path = store.modelFile.path
        val phases = mutableListOf<MemoryReport.Snapshot>()
        val cycleSnaps = mutableListOf<MemoryReport.Snapshot>()
        _ui.update { it.copy(memoryPhases = emptyList(), memoryCycles = emptyList(), memoryVerdict = null) }
        fun publish() = _ui.update { it.copy(memoryPhases = phases.toList(), memoryCycles = cycleSnaps.toList()) }

        engine.unload("Unloaded for the memory profile.")
        settle()
        val idle = snapshot("Idle, no model")
        phases += idle; publish()
        progress("Loading…")
        val state = engine.load(path, gpu, s.maxNumTokens, fallbackToCpu = s.backend == Backend.AUTO)
        if (!state.loaded) { notice(state.message); return@startTask }
        settle()
        phases += snapshot("After load (" + state.backend + ", context " + (s.maxNumTokens ?: "default") + ")"); publish()
        progress("Generating one reply while sampling memory…")
        val sampler = PeakSampler(device, "Peak during reply").also { it.start(CoroutineScope(Dispatchers.Default)) }
        engine.generate(BenchProtocol.PROMPTS[1].second, LabEngine.GenOptions(maxOutputTokens = 128))
        sampler.stop()?.let { phases += it }
        phases += snapshot("After reply, model loaded"); publish()
        engine.unload("Unloaded for the memory profile.")
        settle()
        val firstUnload = snapshot("After unload")
        phases += firstUnload; publish()
        for (c in 1..cycles) {
            if (stopRequested) break
            if (!device.gate().ok) { notice(device.gate().reason); break }
            progress("Load/unload cycle $c/$cycles")
            val st = engine.load(path, state.backend == "GPU", s.maxNumTokens, fallbackToCpu = false)
            if (!st.loaded) { notice(st.message); break }
            engine.generate(ModelCheck.PROBES[0].prompt, LabEngine.GenOptions(maxOutputTokens = 16))
            engine.unload("Unloaded for the memory profile.")
            settle()
            cycleSnaps += snapshot("Cycle $c after unload"); publish()
        }
        _ui.update { it.copy(memoryVerdict = MemoryReport.leakVerdict(idle, firstUnload, cycleSnaps)) }
        progress("Memory profile finished.")
    }

    private suspend fun snapshot(phase: String) = withContext(Dispatchers.Default) { device.memory(phase) }

    /** Gives freed memory a moment to be returned and collected before measuring. */
    private suspend fun settle() {
        withContext(Dispatchers.Default) { Runtime.getRuntime().gc() }
        delay(1_000)
    }

    fun memoryJson(): String = MemoryReport.toJson(meta("memory"), ui.value.memoryPhases, ui.value.memoryCycles, ui.value.memoryVerdict)

    // ---------- Helpers ----------

    private fun meta(kind: String): Map<String, Any?> {
        val m = ui.value.manifest
        val b = device.battery()
        return linkedMapOf(
            "kind" to kind, "app_version" to BuildConfig.VERSION_NAME, "generated_utc" to Instant.now().toString(),
            "device" to Build.MANUFACTURER + " " + Build.MODEL, "soc" to (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null),
            "android" to Build.VERSION.RELEASE, "runtime" to ModelStore.RUNTIME,
            "model_name" to m?.displayName, "model_sha256" to m?.sha256, "model_size_bytes" to m?.sizeBytes,
            "model_check_passed" to m?.checkPassed, "max_num_tokens" to ui.value.maxNumTokens,
            "protocol" to ui.value.benchConfig, "thermal_headroom_supported" to device.headroomSupported,
            "charging_at_export" to b.charging,
        )
    }

    private fun startTask(name: String, block: suspend () -> Unit) {
        if (ui.value.working) return
        stopRequested = false
        _ui.update { it.copy(task = name, progress = "", notice = "") }
        taskJob = viewModelScope.launch {
            try { block() } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) notice(name + " failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                _ui.update { it.copy(task = null) }
                refreshModel()
            }
        }
    }

    private fun progress(text: String) = _ui.update { it.copy(progress = text) }
    private fun notice(text: String) = _ui.update { it.copy(notice = text) }

    override fun onCleared() {
        stopRequested = true
        engine.cancelCurrent()
        device.stopWatchingThermal()
        thermalExecutor.shutdown()
        // viewModelScope is already cancelled here; finish the safe shutdown on an app-wide scope.
        ShutdownScope.launch { engine.shutdown() }
    }

    private object ShutdownScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Samples memory every [intervalMs] and keeps the snapshot with the highest total PSS. */
class PeakSampler(private val device: DeviceMonitor, private val phase: String = "Peak", private val intervalMs: Long = 500) {
    @Volatile private var peak: MemoryReport.Snapshot? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                val s = device.memory(phase)
                if (peak == null || s.totalPssMb > peak!!.totalPssMb) peak = s
                delay(intervalMs)
            }
        }
    }

    suspend fun stop(): MemoryReport.Snapshot? {
        job?.let { it.cancel(); it.join() }
        return peak
    }
}
