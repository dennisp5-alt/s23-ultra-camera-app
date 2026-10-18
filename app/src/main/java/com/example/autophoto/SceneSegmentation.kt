package com.example.autophoto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * SkyWater-Seg (Vincent Qin / Realcat), pretrained SegFormer-B2.
 * True model classes 0=background, 1=sky, 2=water, 3=person.
 *
 * The FP32 ONNX model is copied ONCE from APK assets into a private cache file,
 * rather than allocating a 100-MB managed Java byte array every invocation.
 * Inference is completely offline through ONNX Runtime Android.
 */
object SceneSegmentation {
    const val INPUT_SIDE = 384
    private const val MODEL = "skywater_segformer_b2_fp32.onnx"
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    data class Result(
        val width: Int,
        val height: Int,
        val sky: FloatArray?,
        val water: FloatArray?,
        val person: FloatArray?,
        val skyCoverage: Float,
        val waterCoverage: Float,
        val personCoverage: Float,
        val elapsedMillis: Long,
        val note: String
    ) {
        val detected: Boolean get() = sky != null && water != null &&
            person != null
    }

    private fun modelPath(context: Context): File {
        val target = File(context.filesDir, MODEL)
        if (target.exists() && target.length() > 90_000_000L) return target
        val temporary = File(context.filesDir, MODEL + ".part")
        try {
            context.assets.open(MODEL).use { input ->
                temporary.outputStream().buffered().use { output ->
                    input.copyTo(output, 128 * 1024)
                }
            }
            check(temporary.length() in 90_000_000L..130_000_000L) {
                "Unexpected pretrained scene model size"
            }
            check(temporary.renameTo(target) || target.exists()) {
                "Could not install scene model into app-private storage"
            }
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun infer(context: Context, src: Bitmap): Result {
        val started = SystemClock.elapsedRealtime()
        var stage = "initializing"
        try {
            stage = "preparing 384px RGB input"
            val bitmap = Bitmap.createScaledBitmap(src, INPUT_SIDE, INPUT_SIDE, true)
            val pixels = IntArray(INPUT_SIDE * INPUT_SIDE)
            bitmap.getPixels(pixels, 0, INPUT_SIDE, 0, 0, INPUT_SIDE, INPUT_SIDE)
            if (bitmap !== src) bitmap.recycle()
            val buffer = ByteBuffer.allocateDirect(3 * pixels.size * 4)
                .order(ByteOrder.nativeOrder())
            val floats = buffer.asFloatBuffer()
            for (c in 0..2) {
                val shift = 16 - c * 8
                for (px in pixels) {
                    val x = ((px shr shift) and 255) / 255f
                    floats.put((x - mean[c]) / std[c])
                }
            }
            floats.rewind()
            stage = "initializing ONNX segmentation session"
            val env = OrtEnvironment.getEnvironment()
            var sideW = 0
            var sideH = 0
            var skyMap: FloatArray? = null
            var waterMap: FloatArray? = null
            var personMap: FloatArray? = null
            var skyArea = 0f
            var waterArea = 0f
            var personArea = 0f

            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
            }.use { opts ->
                env.createSession(modelPath(context).absolutePath, opts).use { session ->
                    stage = "running trained four-class model"
                    OnnxTensor.createTensor(env, floats,
                        longArrayOf(1, 3, INPUT_SIDE.toLong(),
                            INPUT_SIDE.toLong())).use { tensor ->
                        session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                            stage = "reading scene logits"
                            val n = out[0].value as? Array<*>
                                ?: error("Unexpected model output container")
                            val channels = n[0] as? Array<*>
                                ?: error("Scene model returned no channel array")
                            check(channels.size == 4) {
                                "Expected 4 semantic classes, got " + channels.size
                            }
                            val rows = channels[0] as? Array<*>
                                ?: error("Missing scene logit rows")
                            sideH = rows.size
                            sideW = (rows.firstOrNull() as? FloatArray)?.size ?: 0
                            check(sideW in 32..1024 && sideH in 32..1024) {
                                "Invalid scene grid: " + sideW + "x" + sideH
                            }
                            val field = Array(4) { FloatArray(sideW * sideH) }
                            for (c in 0..3) {
                                val cr = channels[c] as? Array<*>
                                    ?: error("Scene class tensor " + c)
                                check(cr.size == sideH)
                                for (y in 0 until sideH) {
                                    val line = cr[y] as? FloatArray
                                        ?: error("Invalid scene tensor row")
                                    check(line.size == sideW)
                                    System.arraycopy(line, 0, field[c],
                                        y * sideW, sideW)
                                }
                            }
                            val count = sideW * sideH
                            val sky = FloatArray(count)
                            val water = FloatArray(count)
                            val person = FloatArray(count)
                            var ks = 0
                            var kw = 0
                            var kp = 0
                            for (i in 0 until count) {
                                val maximum = max(max(field[0][i], field[1][i]),
                                    max(field[2][i], field[3][i]))
                                var sum = 0.0
                                val probs = DoubleArray(4)
                                for (c in 0..3) {
                                    val v = exp((field[c][i] - maximum).toDouble())
                                    probs[c] = v
                                    sum += v
                                }
                                if (!sum.isFinite() || sum <= 0) continue
                                sky[i] = (probs[1] / sum).toFloat()
                                water[i] = (probs[2] / sum).toFloat()
                                person[i] = (probs[3] / sum).toFloat()
                                if (sky[i] > 0.65f) ks++
                                if (water[i] > 0.65f) kw++
                                if (person[i] > 0.65f) kp++
                            }
                            skyMap = sky
                            waterMap = water
                            personMap = person
                            skyArea = ks.toFloat() / count
                            waterArea = kw.toFloat() / count
                            personArea = kp.toFloat() / count
                        }
                    }
                }
            }

            val duration = SystemClock.elapsedRealtime() - started
            return Result(sideW, sideH, skyMap, waterMap, personMap,
                skyArea, waterArea, personArea, duration,
                "Trained sky/water/person masks active")
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath)
                throw failure
            Log.w("AutoPhotoScene", "Scene model failure at " + stage, failure)
            val reason = (failure.message ?: failure.javaClass.simpleName)
                .replace('\n', ' ').take(110)
            return Result(0, 0, null, null, null, 0f, 0f, 0f,
                SystemClock.elapsedRealtime() - started,
                "Scene fallback [" + stage + "]: " + reason)
        }
    }
}
