package com.example.autophoto

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Genuine offline neural inference with the pretrained MIRNet TensorFlow Lite model.
 * The network was trained for low-light restoration, not all photographic tasks.
 * Overlapping full-inference tiles are blended then mapped onto native-resolution detail.
 */
object FullAIProcessor {
    private const val TILE = 400
    private const val STEP = 320
    private const val MAX_WORK = 1280
    private const val FADE = 64

    private fun positions(length: Int): List<Int> {
        if (length <= TILE) return listOf(0)
        val result = mutableListOf(0)
        while (result.last() + TILE < length) {
            val next = min(result.last() + STEP, length - TILE)
            if (next == result.last()) break
            result.add(next)
        }
        return result
    }

    private fun feather(x: Int, start: Int, extent: Int): Float {
        var v = 1f
        if (start > 0 && x < FADE) {
            val t = x.toFloat() / FADE
            v *= 0.08f + 0.92f * t * t * (3f - 2f * t)
        }
        if (start + TILE < extent && x > TILE - FADE) {
            val t = (TILE - 1 - x).toFloat() / FADE
            v *= 0.08f + 0.92f * t * t * (3f - 2f * t)
        }
        return v
    }

    private fun model(context: Context): ByteBuffer =
        context.assets.openFd("mirnet_neural.tflite").use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }

    fun enhance(context: Context, src: Bitmap, update: (Int) -> Unit): Bitmap {
        val scale = min(1.0, MAX_WORK.toDouble() / max(src.width, src.height))
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        val reduced = Bitmap.createScaledBitmap(src, w, h, true)
        val base = IntArray(w * h)
        reduced.getPixels(base, 0, w, 0, 0, w, h)
        if (reduced !== src) reduced.recycle()

        val xs = positions(w)
        val ys = positions(h)
        val total = xs.size * ys.size
        val corrections = FloatArray(w * h * 3)
        val weights = FloatArray(w * h)
        val input = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())

        val options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        Interpreter(model(context), options).use { interpreter ->
            interpreter.resizeInput(0, intArrayOf(1, TILE, TILE, 3))
            interpreter.allocateTensors()
            check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, TILE, TILE, 3)))
            check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, TILE, TILE, 3)))
            check(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
            check(interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32)
            var completed = 0
            for (top in ys) for (left in xs) {
                input.clear()
                for (ty in 0 until TILE) {
                    val offset = min(top + ty, h - 1) * w
                    for (tx in 0 until TILE) {
                        val p = base[offset + min(left + tx, w - 1)]
                        input.putFloat(((p shr 16) and 255) / 255f)
                        input.putFloat(((p shr 8) and 255) / 255f)
                        input.putFloat((p and 255) / 255f)
                    }
                }
                input.rewind()
                output.clear()
                interpreter.run(input, output) // Actual machine-learning model execution.
                output.rewind()
                val result = output.asFloatBuffer()
                for (ty in 0 until TILE) {
                    val sy = top + ty
                    if (sy >= h) continue
                    val wy = feather(ty, top, h)
                    for (tx in 0 until TILE) {
                        val sx = left + tx
                        if (sx >= w) continue
                        val index = sy * w + sx
                        val weight = wy * feather(tx, left, w)
                        val color = base[index]
                        val j = (ty * TILE + tx) * 3
                        val k = index * 3
                        corrections[k] += (result.get(j).coerceIn(0f, 1f) * 255f -
                            ((color shr 16) and 255)).coerceIn(-115f, 175f) * weight
                        corrections[k + 1] += (result.get(j + 1).coerceIn(0f, 1f) * 255f -
                            ((color shr 8) and 255)).coerceIn(-115f, 175f) * weight
                        corrections[k + 2] += (result.get(j + 2).coerceIn(0f, 1f) * 255f -
                            (color and 255)).coerceIn(-115f, 175f) * weight
                        weights[index] += weight
                    }
                }
                completed++
                update(completed * 85 / total)
            }
        }

        for (i in weights.indices) {
            val inverse = 1f / weights[i].coerceAtLeast(0.001f)
            val k = i * 3
            corrections[k] *= inverse
            corrections[k + 1] *= inverse
            corrections[k + 2] *= inverse
        }
        update(87)

        // Strong neural enhancement on dark photos; restrained on bright HDR images.
        var sum = 0.0
        var n = 0
        val skip = max(1, base.size / 30000)
        for (i in base.indices step skip) {
            val p = base[i]
            sum += 0.2126 * ((p shr 16) and 255) +
                0.7152 * ((p shr 8) and 255) + 0.0722 * (p and 255)
            n++
        }
        val avg = sum / n.coerceAtLeast(1)
        val strength = when {
            avg < 65 -> 0.95f
            avg < 105 -> 0.85f
            avg < 150 -> 0.75f
            else -> 0.60f
        }

        fun sample(x: Float, y: Float, channel: Int): Float {
            val px = x.coerceIn(0f, (w - 1).toFloat())
            val py = y.coerceIn(0f, (h - 1).toFloat())
            val ix = px.toInt()
            val iy = py.toInt()
            val right = min(ix + 1, w - 1)
            val down = min(iy + 1, h - 1)
            val dx = px - ix
            val dy = py - iy
            val a = corrections[(iy * w + ix) * 3 + channel]
            val b = corrections[(iy * w + right) * 3 + channel]
            val c = corrections[(down * w + ix) * 3 + channel]
            val d = corrections[(down * w + right) * 3 + channel]
            return (a * (1f - dx) + b * dx) * (1f - dy) +
                (c * (1f - dx) + d * dx) * dy
        }

        val edited = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val batchRows = min(16, src.height)
        val pixels = IntArray(src.width * batchRows)
        for (top in 0 until src.height step batchRows) {
            val rowCount = min(batchRows, src.height - top)
            src.getPixels(pixels, 0, src.width, 0, top, src.width, rowCount)
            for (ry in 0 until rowCount) {
                val py = ((top + ry + 0.5f) * h / src.height - 0.5f)
                for (x in 0 until src.width) {
                    val i = ry * src.width + x
                    val p = pixels[i]
                    val alpha = p ushr 24
                    if (alpha == 0) continue
                    val px = (x + 0.5f) * w / src.width - 0.5f
                    val r = ((p shr 16) and 255).toFloat()
                    val g = ((p shr 8) and 255).toFloat()
                    val b = (p and 255).toFloat()
                    val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val highlight = 1f - 0.65f * ((luminance - 200f) / 55f).coerceIn(0f, 1f)
                    val blend = strength * highlight
                    val dr = sample(px, py, 0)
                    val dg = sample(px, py, 1)
                    val db = sample(px, py, 2)
                    val dl = 0.2126f * dr + 0.7152f * dg + 0.0722f * db
                    val rr = (r + blend * (dl + 0.60f * (dr - dl))).roundToInt().coerceIn(0, 255)
                    val gg = (g + blend * (dl + 0.60f * (dg - dl))).roundToInt().coerceIn(0, 255)
                    val bb = (b + blend * (dl + 0.60f * (db - dl))).roundToInt().coerceIn(0, 255)
                    pixels[i] = (alpha shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            edited.setPixels(pixels, 0, src.width, 0, top, src.width, rowCount)
            if (top % 256 == 0) update(87 + (top.toDouble() / src.height * 12).toInt())
        }
        update(100)
        return edited
    }
}
