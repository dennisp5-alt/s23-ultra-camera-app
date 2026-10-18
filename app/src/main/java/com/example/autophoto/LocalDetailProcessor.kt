package com.example.autophoto

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * New experimental, non-neural detail processor for Quality Lab v0.4.0.
 *
 * OpenCV-inspired but independently implemented in Kotlin:
 *  1. Analyse local contrast at up to 1024px edge using a 5x5
 *     edge-preserving bilateral luminance base and spatial variance map.
 *  2. Interpolate continuous soft contrast/detail masks at full resolution.
 *  3. Refine only trustworthy actual local texture using a bounded 3x3
 *     native-resolution edge-aware luminance difference.
 *  4. Per-pixel safeguards prevent clipped channels, chroma changes,
 *     sharpening of out-of-focus regions and strong-edge halos.
 *
 * No hallucination, upscaling, segmentation or automatic denoising.
 * Source image and all prior processing engines are never mutated.
 */
object LocalDetailProcessor {
    // Strength scales signal-dependent detail adjustments. Each rerun
    // always starts from the original decoded photo, never from a prior
    // candidate enhancement.
    enum class Strength(val label: String, val gain: Float, val maxShift: Float) {
        GENTLE("Gentle", 1.2f, 4.0f),
        BALANCED("Balanced", 2.5f, 7.0f),
        STRONG("Strong", 4.0f, 10.0f)
    }

    data class Result(
        val bitmap: Bitmap,
        val elapsedMs: Long,
        val meanAbsLumaChange: Float,
        val changedPercent: Float,
        val maskPercent: Float,
        val inputMeanY: Float,
        val outputMeanY: Float,
        val maximumAbsoluteYShift: Float,
        val strength: Strength
    )

    private const val ANALYSIS_LONG_EDGE = 1024
    private fun clamp(x: Float) = x.coerceIn(0f, 1f)
    private fun ease(x: Float): Float {
        val v = clamp(x)
        return v * v * (3f - 2f * v)
    }
    private fun y(p: Int): Float =
        0.2126f * ((p shr 16) and 255) +
        0.7152f * ((p shr 8) and 255) +
        0.0722f * (p and 255)

    private fun sample(map: FloatArray, w: Int, h: Int,
                       x: Float, y: Float): Float {
        val px = x.coerceIn(0f, (w - 1).toFloat())
        val py = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = px.toInt()
        val y0 = py.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val dx = px - x0
        val dy = py - y0
        val p = y0 * w
        val q = y1 * w
        val upper = map[p + x0] * (1f - dx) + map[p + x1] * dx
        val lower = map[q + x0] * (1f - dx) + map[q + x1] * dx
        return upper * (1f - dy) + lower * dy
    }

