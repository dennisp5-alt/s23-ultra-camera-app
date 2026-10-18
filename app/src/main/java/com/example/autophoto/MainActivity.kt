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
import androidx.activity.result.PickVisualMediaRequest
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
    // Dedicated persistent result panel, never overwritten by compare/save status.
    private lateinit var sceneDiagnostics: TextView
    private lateinit var openButton: Button
    private lateinit var autoButton: Button
    private lateinit var aiButton: Button
    private lateinit var lutButton: Button
    private lateinit var maskedButton: Button
    private lateinit var semanticButton: Button
    private lateinit var sceneButton: Button
    private lateinit var detailButton: Button
    private lateinit var detailGentleButton: Button
    private lateinit var detailBalancedButton: Button
    private lateinit var detailStrongButton: Button
    private lateinit var polishedButton: Button
    private lateinit var polishNormalButton: Button
    private lateinit var polishPlusButton: Button
    private lateinit var polishWowButton: Button
    private var polishedStyle = PolishedAIProcessor.Style.POLISHED_PLUS
    private var polishedProgressActive = false
    private lateinit var detailAdaptiveButton: Button
    // User-preferred Strong is the new default; original Gentle/Balanced/
    // Strong run unchanged for an A/B regression comparison.
    private var detailStrength = LocalDetailProcessor.Strength.STRONG
    private var detailProgressActive = false
    private lateinit var saveButton: Button

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        scope.launch {
            setBusy(true)
            sceneDiagnostics.visibility = View.GONE
            sceneDiagnostics.text = ""
            info.text = "Loading photo…"
            try {
                val bitmap = withContext(Dispatchers.IO) { decodePhoto(uri) }
                image.setImageBitmap(null)
                original = bitmap
                corrected = null
                showOriginal = false
                image.setImageBitmap(bitmap)
                info.text = "${bitmap.width} × ${bitmap.height} • Select Polished, Polished+ or Wow"
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
            setOnClickListener { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        autoButton = Button(this).apply {
            text = "Auto"
            isAllCaps = false
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { autoCorrect() }
        }
        aiButton = Button(this).apply {
            text = "MobileIE AI"
            isAllCaps = false
            textSize = 12f
            setPadding(0, 0, 0, 0)
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { runAlternative(true) }
        }
        lutButton = Button(this).apply {
            text = "Learned 3D LUT"
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { runAlternative(false) }
        }
        saveButton = Button(this).apply {
            text = "Save"
            isAllCaps = false
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { savePhoto() }
        }
        // Polished-only v0.4.3.1 UI: keep Open/Save, hide the old Auto
        // button while retaining its original processor untouched in source.
        for (button in arrayOf(openButton, saveButton)) {
            button.minWidth = 0
            button.setPadding(0, 0, 0, 0)
            button.textSize = 12f
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

        // Two model buttons in a second row, no clipped text on small screens.
        val aiControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (button in arrayOf(aiButton, lutButton)) {
            button.minWidth = 0
            button.setPadding(0, 0, 0, 0)
            aiControls.addView(button, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginStart = pad / 4
                marginEnd = pad / 4
            })
        }
        // Historical mode row intentionally not attached to the screen.
        // Its trained models and original actions remain preserved in source.

        // Third independent mode: combines real outputs of both trained
        // networks through multiple edge-aware regional masking passes.
        maskedButton = Button(this).apply {
            text = "Adaptive Masked AI"
            isAllCaps = false
            textSize = 13f
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { runMaskedAI() }
        }
        // Original Adaptive Masked AI remains compiled but hidden.

        semanticButton = Button(this).apply {
            text = "Semantic Person AI (v0.3.7)"
            isAllCaps = false
            textSize = 13f
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { runSemanticAI() }
        }
        // Original person segmentation remains compiled but hidden.
        sceneButton = Button(this).apply {
            text = "Scene Semantic AI (v0.3.9)"
            isAllCaps = false
            textSize = 13f
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { runSceneAI() }
        }
        // Original scene segmentation remains compiled but hidden.
        detailButton = Button(this).apply {
            text = "Run Local Detail (v0.4.1 presets)"
            isAllCaps = false
            textSize = 13f
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { runLocalDetailLab() }
        }
        // v0.4.1 local-detail button remains compiled but hidden.
        // Compact A/B strength controls, separate from the correction action.
        // Changing the preset never reuses a previously edited output.
        val strengthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addStrengthButton(
            label: String, preset: LocalDetailProcessor.Strength
        ): Button {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                minHeight = (40 * resources.displayMetrics.density).roundToInt()
                setOnClickListener {
                    detailStrength = preset
                    refreshDetailStrengthButtons()
                }
            }
            strengthRow.addView(button, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            return button
        }
        detailGentleButton = addStrengthButton(
            "Gentle", LocalDetailProcessor.Strength.GENTLE)
        detailBalancedButton = addStrengthButton(
            "Balanced", LocalDetailProcessor.Strength.BALANCED)
        detailStrongButton = addStrengthButton(
            "Strong", LocalDetailProcessor.Strength.STRONG)
        // Original detail strength row remains available in source only.
        refreshDetailStrengthButtons()
        // New algorithm is independent of the original three v0.4.1 presets.
        // It uses Strong gain locally, with additional soft tone/skin/sky
        // boundary protection and a no-new-extrema safeguard.
        detailAdaptiveButton = Button(this).apply {
            text = "Adaptive Strong Detail (v0.4.2)"
            isAllCaps = false
            textSize = 13f
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { runLocalDetailLab(adaptive = true) }
        }
        // v0.4.2 Adaptive Strong remains compiled but hidden.

        // Three A/B finishing looks; each starts from the untouched original
        // and runs the same genuine neural learned LUT before style finishing.
        polishedButton = Button(this).apply {
            text = "Run Polished AI"
            isAllCaps = false
            textSize = 13f
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { runPolishedAI() }
        }
        root.addView(polishedButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = pad / 4
            marginEnd = pad / 4
        })
        val polishedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addPolishedChoice(label: String, preset: PolishedAIProcessor.Style): Button {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                minHeight = (40 * resources.displayMetrics.density).roundToInt()
                setOnClickListener {
                    polishedStyle = preset
                    refreshPolishedButtons()
                }
            }
            polishedRow.addView(button, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return button
        }
        polishNormalButton = addPolishedChoice(
            "Polished", PolishedAIProcessor.Style.POLISHED)
        polishPlusButton = addPolishedChoice(
            "Polished+", PolishedAIProcessor.Style.POLISHED_PLUS)
        polishWowButton = addPolishedChoice(
            "Wow", PolishedAIProcessor.Style.WOW)
        root.addView(polishedRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        refreshPolishedButtons()
        info = TextView(this).apply {
            text = "Open a photo, choose a look, then run Polished AI"
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // A dedicated result panel survives preview taps and saves.
        sceneDiagnostics = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(pad / 2, pad / 3, pad / 2, pad / 2)
            setTextIsSelectable(true)
            contentDescription = "Polished AI processing results"
        }
        root.addView(sceneDiagnostics, LinearLayout.LayoutParams(
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

    private fun refreshDetailStrengthButtons() {
        fun mark(button: Button, selected: Boolean, label: String) {
            button.text = if (selected) "✓ " + label else label
            button.alpha = if (selected) 1f else 0.62f
            button.isSelected = selected
        }
        mark(detailGentleButton,
            detailStrength == LocalDetailProcessor.Strength.GENTLE, "Gentle")
        mark(detailBalancedButton,
            detailStrength == LocalDetailProcessor.Strength.BALANCED, "Balanced")
        mark(detailStrongButton,
            detailStrength == LocalDetailProcessor.Strength.STRONG, "Strong")
    }

    private fun refreshPolishedButtons() {
        fun indicate(button: Button, selected: Boolean, label: String) {
            button.text = if (selected) "✓ " + label else label
            button.alpha = if (selected) 1f else 0.65f
            button.isSelected = selected
        }
        indicate(polishNormalButton,
            polishedStyle == PolishedAIProcessor.Style.POLISHED, "Polished")
        indicate(polishPlusButton,
            polishedStyle == PolishedAIProcessor.Style.POLISHED_PLUS, "Polished+")
        indicate(polishWowButton,
            polishedStyle == PolishedAIProcessor.Style.WOW, "Wow")
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        openButton.isEnabled = !busy
        autoButton.isEnabled = !busy && original != null
        aiButton.isEnabled = !busy && original != null
        lutButton.isEnabled = !busy && original != null
        maskedButton.isEnabled = !busy && original != null
        semanticButton.isEnabled = !busy && original != null
        sceneButton.isEnabled = !busy && original != null
        detailButton.isEnabled = !busy && original != null
        detailGentleButton.isEnabled = !busy && original != null
        detailBalancedButton.isEnabled = !busy && original != null
        detailStrongButton.isEnabled = !busy && original != null
        detailAdaptiveButton.isEnabled = !busy && original != null
        polishedButton.isEnabled = !busy && original != null
        polishNormalButton.isEnabled = !busy && original != null
        polishPlusButton.isEnabled = !busy && original != null
        polishWowButton.isEnabled = !busy && original != null
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
            sceneDiagnostics.visibility = View.GONE
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

    /**
     * A completely independent neural processing path.
     * The existing ImageProcessor.correct (Auto) is never called here.
     */
    /** Two distinct trained networks; the existing Auto method is unchanged. */
    private fun runAlternative(mobile: Boolean) {
        val source = original ?: return
        val mode = if (mobile) "MobileIE" else "Learned 3D LUT"
        scope.launch {
            setBusy(true)
            sceneDiagnostics.visibility = View.GONE
            info.text = mode + " • loading offline neural weights…"
            try {
                val result = withContext(Dispatchers.Default) {
                    val update: (Int) -> Unit = { percent ->
                        runOnUiThread {
                            info.text = mode + " • processing " + percent + "%"
                        }
                    }
                    if (mobile) AlternativeAIProcessor.mobileIE(this@MainActivity, source, update)
                    else AlternativeAIProcessor.adaptiveLut(this@MainActivity, source, update)
                }
                corrected = result.bitmap
                showOriginal = false
                image.setImageBitmap(result.bitmap)
                val total = result.totalMillis / 1000.0
                val neural = result.inferenceMillis / 1000.0
                info.text = mode + " • " + "%.1f".format(total) + "s total / " +
                    "%.1f".format(neural) + "s neural • tap to compare"
            } catch (e: Exception) {
                errorMessage(mode + " failed", e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runMaskedAI() {
        val source = original ?: return
        scope.launch {
            setBusy(true)
            sceneDiagnostics.visibility = View.GONE
            info.text = "Adaptive Masked AI • analysing regions…"
            try {
                val output = withContext(Dispatchers.Default) {
                    MaskedAIProcessor.enhance(this@MainActivity, source) { percent ->
                        runOnUiThread {
                            info.text = "Adaptive Masked AI • processing " + percent + "%"
                        }
                    }
                }
                corrected = output.bitmap
                showOriginal = false
                image.setImageBitmap(output.bitmap)
                val total = output.totalMillis / 1000.0
                val mobileMs = output.mobileInferenceMs / 1000.0
                val lutMs = output.lutInferenceMs / 1000.0
                info.text = "Adaptive Masked AI • " + "%.1f".format(total) + "s total" +
                    " (MobileIE " + "%.1f".format(mobileMs) +
                    "s / LUT " + "%.1f".format(lutMs) + "s)" +
                    " • tap photo to compare"
            } catch (e: Exception) {
                errorMessage("Masked AI failed", e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runSemanticAI() {
        val source = original ?: return
        scope.launch {
            setBusy(true)
            sceneDiagnostics.visibility = View.GONE
            info.text = "Semantic AI • offline person segmentation…"
            try {
                val output = withContext(Dispatchers.Default) {
                    MaskedAIProcessor.enhanceSemantic(
                        this@MainActivity, source) { percent ->
                        runOnUiThread {
                            info.text = "Semantic AI • processing " + percent + "%"
                        }
                    }
                }
                corrected = output.bitmap
                showOriginal = false
                image.setImageBitmap(output.bitmap)
                val total = output.totalMillis / 1000.0
                val seg = output.personInferenceMs / 1000.0
                val status = if (output.personMaskActive) {
                    "Person mask active (" +
                        "%.1f".format(output.personMaskCoverage * 100f) + "%)"
                } else output.personStatus
                info.text = "Semantic AI • " + "%.1f".format(total) +
                    "s • " + status + " • segmentation " +
                    "%.1f".format(seg) + "s • tap photo to compare"
            } catch (e: Exception) {
                errorMessage("Semantic AI failed", e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runSceneAI() {
        val source = original ?: return
        scope.launch {
            setBusy(true)
            sceneDiagnostics.visibility = View.VISIBLE
            sceneDiagnostics.text = "Scene model: starting offline inference…"
            info.text = "Scene AI • analysing sky, water and people offline…"
            try {
                val result = withContext(Dispatchers.Default) {
                    MaskedAIProcessor.enhanceScene(
                        this@MainActivity, source) { percent ->
                        runOnUiThread {
                            info.text = "Scene AI • processing " + percent + "%"
                        }
                    }
                }
                corrected = result.bitmap
                showOriginal = false
                image.setImageBitmap(result.bitmap)
                val total = result.totalMillis / 1000.0
                val seg = result.sceneSegmentationMs / 1000.0
                // Keep true model status visible after photo comparison & Save.
                val sceneSucceeded = result.sceneStatus ==
                    "Trained sky/water/person masks active"
                val report = if (sceneSucceeded) {
                    "Scene segmentation: ACTIVE" + "\n" +
                    "Sky " + "%.1f".format(result.skyCoverage * 100f) + "%  •  " +
                    "Water " + "%.1f".format(result.waterCoverage * 100f) + "%  •  " +
                    "People " + "%.1f".format(result.personMaskCoverage * 100f) + "%" +
                    "\n" + "Scene inference " + "%.1f".format(seg) + "s" +
                    "  •  Total " + "%.1f".format(total) + "s"
                } else {
                    "Scene segmentation: FALLBACK" + "\n" +
                    result.sceneStatus + "\n" +
                    "Scene inference " + "%.1f".format(seg) + "s" +
                    "  •  Total " + "%.1f".format(total) + "s"
                }
                sceneDiagnostics.text = report
                sceneDiagnostics.visibility = View.VISIBLE
                info.text = "Scene AI complete — tap photo to compare"
            } catch (e: Exception) {
                sceneDiagnostics.text = "Scene processing error: " +
                    (e.localizedMessage ?: e.javaClass.simpleName)
                sceneDiagnostics.visibility = View.VISIBLE
                errorMessage("Scene AI failed", e)
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * Experimental local luminance contrast/detail path. Runs independent of
     * original Auto, MobileIE, LUT, Masked AI, ML Kit and scene segmentation.
     */
    private fun runLocalDetailLab(adaptive: Boolean = false) {
        val source = original ?: return
        val preset = if (adaptive)
            LocalDetailProcessor.Strength.STRONG else detailStrength
        val label = if (adaptive) "Adaptive Strong" else preset.label
        scope.launch {
            setBusy(true)
            detailProgressActive = true
            sceneDiagnostics.visibility = View.VISIBLE
            sceneDiagnostics.text = "Quality Lab " + label +
                " • analysing original image texture…"
            info.text = "Local Detail Lab " + label +
                " • preparing edge-aware enhancement…"
            try {
                val result = withContext(Dispatchers.Default) {
                    if (adaptive) {
                        LocalDetailProcessor.enhanceAdaptiveStrong(source) { percent ->
                            runOnUiThread {
                                if (detailProgressActive) {
                                    info.text = "Adaptive Strong • processing " + percent + "%"
                                }
                            }
                        }
                    } else LocalDetailProcessor.enhance(source, preset) { percent ->
                        // Callbacks can arrive after the coroutine completes.
                        // Suppress stale 100% callbacks overwriting "complete".
                        runOnUiThread {
                            if (detailProgressActive) {
                                info.text = "Local Detail Lab " + preset.label +
                                    " • processing " + percent + "%"
                            }
                        }
                    }
                }
                detailProgressActive = false
                corrected = result.bitmap
                showOriginal = false
                image.setImageBitmap(result.bitmap)
                val sec = result.elapsedMs / 1000.0
                sceneDiagnostics.text = (if (result.adaptiveStrong)
                    "Adaptive Strong Detail v0.4.2" else
                    "Original Local Detail v0.4.1") +
                    " • " + result.strength.label + "\n" +
                    "Duration: " + "%.1f".format(sec) + "s" +
                    " • Pixels changed: " +
                    "%.1f".format(result.changedPercent) + "%" +
                    "\n" + "Average absolute luminance change: " +
                    "%.2f".format(result.meanAbsLumaChange) + "/255" +
                    " • Max: " +
                    "%.1f".format(result.maximumAbsoluteYShift) + "/255" +
                    "\n" + "Same decoded source brightness: " +
                    "%.2f".format(result.inputMeanY) + " → " +
                    "%.2f".format(result.outputMeanY) +
                    " • Texture mask: " +
                    "%.1f".format(result.maskPercent) + "%" +
                    (if (result.adaptiveStrong) "\nBoundary protection: " +
                        "%.1f".format(result.boundaryLimitedPercent) +
                        "% of pixels limited" else "")
                sceneDiagnostics.visibility = View.VISIBLE
                info.text = "Local Detail Lab " + label +
                    " complete — tap photo to compare"
            } catch (e: Exception) {
                detailProgressActive = false
                sceneDiagnostics.text = "Local Detail Lab failed: " +
                    (e.localizedMessage ?: e.javaClass.simpleName)
                sceneDiagnostics.visibility = View.VISIBLE
                errorMessage("Local Detail Lab failed", e)
            } finally {
                detailProgressActive = false
                setBusy(false)
            }
        }
    }

    /**
     * Separate v0.4.3 actual-neural style path: pretrained learned 3D LUT
     * plus graded polished/wow finish. Does not call or change old modes.
     */
    private fun runPolishedAI() {
        val source = original ?: return
        val preset = polishedStyle
        scope.launch {
            setBusy(true)
            polishedProgressActive = true
            sceneDiagnostics.visibility = View.VISIBLE
            sceneDiagnostics.text = "Polished AI " + preset.label +
                " • applying learned offline neural colour model…"
            info.text = "Polished AI " + preset.label + " • starting…"
            try {
                val result = withContext(Dispatchers.Default) {
                    PolishedAIProcessor.enhance(
                        this@MainActivity, source, preset) { pct ->
                        runOnUiThread {
                            if (polishedProgressActive) {
                                info.text = "Polished AI " + preset.label +
                                    " • processing " + pct + "%"
                            }
                        }
                    }
                }
                polishedProgressActive = false
                corrected = result.bitmap
                showOriginal = false
                image.setImageBitmap(result.bitmap)
                sceneDiagnostics.text = "Polished AI Lab v0.4.3" +
                    " • " + result.style.label + "\n" +
                    "Total: " + "%.1f".format(result.totalMs/1000.0) +
                    "s • Neural inference: " +
                    "%.1f".format(result.neuralMs/1000.0) + "s" +
                    "\nAdjusted pixels: " +
                    "%.1f".format(result.changedPercent) + "%" +
                    " • Original / result luminance: " +
                    "%.1f".format(result.beforeMeanY) + " → " +
                    "%.1f".format(result.afterMeanY) +
                    "\nClipping-risk pixels: " +
                    "%.1f".format(result.clippedChannelPercent) + "%" +
                    (if (result.darkSceneProtected)
                        " • Night/black-background protection active" else "")
                sceneDiagnostics.visibility = View.VISIBLE
                info.text = "Polished AI " + preset.label +
                    " complete — tap photo to compare"
            } catch (e: Exception) {
                polishedProgressActive = false
                sceneDiagnostics.text = "Polished AI processing failed: " +
                    (e.localizedMessage ?: e.javaClass.simpleName)
                sceneDiagnostics.visibility = View.VISIBLE
                errorMessage("Polished AI failed", e)
            } finally {
                polishedProgressActive = false
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
 * v0.2.2: enhanced smartphone JPEG / HEIC correction.
 *
 * The Galaxy S23 Ultra and similar phones have already performed HDR tone mapping,
 * colour rendering, denoising and sharpening. Retain that work; do not equalise
 * RGB channels, add sharpening, or force every picture into a fixed histogram.
 * SDR sRGB conversion is handled at decode.
 */
object ImageProcessor {
    private fun ease(a: Double, b: Double, value: Double): Double {
        val t = ((value - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    fun correct(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        val step = max(1, sqrt(w.toDouble() * h / 125_000.0).toInt())

        // Search for reliable, *unsaturated* neutral reference patches, rather
        // than assuming a scene dominated by a sky, grass or sunset should be grey.
        var sampled = 0
        var neutralCount = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (y in 0 until h step step) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step step) {
                val p = row[x]
                if ((p ushr 24) < 128) continue
                sampled++
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                if (luma in 55.0..207.0 && hi - lo <= max(7.0, luma * 0.085)) {
                    neutralCount++
                    sumR += r
                    sumG += g
                    sumB += b
                }
            }
        }

        val confidence = (neutralCount.toDouble() / sampled.coerceAtLeast(1) / 0.045)
            .coerceIn(0.0, 1.0)
        val reliable = neutralCount >= 120 && confidence >= 0.24
        val strength = if (reliable) 0.78 * confidence else 0.0
        val redGain = if (reliable) {
            1.0 + ((sumG / sumR.coerceAtLeast(1.0)).coerceIn(0.86, 1.14) - 1.0) * strength
        } else 1.0
        val blueGain = if (reliable) {
            1.0 + ((sumG / sumB.coerceAtLeast(1.0)).coerceIn(0.86, 1.14) - 1.0) * strength
        } else 1.0

        // Luma histogram, not per-channel stretching. Count clipped pixels so
        // existing processed HDR highlights receive even more restrained editing.
        val hist = IntArray(256)
        var valid = 0
        var clipped = 0
        var nearBlack = 0
        for (y in 0 until h step step) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step step) {
                val p = row[x]
                if ((p ushr 24) < 128) continue
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b)
                    .roundToInt().coerceIn(0, 255)
                hist[lum]++
                valid++
                if (lum >= 249 || (r >= 254 && g >= 254 && b >= 254)) clipped++
                if (lum <= 15) nearBlack++
            }
        }
        if (valid == 0) return src.copy(Bitmap.Config.ARGB_8888, true)

        fun percentile(f: Double): Double {
            val target = max(1, (valid * f).roundToInt())
            var n = 0
            for (i in 0..255) {
                n += hist[i]
                if (n >= target) return i / 255.0
            }
            return 1.0
        }

        val p05 = percentile(0.05)
        val p10 = percentile(0.10)
        val p50 = percentile(0.50).coerceIn(0.02, 0.98)
        val p90 = percentile(0.90)
        val p98 = percentile(0.98)
        val range = p90 - p10
        val darkScene = p90 < 0.49 && p50 < 0.27
        val brightScene = p10 > 0.52 && p50 > 0.69
        val clippedFraction = clipped.toDouble() / valid
        val blackFraction = nearBlack.toDouble() / valid
        // Preserve low-light mood, snow and deliberately high-key compositions.
        val keyProtection = if (darkScene || brightScene) 0.25 else 1.0
        val error = when {
            p50 < 0.39 -> 0.39 - p50
            p50 > 0.64 -> 0.64 - p50
            else -> 0.0
        }
        val targetMid = (p50 + 0.66 * keyProtection * error).coerceIn(0.02, 0.98)
        val gamma = (ln(targetMid) / ln(p50)).coerceIn(0.82, 1.18)

        // More visible but still protected midtone contrast for rendered HDR.
        // Avoid forcing already-dark night scenes into daytime brightness.
        val contrast = when {
            range < 0.50 && !darkScene && !brightScene -> (0.50 - range) * 0.22
            range > 0.78 -> -(range - 0.78) * 0.18
            else -> 0.0
        }.coerceIn(-0.025, 0.065)
        val shadowLift = if (!darkScene && blackFraction < 0.18 &&
            p05 < 0.095 && p90 > 0.60) 0.024 else 0.0

        // More pronounced but monotonic shoulder for rendered highlights.
        // Do NOT falsely claim
        // to reconstruct white pixels that the camera has clipped.
        val highlightActivity = ease(0.60, 0.89, p90)
        val rollOff = (0.15 + 0.22 * highlightActivity).coerceAtMost(0.36) *
            (if (brightScene) 0.55 else 1.0) *
            (if (clippedFraction > 0.045) 0.60 else 1.0)

        val tone = DoubleArray(256)
        for (i in 0..255) {
            val x = i / 255.0
            var y = x.pow(gamma)
            y += contrast * (y - 0.5) * 4.0 * y * (1.0 - y)
            y += shadowLift * ease(0.015, 0.12, y) *
                (1.0 - ease(0.20, 0.42, y))
            // Smooth roll-off, tapered back to unchanged diffuse/specular white.
            y -= rollOff * ease(0.52, 0.85, y) * (1.0 - y)
            val adjusted = y.coerceIn(0.0, 1.0) * 255.0
            // Enforce monotonicity: no tonal reversals in high gradients.
            tone[i] = if (i == 0) adjusted else max(adjusted, tone[i - 1])
        }
        tone[255] = 255.0

        // Reuse row batches instead of allocating arrays for an entire 200 MP
        // image. The decode stage caps output dimensions at 4096 px.
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rowsPerBatch = min(32, h)
        val pixels = IntArray(w * rowsPerBatch)
        for (top in 0 until h step rowsPerBatch) {
            val rows = min(rowsPerBatch, h - top)
            src.getPixels(pixels, 0, w, 0, top, w, rows)
            for (i in 0 until w * rows) {
                val p = pixels[i]
                val alpha = p ushr 24
                if (alpha == 0) {
                    pixels[i] = p
                    continue
                }
                val inputR = ((p shr 16) and 255).toDouble()
                val inputG = ((p shr 8) and 255).toDouble()
                val inputB = (p and 255).toDouble()
                val originalY = 0.2126 * inputR + 0.7152 * inputG + 0.0722 * inputB

                // Apply WB to shadows and midtones; fade it near blown-out
                // highlights to avoid cyan/magenta channel clipping.
                val wbFade = 1.0 - ease(190.0, 252.0, max(inputR, max(inputG, inputB)))
                var r = inputR * (1.0 + (redGain - 1.0) * wbFade)
                var g = inputG
                var b = inputB * (1.0 + (blueGain - 1.0) * wbFade)
                val peak = max(r, max(g, b))
                if (peak > 255.0) {
                    val fit = 255.0 / peak
                    r *= fit
                    g *= fit
                    b *= fit
                }

                val y = (0.2126 * r + 0.7152 * g + 0.0722 * b).coerceIn(0.0, 255.0)
                val index = y.toInt().coerceIn(0, 255)
                val next = min(index + 1, 255)
                val target = tone[index] + (tone[next] - tone[index]) * (y - index)

                // Skip nearly imperceptible per-pixel changes to keep processed
                // smartphone JPEGs stable. Subtle correction is the goal.
                val adjustment = if (kotlin.math.abs(target - originalY) < 0.35 &&
                    kotlin.math.abs(redGain - 1.0) < 0.006 &&
                    kotlin.math.abs(blueGain - 1.0) < 0.006) {
                    pixels[i] = p
                    continue
                } else target

                // Keep hue and skin tones intact; unlike the v0.3 experiment,
                // don't apply forced global saturation.
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                var chroma = 1.0
                val upper = hi - y
                val lower = y - lo
                if (upper > 0.001) chroma =
                    min(chroma, ((255.0 - adjustment) / upper).coerceAtLeast(0.0))
                if (lower > 0.001) chroma =
                    min(chroma, (adjustment / lower).coerceAtLeast(0.0))
                val outR = (adjustment + (r - y) * chroma).roundToInt().coerceIn(0, 255)
                val outG = (adjustment + (g - y) * chroma).roundToInt().coerceIn(0, 255)
                val outB = (adjustment + (b - y) * chroma).roundToInt().coerceIn(0, 255)
                pixels[i] = (alpha shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
            result.setPixels(pixels, 0, w, 0, top, w, rows)
        }
        return result
    }
}
