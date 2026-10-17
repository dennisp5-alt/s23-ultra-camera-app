package com.example.autophoto

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Small, offline, one-tap photographic correction experiment. Android 10+. */
class MainActivity : ComponentActivity() {
    private var original: Bitmap? = null
    private var corrected: Bitmap? = null
    private var showOriginal = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var image: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var info: TextView
    private lateinit var openButton: Button
    private lateinit var autoButton: Button
    private lateinit var saveButton: Button

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        scope.launch {
            setBusy(true)
            try {
                val bitmap = withContext(Dispatchers.IO) { decodePhoto(uri) }
                image.setImageBitmap(null)
                original = bitmap
                corrected = null
                showOriginal = false
                image.setImageBitmap(bitmap)
                info.text = "${bitmap.width} × ${bitmap.height}  •  Tap Auto to correct"
            } catch (e: Exception) {
                errorMessage("Cannot open image", e)
            } finally {
                setBusy(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val pad = (12 * resources.displayMetrics.density).roundToInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(pad, pad, pad, pad)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                pad + safe.left,
                pad + safe.top,
                pad + safe.right,
                pad + safe.bottom
            )
            insets
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        openButton = Button(this).apply {
            text = "Open"
            isAllCaps = false
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        }
        autoButton = Button(this).apply {
            text = "Auto"
            isAllCaps = false
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { autoCorrect() }
        }
        saveButton = Button(this).apply {
            text = "Save"
            isAllCaps = false
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { savePhoto() }
        }
        for (button in arrayOf(openButton, autoButton, saveButton)) {
            controls.addView(button, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginStart = pad / 4
                marginEnd = pad / 4
            })
        }
        root.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        info = TextView(this).apply {
            text = "Choose a photo to start"
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(
            pad * 2, pad * 2
        ).apply { gravity = Gravity.CENTER })

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF151515.toInt())
            contentDescription = "Photo preview; tap to compare before and after"
            setOnClickListener {
                if (corrected != null) {
                    showOriginal = !showOriginal
                    setImageBitmap(if (showOriginal) original else corrected)
                    info.text = if (showOriginal) "Original — tap photo to compare"
                    else "Corrected — tap photo to compare"
                }
            }
        }
        root.addView(image, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        setBusy(false)
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        openButton.isEnabled = !busy
        autoButton.isEnabled = !busy && original != null
        saveButton.isEnabled = !busy && corrected != null
    }

    /** Decode on a worker thread, with a bounded 4096px maximum edge to avoid OOM. */
    private fun decodePhoto(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, imageInfo, _ ->
            val width = imageInfo.size.width
            val height = imageInfo.size.height
            val scale = min(1.0, 4096.0 / max(width, height))
            if (scale < 1) decoder.setTargetSize(
                max(1, (width * scale).roundToInt()),
                max(1, (height * scale).roundToInt())
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    }

    private fun autoCorrect() {
        val input = original ?: return
        scope.launch {
            setBusy(true)
            try {
                val output = withContext(Dispatchers.Default) { ImageProcessor.correct(input) }
                corrected = output
                showOriginal = false
                image.setImageBitmap(output)
                info.text = "Corrected — tap photo to compare"
            } catch (e: Exception) {
                errorMessage("Auto correction failed", e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun savePhoto() {
        val output = corrected ?: return
        scope.launch {
            setBusy(true)
            try {
                val saved = withContext(Dispatchers.IO) { writeJpeg(output) }
                info.text = "Saved to Pictures/AutoPhoto"
                Toast.makeText(this@MainActivity, "Saved: $saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                errorMessage("Save failed", e)
            } finally {
                setBusy(false)
            }
        }
    }

    /** A half-written photo is deleted if the save fails. Android 10+ scoped storage. */
    private fun writeJpeg(bitmap: Bitmap): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "autophoto_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AutoPhoto")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused to create file")
        try {
            contentResolver.openOutputStream(uri)?.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) { "JPEG encoding failed" }
            } ?: error("Could not open destination")
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun errorMessage(message: String, e: Exception) {
        info.text = message
        Toast.makeText(this, "$message: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * Stage 3 experiment: deliberately push the luminance histogram toward a more
 * "finished photo" shape and push colour toward a more corrected look.
 *
 * This is intentionally stronger and less scene-aware than the previous build.
 * It will not look right on every photo, but it is useful for testing how far
 * a small offline pipeline can be pushed.
 */
object ImageProcessor {
    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        if (edge1 <= edge0) return if (x >= edge1) 1.0 else 0.0
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun interpolateAnchors(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        for (i in 0 until xs.lastIndex) {
            val x0 = xs[i]
            val x1 = xs[i + 1]
            if (x <= x1) {
                val t = ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
                val s = t * t * (3.0 - 2.0 * t)
                return ys[i] + (ys[i + 1] - ys[i]) * s
            }
        }
        return ys.last()
    }

    fun correct(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val sampleRow = IntArray(width)
        val step = max(1, sqrt(width.toDouble() * height / 110_000.0).toInt())

        // More assertive neutral-balance sampling.
        var sampled = 0
        var neutrals = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (y in 0 until height step step) {
            src.getPixels(sampleRow, 0, width, 0, y, width, 1)
            for (x in 0 until width step step) {
                val p = sampleRow[x]
                if ((p ushr 24) < 128) continue
                sampled++
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                if (hi in 38..235 && (hi - lo) <= max(12.0, hi * 0.18)) {
                    neutrals++
                    sumR += r
                    sumG += g
                    sumB += b
                }
            }
        }
        val hasReference = neutrals >= max(40, sampled / 60)
        val rawRedGain = if (hasReference) (sumG / sumR.coerceAtLeast(1.0)) else 1.0
        val rawBlueGain = if (hasReference) (sumG / sumB.coerceAtLeast(1.0)) else 1.0
        val redGain = rawRedGain.coerceIn(0.78, 1.22)
        val blueGain = rawBlueGain.coerceIn(0.78, 1.22)

        // Histogram on white-balanced luminance only.
        val histogram = IntArray(256)
        var count = 0
        for (y in 0 until height step step) {
            src.getPixels(sampleRow, 0, width, 0, y, width, 1)
            for (x in 0 until width step step) {
                val p = sampleRow[x]
                if ((p ushr 24) < 128) continue
                var r = ((p shr 16) and 255) * redGain
                var g = ((p shr 8) and 255).toDouble()
                var b = (p and 255) * blueGain
                val hi = max(r, max(g, b))
                if (hi > 255.0) {
                    val scale = 255.0 / hi
                    r *= scale
                    g *= scale
                    b *= scale
                }
                val yLin = (0.2126 * r + 0.7152 * g + 0.0722 * b).roundToInt().coerceIn(0, 255)
                histogram[yLin]++
                count++
            }
        }
        if (count == 0) return src.copy(Bitmap.Config.ARGB_8888, true)

        fun percentile(fraction: Double): Double {
            val target = max(1, (count * fraction).roundToInt())
            var cumulative = 0
            for (i in histogram.indices) {
                cumulative += histogram[i]
                if (cumulative >= target) return i / 255.0
            }
            return 1.0
        }

        val p01 = percentile(0.01)
        val p05 = percentile(0.05)
        val p25 = percentile(0.25)
        val p50 = percentile(0.50)
        val p75 = percentile(0.75)
        val p95 = percentile(0.95)
        val p99 = percentile(0.99)

        // Push toward a classic "good finished photo" tonal spread.
        val xs = doubleArrayOf(
            0.0,
            p01,
            max(p01 + 0.01, p05),
            max(p05 + 0.02, p25),
            max(p25 + 0.02, p50),
            max(p50 + 0.02, p75),
            max(p75 + 0.02, p95),
            max(p95 + 0.01, p99),
            1.0
        )
        val ys = doubleArrayOf(
            0.0,
            0.015,
            0.065,
            0.22,
            0.47,
            0.74,
            0.935,
            0.985,
            1.0
        )

        val toneCurve = DoubleArray(256)
        for (i in 0..255) {
            val x = i / 255.0
            var y = interpolateAnchors(x, xs, ys)

            // Extra S-curve polish for midtone punch.
            val midWeight = smoothstep(0.10, 0.42, x) * (1.0 - smoothstep(0.60, 0.92, x))
            y += (x - 0.5) * 0.08 * midWeight

            // Stronger highlight shoulder.
            y -= 0.09 * smoothstep(0.70, 0.98, x) * (1.0 - x)

            // Keep shadows slightly lifted instead of crushed.
            y += 0.02 * smoothstep(0.0, 0.12, x) * (1.0 - smoothstep(0.20, 0.40, x))

            val mapped = (y * 255.0).coerceIn(0.0, 255.0)
            toneCurve[i] = if (i == 0) mapped else max(mapped, toneCurve[i - 1])
        }
        toneCurve[255] = 255.0

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val batchRows = min(32, height)
        val pixels = IntArray(width * batchRows)

        for (top in 0 until height step batchRows) {
            val rows = min(batchRows, height - top)
            src.getPixels(pixels, 0, width, 0, top, width, rows)
            for (i in 0 until width * rows) {
                val p = pixels[i]
                val a = p ushr 24
                if (a == 0) continue

                var r = ((p shr 16) and 255) * redGain
                var g = ((p shr 8) and 255).toDouble()
                var b = (p and 255) * blueGain

                val initialMax = max(r, max(g, b))
                if (initialMax > 255.0) {
                    val scale = 255.0 / initialMax
                    r *= scale
                    g *= scale
                    b *= scale
                }

                val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b).coerceIn(0.0, 255.0)
                val base = luma.toInt().coerceIn(0, 255)
                val next = min(255, base + 1)
                val mappedLuma = toneCurve[base] + (toneCurve[next] - toneCurve[base]) * (luma - base)

                // Preserve hue/chroma, but push saturation toward a more corrected, finished-photo look.
                val high = max(r, max(g, b))
                val low = min(r, min(g, b))
                val highDistance = high - luma
                val lowDistance = luma - low
                var chromaScale = 1.0
                if (highDistance > 0.001) {
                    chromaScale = min(chromaScale, ((255.0 - mappedLuma) / highDistance).coerceAtLeast(0.0))
                }
                if (lowDistance > 0.001) {
                    chromaScale = min(chromaScale, (mappedLuma / lowDistance).coerceAtLeast(0.0))
                }

                val currentSaturation = ((high - low) / 255.0).coerceIn(0.0, 1.0)
                val saturationBoost = when {
                    currentSaturation < 0.15 -> 1.30
                    currentSaturation < 0.35 -> 1.18
                    currentSaturation < 0.60 -> 1.10
                    else -> 1.02
                }
                chromaScale *= saturationBoost

                val rr = (mappedLuma + (r - luma) * chromaScale).roundToInt().coerceIn(0, 255)
                val gg = (mappedLuma + (g - luma) * chromaScale).roundToInt().coerceIn(0, 255)
                val bb = (mappedLuma + (b - luma) * chromaScale).roundToInt().coerceIn(0, 255)
                pixels[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.setPixels(pixels, 0, width, 0, top, width, rows)
        }
        return out
    }
}