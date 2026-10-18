package com.example.autophoto

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offline, real trained Google MediaPipe general selfie segmentation.
 * Predicts a soft person/background probability at 256x256.
 * This is NOT full ADE20K multi-class scene segmentation; distant people,
 * heavy occlusions and complex backgrounds can be missed.
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
        val start = SystemClock.elapsedRealtime()
        try {
            val modelBytes = context.assets.open("mediapipe_person_segmentation.tflite")
                .use { it.readBytes() }
            check(modelBytes.size > 100_000 &&
                modelBytes.copyOfRange(4, 8).contentEquals(
                    byteArrayOf(84, 70, 76, 51))) {
                "Invalid pretrained TFLite segmentation asset"
            }
            val model = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
            model.put(modelBytes)
            model.rewind()

            // Official MediaPipe graph uses 256x256 float32 RGB normalized 0..1
            // and keep_aspect_ratio=false for the general segmentation model.
            val resized = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
            val rgb = IntArray(SIDE * SIDE)
            resized.getPixels(rgb, 0, SIDE, 0, 0, SIDE, SIDE)
            if (resized !== bitmap) resized.recycle()

            val input = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4)
                .order(ByteOrder.nativeOrder())
            for (p in rgb) {
                input.putFloat(((p shr 16) and 255) / 255f)
                input.putFloat(((p shr 8) and 255) / 255f)
                input.putFloat((p and 255) / 255f)
            }
            input.rewind()
            val output = ByteBuffer.allocateDirect(SIDE * SIDE * 4)
                .order(ByteOrder.nativeOrder())
            Interpreter(model, Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }).use { net ->
                net.allocateTensors()
                val shape = intArrayOf(1, SIDE, SIDE, 3)
                val outputShape = intArrayOf(1, SIDE, SIDE, 1)
                check(net.getInputTensor(0).shape().contentEquals(shape))
                check(net.getOutputTensor(0).shape().contentEquals(outputShape))
                check(net.getInputTensor(0).dataType() == DataType.FLOAT32)
                check(net.getOutputTensor(0).dataType() == DataType.FLOAT32)
                net.run(input, output)
            }

            output.rewind()
            val floats = output.asFloatBuffer()
            val probabilities = FloatArray(SIDE * SIDE)
            var highConfidence = 0
            var strongConfidence = 0
            var sumConfidence = 0f
            for (i in probabilities.indices) {
                val p = floats.get(i)
                check(p.isFinite()) { "Segmentation produced non-finite values" }
                probabilities[i] = p.coerceIn(0f, 1f)
                if (p >= 0.70f) highConfidence++
                if (p >= 0.88f) strongConfidence++
                sumConfidence += probabilities[i]
            }
            val coverage = highConfidence.toFloat() / probabilities.size
            val strongCoverage = strongConfidence.toFloat() / probabilities.size
            val mean = sumConfidence / probabilities.size
            // Avoid hallucinating 'people' on roses/foliage; ignore barely
            // perceptible, extreme-coverage or unconvincing predictions.
            val reliable = coverage in 0.004f..0.75f &&
                strongCoverage >= 0.001f && mean >= 0.006f
            val time = SystemClock.elapsedRealtime() - start
            return if (reliable)
                Result(probabilities, coverage, time, "Person mask active")
            else
                Result(null, coverage, time,
                    "No confident person mask — tonal masks used")
        } catch (e: Exception) {
            Log.w("AutoPhotoSemantic", "Person model unavailable; regional fallback", e)
            return Result(null, 0f, SystemClock.elapsedRealtime() - start,
                "Person model unavailable — tonal fallback")
        }
    }
}
