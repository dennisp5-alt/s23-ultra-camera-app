package com.dennisp.s23lab.engine

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.benchmark
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns one LiteRT-LM engine. Rules that fix the v0.3 lifecycle problems:
 *  - One operation at a time (load, generate, benchmark, unload) behind a Mutex. A second request
 *    waits; it never runs on the same conversation at the same time.
 *  - Generation holds the lock until native generation has reported done or error, so unload and
 *    close can never free native resources while a reply is still streaming.
 *  - Cancelling a coroutine also cancels the native process (cancelProcess) and waits for it to stop.
 *    (The library's Flow API does not do this on its own: its awaitClose is empty.)
 *  - All native calls run on one dedicated thread.
 * This class has no Activity or UI types, so it can move into a separate :ai service later unchanged.
 */
@OptIn(ExperimentalApi::class)
class LabEngine(private val cacheDir: String) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "lab-engine").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val mutex = Mutex()

    private var engine: Engine? = null
    private val activeLock = Any()
    private var active: Conversation? = null

    data class State(
        val backend: String? = null,
        val busy: String? = null,
        val loadMs: Long = 0,
        val maxNumTokens: Int? = null,
        val fellBackToCpu: Boolean = false,
        val message: String = "No model loaded",
    ) {
        val loaded: Boolean get() = backend != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class GenOptions(
        val system: String? = null,
        /** Greedy by default (topK = 1) so repeated runs do the same work. */
        val sampler: SamplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 1),
        val maxOutputTokens: Int = 128,
        val thinking: Boolean = false,
    )

    data class GenResult(
        val text: String,
        val channels: Map<String, String>,
        val wallMs: Long,
        val firstChunkMs: Long?,
        val info: BenchmarkInfo?,
        val cancelled: Boolean,
        val error: String?,
    )

    /** Loads the model. With [fallbackToCpu], a GPU failure is followed by a CPU attempt. */
    suspend fun load(modelPath: String, gpu: Boolean, maxNumTokens: Int?, fallbackToCpu: Boolean): State = mutex.withLock {
        withContext(NonCancellable + dispatcher) {
            closeEngineLocked()
            _state.update { it.copy(busy = "Loading on " + (if (gpu) "GPU" else "CPU") + "…") }
            // Needed for Conversation.getBenchmarkInfo() (real token counts). Set before the engine is created.
            ExperimentalFlags.enableBenchmark = true
            val first = tryInit(modelPath, gpu, maxNumTokens)
            val result = if (first.isSuccess || !gpu || !fallbackToCpu) first else tryInit(modelPath, false, maxNumTokens)
            result.fold(
                onSuccess = { (e, ms, onGpu) ->
                    engine = e
                    val fellBack = gpu && !onGpu
                    State(
                        backend = if (onGpu) "GPU" else "CPU", loadMs = ms, maxNumTokens = maxNumTokens, fellBackToCpu = fellBack,
                        message = "Ready on " + (if (onGpu) "GPU" else "CPU") + " · load " + ms + " ms" +
                            (if (fellBack) " · GPU failed (" + first.exceptionOrNull()?.message + "), using CPU" else ""),
                    )
                },
                onFailure = { State(message = "Load failed: " + (it.message ?: it.javaClass.simpleName)) },
            ).also { _state.value = it }
        }
    }

    private fun tryInit(modelPath: String, gpu: Boolean, maxNumTokens: Int?): Result<Triple<Engine, Long, Boolean>> {
        val started = SystemClock.elapsedRealtime()
        val candidate = Engine(EngineConfig(
            modelPath = modelPath,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = maxNumTokens,
            cacheDir = cacheDir,
        ))
        return try {
            candidate.initialize()
            Result.success(Triple(candidate, SystemClock.elapsedRealtime() - started, gpu))
        } catch (e: Throwable) {
            runCatching { if (candidate.isInitialized()) candidate.close() }
            Result.failure(e)
        }
    }

    /**
     * One reply in a fresh conversation (no history, so every run is comparable). [onText] receives the
     * text so far, on the engine's callback thread.
     */
    suspend fun generate(prompt: String, options: GenOptions = GenOptions(), onText: (String) -> Unit = {}): GenResult = mutex.withLock {
        val eng = engine ?: return GenResult("", emptyMap(), 0, null, null, false, "No model loaded")
        _state.update { it.copy(busy = "Generating…") }
        val conversation = withContext(dispatcher) {
            eng.createConversation(ConversationConfig(
                systemInstruction = options.system?.let { Contents.of(it) },
                samplerConfig = options.sampler,
                maxOutputToken = options.maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = options.thinking),
            ))
        }
        synchronized(activeLock) { active = conversation }
        val text = StringBuilder()
        val channels = LinkedHashMap<String, String>()
        val done = CompletableDeferred<Unit>()
        val started = SystemClock.elapsedRealtime()
        var firstAt = 0L
        var cancelled = false
        var error: String? = null
        try {
            withContext(dispatcher) {
                conversation.sendMessageAsync(Message.user(prompt), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val chunk = message.toString()
                        if (chunk.isNotEmpty()) {
                            if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                            text.append(chunk)
                            onText(text.toString())
                        }
                        for ((k, v) in message.channels) channels[k] = (channels[k] ?: "") + v
                    }
                    override fun onDone() { done.complete(Unit) }
                    override fun onError(throwable: Throwable) { done.completeExceptionally(throwable) }
                })
            }
            try {
                done.await()
            } catch (c: CancellationException) {
                cancelled = true
                if (!currentCoroutineContext().isActive) {
                    // The caller was cancelled: stop native generation and wait until it really stops.
                    withContext(NonCancellable) {
                        cancelActive()
                        withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { done.await() } }
                    }
                    throw c
                }
                // Otherwise the native side reported a cancellation (cancelCurrent()): fall through.
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            val info = withContext(NonCancellable + dispatcher) { runCatching { conversation.getBenchmarkInfo() }.getOrNull() }
            return@withLock GenResult(
                text.toString(), channels, SystemClock.elapsedRealtime() - started,
                if (firstAt > 0) firstAt - started else null, info, cancelled, error,
            )
        } finally {
            withContext(NonCancellable + dispatcher) {
                synchronized(activeLock) {
                    active = null
                    runCatching { conversation.close() }
                }
            }
            _state.update { it.copy(busy = null) }
        }
    }

    /** Asks the running reply to stop. Safe to call from any thread at any time. */
    fun cancelCurrent() = cancelActive()

    private fun cancelActive() {
        synchronized(activeLock) { active?.let { runCatching { it.cancelProcess() } } }
    }

    /**
     * LiteRT-LM's own benchmark: a fresh engine with exactly [prefillTokens] and [decodeTokens]. Unloads
     * the chat engine first so two engines are never in memory together. Blocking native work; it cannot
     * be interrupted part-way, only between runs.
     */
    suspend fun syntheticBenchmark(modelPath: String, gpu: Boolean, prefillTokens: Int, decodeTokens: Int, maxNumTokens: Int?): BenchmarkInfo =
        mutex.withLock {
            withContext(NonCancellable + dispatcher) {
                if (engine != null) {
                    closeEngineLocked()
                    _state.value = State(message = "Chat model unloaded for the benchmark.")
                }
                _state.update { it.copy(busy = "Benchmark on " + (if (gpu) "GPU" else "CPU") + ": $prefillTokens prefill / $decodeTokens decode tokens…") }
                try {
                    benchmark(
                        modelPath = modelPath,
                        backend = if (gpu) Backend.GPU() else Backend.CPU(),
                        prefillTokens = prefillTokens,
                        decodeTokens = decodeTokens,
                        cacheDir = cacheDir,
                        maxNumTokens = maxNumTokens,
                    )
                } finally {
                    _state.update { it.copy(busy = null) }
                }
            }
        }

    /** Stops any reply, waits for it to finish, then frees the engine. */
    suspend fun unload(message: String = "Model unloaded; native resources released.") {
        cancelActive()
        mutex.withLock {
            withContext(NonCancellable + dispatcher) {
                closeEngineLocked()
                _state.value = State(message = message)
            }
        }
    }

    /** Final shutdown: unload, then stop the engine thread. */
    suspend fun shutdown() {
        unload()
        executor.shutdown()
    }

    private fun closeEngineLocked() {
        val e = engine ?: return
        engine = null
        runCatching { if (e.isInitialized()) e.close() }
    }

    companion object {
        const val STOP_TIMEOUT_MS = 10_000L
    }
}
