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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
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
        // Android 15/16 enforces edge-to-edge for target 35; pad for system bars.
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

        // Controls must always be above the preview: no off-screen buttons.
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
        // Preview occupies remaining height and can shrink on smaller screens.
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
 * Histogram-guided correction for already-rendered sRGB photographs.
 *
 * Conservative white balance, scene-aware exposure, protected shadows and a
 * highlight shoulder. Adjusts luma, not three independent RGB histograms:
 * chroma is preserved as far as the sRGB gamut allows (no false colour).
 * A clipped white JPEG pixel contains no recoverable highlight detail.
 */
object ImageProcessor {
    private fun smoothstep(start: Double, end: Double, value: Double): Double {
        val x = ((value - start) / (end - start)).coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    fun correct(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val row = IntArray(width)
        val step = max(1, sqrt(width.toDouble() * height / 120_000.0).toInt())

        // Select modestly lit, approximately neutral pixels only.
        // Grey-world applied to every pixel causes false casts in sunsets, foliage, etc.
        var sampled = 0
        var neutrals = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (y in 0 until height step step) {
            src.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width step step) {
                val p = row[x]
                if ((p ushr 24) < 128) continue
                sampled++
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                if (hi in 50..225 && (hi - lo) <= max(10.0, hi * 0.12)) {
                    sumR += r
                    sumG += g
                    sumB += b
                    neutrals++
                }
            }
        }

        // Avoid changing intentional warm/cool light without a trustworthy reference.
        val hasReference = neutrals >= max(60, sampled / 40)
        val redGain = if (hasReference) {
            1.0 + ((sumG / sumR.coerceAtLeast(1.0)).coerceIn(0.88, 1.12) - 1.0) * 0.7
        } else 1.0
        val blueGain = if (hasReference) {
            1.0 + ((sumG / sumB.coerceAtLeast(1.0)).coerceIn(0.88, 1.12) - 1.0) * 0.7
        } else 1.0

        // Histogram built from luma AFTER gentle white balance. Do not equalize
        // per-channel histograms: doing that distorts hue, saturation and skin tones.
        val histogram = IntArray(256)
        var count = 0
        for (y in 0 until height step step) {
            src.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width step step) {
                val p = row[x]
                if ((p ushr 24) < 128) continue
                var r = ((p shr 16) and 255) * redGain
                var g = ((p shr 8) and 255).toDouble()
                var b = (p and 255) * blueGain
                // Avoid channel-specific clipping from white balance.
                val maximum = max(r, max(g, b))
                if (maximum > 255.0) {
                    val fit = 255.0 / maximum
                    r *= fit
                    g *= fit
                    b *= fit
                }
                val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b)
                    .roundToInt().coerceIn(0, 255)
                histogram[luma]++
                count++
            }
        }
        if (count == 0) return src.copy(Bitmap.Config.ARGB_8888, true)

        fun percentile(fraction: Double): Double {
            val threshold = max(1, (count * fraction).roundToInt())
            var cumulative = 0
            for (i in histogram.indices) {
                cumulative += histogram[i]
                if (cumulative >= threshold) return i / 255.0
            }
            return 1.0
        }

        val p10 = percentile(0.10)
        val median = percentile(0.50).coerceIn(0.02, 0.98)
        val p90 = percentile(0.90)
        val p98 = percentile(0.98)
        val lowKey = p90 < 0.53 && median < 0.30
        val highKey = p10 > 0.49 && median > 0.64

        // Don't force intentionally dark night photos or bright snow scenes to mid-grey.
        // Only move markedly under/over-exposed medians, and then modestly.
        val medianError = when {
            median < 0.36 -> 0.36 - median
            median > 0.62 -> 0.62 - median
            else -> 0.0
        }
        val compensation = if (lowKey || highKey) 0.25 else 0.58
        val targetMedian = (median + medianError * compensation).coerceIn(0.02, 0.98)
        val gamma = (ln(targetMedian) / ln(median)).coerceIn(0.84, 1.16)

        // Subtle midtone contrast; preserve black point and white point.
        val midtoneContrast = ((0.61 - (p90 - p10)) * 0.16).coerceIn(0.0, 0.055)
        val shadowLift = if (p10 < 0.08 && p90 > 0.62 && !lowKey) 0.025 else 0.0

        // Highlight roll-off redistributes existing bright gradations without
        // flattening all whites. Retain brilliant specular reflections at 255.
        val sceneHighlights = ((p90 - 0.64) * 1.55).coerceIn(0.0, 0.41)
        val highlightStrength = (sceneHighlights +
            if (p98 > 0.96 && p90 > 0.66) 0.045 else 0.0)
            .coerceAtMost(0.45) * (if (highKey) 0.50 else 1.0)

        val curve = DoubleArray(256)
        for (i in 0..255) {
            val x = i / 255.0
            var y = x.pow(gamma)
            // Symmetric midtone contrast: taper adjustment to zero at endpoints.
            y += midtoneContrast * (y - 0.5) * (4.0 * y * (1.0 - y))
            y += shadowLift * smoothstep(0.01, 0.18, y) *
                (1.0 - smoothstep(0.26, 0.52, y))
            y -= highlightStrength * smoothstep(0.57, 0.96, y) * (1.0 - y)
            // Monotonic curve ensures no tone reversals or histogram posterization.
            curve[i] = max(if (i == 0) 0.0 else curve[i - 1], y.coerceIn(0.0, 1.0) * 255.0)
        }
        curve[255] = 255.0

        // Process in bounded batches. Preserve the original RGB colour differences,
        // reducing chroma only when necessary to keep a colour inside sRGB.
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val batchHeight = min(32, height)
        val pixels = IntArray(width * batchHeight)
        for (top in 0 until height step batchHeight) {
            val rows = min(batchHeight, height - top)
            src.getPixels(pixels, 0, width, 0, top, width, rows)
            for (i in 0 until width * rows) {
                val p = pixels[i]
                val alpha = p ushr 24
                if (alpha == 0) continue
                var r = ((p shr 16) and 255) * redGain
                var g = ((p shr 8) and 255).toDouble()
                var b = (p and 255) * blueGain
                val brightest = max(r, max(g, b))
                if (brightest > 255.0) {
                    val fit = 255.0 / brightest
                    r *= fit
                    g *= fit
                    b *= fit
                }
                val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b).coerceIn(0.0, 255.0)
                val index = luma.toInt().coerceIn(0, 255)
                val next = min(index + 1, 255)
                val desired = curve[index] + (curve[next] - curve[index]) * (luma - index)

                val brightestChannel = max(r, max(g, b))
                val darkestChannel = min(r, min(g, b))
                val highDistance = brightestChannel - luma
                val lowDistance = luma - darkestChannel
                var chroma = 1.0
                if (highDistance > 0.001) {
                    chroma = min(chroma, ((255.0 - desired) / highDistance).coerceAtLeast(0.0))
                }
                if (lowDistance > 0.001) {
                    chroma = min(chroma, (desired / lowDistance).coerceAtLeast(0.0))
                }
                val rr = (desired + (r - luma) * chroma).roundToInt().coerceIn(0, 255)
                val gg = (desired + (g - luma) * chroma).roundToInt().coerceIn(0, 255)
                val bb = (desired + (b - luma) * chroma).roundToInt().coerceIn(0, 255)
                pixels[i] = (alpha shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            result.setPixels(pixels, 0, width, 0, top, width, rows)
        }
        return result
    }
}