    fun enhance(source: Bitmap, strength: Strength = Strength.BALANCED,
                progress: (Int) -> Unit): Result {
        require(source.width > 0 && source.height > 0)
        val started = SystemClock.elapsedRealtime()
        progress(1)
        val w0 = source.width
        val h0 = source.height
        val scale = min(1.0,
            ANALYSIS_LONG_EDGE.toDouble() / max(w0, h0))
        val w = max(1, (w0 * scale).roundToInt())
        val h = max(1, (h0 * scale).roundToInt())
        val downscaled = Bitmap.createScaledBitmap(source, w, h, true)
        val small = IntArray(w * h)
        downscaled.getPixels(small, 0, w, 0, 0, w, h)
        if (downscaled !== source) downscaled.recycle()

        val lowLuma = FloatArray(small.size) { i -> y(small[i]) }
        val base = FloatArray(small.size)
        val texture = FloatArray(small.size)
        var trusted = 0
        // A 5x5 spatial/range guided luminance estimate distinguishes
        // out-of-focus smooth regions from actual middle-frequency detail.
        for (row in 0 until h) {
            for (col in 0 until w) {
                val i = row * w + col
                val centre = lowLuma[i]
                var spatialSum = 0f
                var spatialY = 0f
                var spatialY2 = 0f
                var weightedY = 0f
                var weightedSum = 0f
                for (dy in -2..2) {
                    val sy = (row + dy).coerceIn(0, h - 1)
                    val oy = dy * dy
                    for (dx in -2..2) {
                        val sx = (col + dx).coerceIn(0, w - 1)
                        val value = lowLuma[sy * w + sx]
                        val spatial = when (oy + dx * dx) {
                            0 -> 4f
                            1 -> 3f
                            2 -> 2.3f
                            4 -> 1.65f
                            else -> 1f
                        }
                        val diff = (value - centre) / 25f
                        val rangeWeight = 1f / (1f + diff * diff)
                        val weight = spatial * rangeWeight
                        spatialSum += spatial
                        spatialY += spatial * value
                        spatialY2 += spatial * value * value
                        weightedSum += weight
                        weightedY += value * weight
                    }
                }
                val mean = spatialY / spatialSum
                val variance = max(0f, spatialY2 / spatialSum - mean * mean)
                val sigma = sqrt(variance)
                // Very flat areas are probably natural bokeh or sky.
                // Extremely busy edges receive less amplification to avoid
                // high-contrast halos around petals, filaments and windows.
                val contrastMask = ease((sigma - 3.0f) / 10f) *
                    (1f - 0.50f * ease((sigma - 48f) / 68f))
                texture[i] = contrastMask.coerceIn(0f, 1f)
                base[i] = weightedY / weightedSum.coerceAtLeast(0.0001f)
                if (contrastMask > 0.22f) trusted++
            }
            if (row % 32 == 0) progress(3 + (28.0 * row / h).roundToInt())
        }
        progress(33)

        // Source pixels held once; output is written row-wise to avoid a
        // second full-size pixel array. S23U works with 4096px decoded photos.
        val pixels = IntArray(w0 * h0)
        source.getPixels(pixels, 0, w0, 0, 0, w0, h0)
        val output = Bitmap.createBitmap(w0, h0, Bitmap.Config.ARGB_8888)
        val row = IntArray(w0)
        var changed: Long = 0L
        var totalAbs = 0.0
        var totalOriginalY = 0.0
        var totalEnhancedY = 0.0
        var maximumApplied = 0f
        val minSrc = 4f
        val maxSrc = 251f

        for (sy in 0 until h0) {
            val mapY = (sy + 0.5f) * h / h0 - 0.5f
            for (sx in 0 until w0) {
                val index = sy * w0 + sx
                val p = pixels[index]
                val a = (p ushr 24) and 255
                if (a == 0) {
                    row[sx] = p
                    continue
                }
                val r = ((p shr 16) and 255).toFloat()
                val g = ((p shr 8) and 255).toFloat()
                val b = (p and 255).toFloat()
                val lum = y(p)
                totalOriginalY += lum
                val high = max(r, max(g, b))
                val low = min(r, min(g, b))
                val sat = (high - low) / high.coerceAtLeast(1f)
                val mapX = (sx + 0.5f) * w / w0 - 0.5f
                val mask = sample(texture, w, h, mapX, mapY)

                // Mask known high-risk areas with continuous thresholds:
                // saturated bottlebrush reds, warm skin-like colours,
                // highlights, near-black texture, and blurred background.
                val saturatedColourProtection = 1f - 0.57f *
                    ease((sat - 0.51f) / 0.38f)
                val redFlower = if (r > g + 38f && r > b + 24f)
                    (1f - 0.43f * ease((r - max(g, b) - 28f) / 50f))
                    else 1f
                val warmSkinLike = if (r > g + 9f &&
                    g > b + 6f && r > 80f)
                    1f - 0.47f * ease((r - b - 17f) / 90f)
                    else 1f
                val shadowProtection = ease((lum - 15f) / 57f)
                val highlightProtection = 1f - 0.76f *
                    ease((lum - 170f) / 80f)
                val protectedMask = mask * saturatedColourProtection *
                    redFlower * warmSkinLike * shadowProtection *
                    highlightProtection

                if (protectedMask < 0.006f) {
                    row[sx] = p
                    totalEnhancedY += lum
                    continue
                }

                // Pixel-native 3x3 edge-aware smoothing captures genuine
                // fine grain/texture while suppressing discontinuity halos.
                var weightedY = 0f
                var weightedSum = 0f
                var strongestEdge = 0f
                for (dy in -1..1) {
                    val py = (sy + dy).coerceIn(0, h0 - 1)
                    for (dx in -1..1) {
                        val px = (sx + dx).coerceIn(0, w0 - 1)
                        val neighbor = y(pixels[py * w0 + px])
                        val delta = neighbor - lum
                        strongestEdge = max(strongestEdge, abs(delta))
                        val spatial = if (dy == 0 && dx == 0) 4f
                            else if (dy == 0 || dx == 0) 2f
                            else 1f
                        val d = delta / 13f
                        val weight = spatial / (1f + d * d)
                        weightedY += weight * neighbor
                        weightedSum += weight
                    }
                }
                val fineDetail = lum - weightedY / weightedSum
                val mediumDetail = lum - sample(base, w, h, mapX, mapY)
                val edgeSafety = 1f - 0.65f *
                    ease((strongestEdge - 25f) / 78f)

                // Adaptive fine detail threshold: suppress low-amplitude
                // random pixel noise while retaining genuine bright/dark
                // flower filament edges. Smooth natural bokeh gets almost
                // zero strength because the analysis texture mask is weak.
                val fineNoiseFloor = 0.75f + (1f - mask) * 1.65f
                val fineMagnitude = (abs(fineDetail) -
                    fineNoiseFloor).coerceAtLeast(0f)
                val fineSignal = if (fineDetail < 0f)
                    -fineMagnitude else fineMagnitude

                // Decouple medium/local contrast from pixel-level sharpness
                // to avoid purely noise-driven enhancement.
                val midSignal = (abs(mediumDetail) - 1.25f)
                    .coerceAtLeast(0f).coerceAtMost(20f) *
                    if (mediumDetail < 0f) -1f else 1f
                val textureConfidence = (0.72f +
                    0.28f * ease(fineMagnitude / 9f))
                    .coerceIn(0f, 1f)
                val raw = (0.78f * fineSignal.coerceIn(-15f, 15f) +
                    0.34f * midSignal) * strength.gain *
                    textureConfidence * protectedMask * edgeSafety
                // If a source channel is already outside the chosen
                // safety range, do not force it inward: refuse only those
                // corrections that would push that channel further out.
                val minimumDelta = min(0f, minSrc - low)
                val maximumDelta = max(0f, maxSrc - high)
                val delta = raw.coerceIn(-strength.maxShift,
                    strength.maxShift)
                    .coerceIn(minimumDelta, maximumDelta)
                val rr = (r + delta).roundToInt().coerceIn(0, 255)
                val gg = (g + delta).roundToInt().coerceIn(0, 255)
                val bb = (b + delta).roundToInt().coerceIn(0, 255)
                val outputPixel = (a shl 24) or (rr shl 16) or
                    (gg shl 8) or bb
                row[sx] = outputPixel
                val applied = 0.2126f * (rr - r) +
                    0.7152f * (gg - g) + 0.0722f * (bb - b)
                totalAbs += abs(applied).toDouble()
                totalEnhancedY += lum + applied
                maximumApplied = max(maximumApplied, abs(applied))
                if (rr.toFloat() != r || gg.toFloat() != g ||
                    bb.toFloat() != b) changed++
            }
            output.setPixels(row, 0, w0, 0, sy, w0, 1)
            if (sy % 35 == 0) {
                progress(34 + (65.0 * sy / h0).roundToInt())
            }
        }
        progress(100)
        val pixelsCount = (w0.toLong() * h0.toLong()).coerceAtLeast(1L)
        return Result(
            bitmap = output,
            elapsedMs = SystemClock.elapsedRealtime() - started,
            meanAbsLumaChange = (totalAbs / pixelsCount).toFloat(),
            changedPercent = 100f * changed / pixelsCount,
            maskPercent = 100f * trusted / small.size.coerceAtLeast(1),
            inputMeanY = (totalOriginalY / pixelsCount).toFloat(),
            outputMeanY = (totalEnhancedY / pixelsCount).toFloat(),
            maximumAbsoluteYShift = maximumApplied,
            strength = strength
        )
    }
}
