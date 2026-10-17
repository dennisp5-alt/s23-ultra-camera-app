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
        val pad = (12 * resources.displayMetrics.density).roundToInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF151515.toInt())
            contentDescription = "Photo preview; tap to compare before and after"
            setOnClickListener {
                if (corrected != null) {
                    showOriginal = !showOriginal
                    setImageBitmap(if (showOriginal) original else corrected)
                    info.text = if (showOriginal) "Original — tap photo to compare" else "Corrected — tap photo to compare"
                }
            }
        }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))

        info = TextView(this).apply {
            text = "Choose a photo to start"
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad / 2)
        }
        root.addView(info)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(pad * 2, pad * 2).apply { gravity = Gravity.CENTER })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        openButton = Button(this).apply { text = "Open"; setOnClickListener { imagePicker.launch(arrayOf("image/*")) } }
        autoButton = Button(this).apply { text = "Auto"; setOnClickListener { autoCorrect() } }
        saveButton = Button(this).apply { text = "Save"; setOnClickListener { savePhoto() } }
        for (button in arrayOf(openButton, autoButton, saveButton)) {
            controls.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = pad / 4
                marginEnd = pad / 4
            })
        }
        root.addView(controls)
        setContentView(root)
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

/** Neutral-aware white balance + adaptive tonal correction; no models or libraries. */
object ImageProcessor {
    fun correct(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        val step = max(1, sqrt(w.toDouble() * h / 120_000.0).toInt())

        // Find genuinely near-neutral midtones instead of assuming the entire photo is grey.
        var total = 0
        var neutral = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (y in 0 until h step step) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step step) {
                val p = row[x]
                if ((p ushr 24) < 128) continue
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                total++
                if (hi in 45..225 && (hi - lo) <= hi * 0.17) {
                    sumR += r; sumG += g; sumB += b; neutral++
                }
            }
        }
        // When no reliable neutral reference exists, don't invent a colour cast.
        val reliable = neutral >= max(30, total / 100)
        val rGain = if (reliable) (sumG / sumR.coerceAtLeast(1.0)).coerceIn(0.80, 1.20) else 1.0
        val bGain = if (reliable) (sumG / sumB.coerceAtLeast(1.0)).coerceIn(0.80, 1.20) else 1.0
        val rrGain = 1.0 + (rGain - 1.0) * 0.85
        val bbGain = 1.0 + (bGain - 1.0) * 0.85

        // Estimate luminance distribution AFTER white balancing.
        val hist = IntArray(256)
        var counted = 0
        for (y in 0 until h step step) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step step) {
                val p = row[x]
                if ((p ushr 24) < 128) continue
                val r = (((p shr 16) and 255) * rrGain).coerceIn(0.0, 255.0)
                val g = ((p shr 8) and 255).toDouble()
                val b = ((p and 255) * bbGain).coerceIn(0.0, 255.0)
                val y8 = (0.2126 * r + 0.7152 * g + 0.0722 * b).roundToInt().coerceIn(0, 255)
                hist[y8]++
                counted++
            }
        }
        if (counted == 0) return src.copy(Bitmap.Config.ARGB_8888, true)
        fun percentile(fraction: Double): Int {
            val target = max(1, (counted * fraction).roundToInt())
            var sum = 0
            for (i in hist.indices) {
                sum += hist[i]
                if (sum >= target) return i
            }
            return 255
        }
        val low = percentile(0.01) / 255.0
        val mid = (percentile(0.50) / 255.0).coerceIn(0.04, 0.96)
        val high = percentile(0.99) / 255.0
        val contrast = (0.87 / (high - low).coerceAtLeast(0.15)).coerceIn(0.92, 1.27)
        val gamma = (ln(0.46) / ln(mid)).coerceIn(0.74, 1.28)
        val tone = IntArray(256) { v ->
            val normalized = v / 255.0
            val stretched = ((normalized - mid) * contrast + mid).coerceIn(0.0, 1.0)
            (stretched.pow(gamma) * 255.0).roundToInt().coerceIn(0, 255)
        }

        // Transform in small row batches: avoids allocating two full-size pixel arrays.
        val output = src.copy(Bitmap.Config.ARGB_8888, true)
        val batchRows = min(32, h)
        val pixels = IntArray(w * batchRows)
        for (top in 0 until h step batchRows) {
            val rows = min(batchRows, h - top)
            src.getPixels(pixels, 0, w, 0, top, w, rows)
            for (i in 0 until w * rows) {
                val p = pixels[i]
                val a = p ushr 24
                if (a == 0) continue
                val r = (((p shr 16) and 255) * rrGain).coerceIn(0.0, 255.0)
                val g = ((p shr 8) and 255).toDouble()
                val b = ((p and 255) * bbGain).coerceIn(0.0, 255.0)
                val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
                val yNew = tone[y.roundToInt().coerceIn(0, 255)].toDouble()
                val ratio = (yNew / y.coerceAtLeast(1.0)).coerceIn(0.0, 2.4)
                val r2 = r * ratio
                val g2 = g * ratio
                val b2 = b * ratio
                // Small vibrance boost mainly affects dull colours, not already-saturated ones.
                val saturation = (max(r2, max(g2, b2)) - min(r2, min(g2, b2))) / 255.0
                val vibrance = 1.0 + 0.07 * (1.0 - saturation.coerceIn(0.0, 1.0))
                fun channel(v: Double) = (yNew + (v - yNew) * vibrance).roundToInt().coerceIn(0, 255)
                pixels[i] = (a shl 24) or (channel(r2) shl 16) or (channel(g2) shl 8) or channel(b2)
            }
            output.setPixels(pixels, 0, w, 0, top, w, rows)
        }
        return output
    }
}