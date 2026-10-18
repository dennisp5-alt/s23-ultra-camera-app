package com.example.autophoto

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder

/**
 * Offline person segmentation using Google's model-bundled ML Kit SDK.
 *
 * The old raw MediaPipe model needed the MediaPipe custom operator
 * "Convolution2DTransposeBias". Plain TensorFlow Lite cannot run that model
 * without a custom op resolver, hence v0.3.7 always fell back.
 * ML Kit bundles its compatible person segmenter and native runtime.
 *
 * This is a person/background mask, not general semantic scene segmentation.
 */
object PersonSegmentation {
    const val SIDE = 256

    data class Result(
        val mask: FloatArray?,
        val coverage: Float,
        val elapsedMillis: Long,
        val note: String
    ) {
        val detected: Boolean get() = mask != null
    }

    fun infer(context: Context, bitmap: Bitmap): Result {
        val started = SystemClock.elapsedRealtime()
        var stage = "initializing"
        try {
            // ML Kit library includes the model at APK build/install time.
            // SINGLE_IMAGE_MODE treats each selected JPEG independently.
            stage = "creating client"
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
            val segmenter = Segmentation.getClient(options)

            try {
                stage = "preparing image"
                val small = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
                val image = InputImage.fromBitmap(small, 0)
                try {
                    stage = "running bundled model"
                    // Called only from Dispatchers.Default: blocking is safe
                    // here and never runs on the Android main/UI thread.
                    val result = Tasks.await(segmenter.process(image))
                    stage = "reading mask"
                    val mw = result.width
                    val mh = result.height
                    check(mw == SIDE && mh == SIDE) {
                        "Unexpected ML Kit raw mask dimensions: " + mw + "x" + mh
                    }
                    val buffer = result.buffer.duplicate()
                    buffer.order(ByteOrder.nativeOrder())
                    buffer.rewind()
                    check(buffer.remaining() >= SIDE * SIDE * 4) {
                        "ML Kit returned incomplete float32 mask"
                    }
                    val floats = buffer.asFloatBuffer()
                    val probabilities = FloatArray(SIDE * SIDE)
                    var confident = 0
                    var strong = 0
                    var sum = 0f
                    for (i in probabilities.indices) {
                        val p = floats.get(i)
                        check(p.isFinite()) { "Invalid person-mask confidence" }
                        val value = p.coerceIn(0f, 1f)
                        probabilities[i] = value
                        if (value >= 0.70f) confident++
                        if (value >= 0.88f) strong++
                        sum += value
                    }
                    val coverage = confident.toFloat() / probabilities.size
                    val strongFraction = strong.toFloat() / probabilities.size
                    val mean = sum / probabilities.size
                    // Avoid falsely "detecting a person" in foliage or sky.
                    // When not confident we report that fact distinctly from
                    // a runtime failure; tonal masks still run reliably.
                    val reliable = coverage in 0.004f..0.75f &&
                        strongFraction >= 0.001f && mean >= 0.006f
                    val ms = SystemClock.elapsedRealtime() - started
                    return if (reliable)
                        Result(probabilities, coverage, ms,
                            "Person mask active (bundled ML Kit)")
                    else
                        Result(null, coverage, ms,
                            "No confident person detected; tonal masks used")
                } finally {
                    if (small !== bitmap) small.recycle()
                }
            } finally {
                segmenter.close()
            }
        } catch (e: Exception) {
            Log.e("AutoPhotoSemantic", "Person segmentation failed during " + stage, e)
            val reason = (e.cause?.message ?: e.message ?: "Unknown error")
                .replace('\n', ' ')
                .take(120)
            return Result(null, 0f,
                SystemClock.elapsedRealtime() - started,
                "Segmentation error [" + stage + "]: " +
                    e.javaClass.simpleName + ": " + reason)
        }
    }
}
