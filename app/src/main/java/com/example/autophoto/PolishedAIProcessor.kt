package com.example.autophoto

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AutoPhoto Polished Impact Lab v0.4.4 (Polished baseline unchanged)
 *
 * Original / neural-LUT fusion -> scene-dependent filmic tone curve ->
 * vibrance and colour separation -> softly masked, edge-limited texture pop.
 *
 * REAL offline neural component: AlternativeAIProcessor.adaptiveLut runs
 * the existing pretrained ONNX 3D LUT classifier and learned 3D LUT bases.
 * The subsequent "polished look" is deterministic photographic processing,
 * not generative AI or semantic segmentation.
 *
 * The source Bitmap and ALL older processor implementations stay untouched.
 */
object PolishedAIProcessor {
    enum class Style(
        val label: String,
        val neuralBlend: Float,
        val contrast: Float,
        val midtoneLift: Float,
        val vibrance: Float,
        val detailGain: Float
    ) {
        POLISHED("Polished", 0.75f, 0.10f, 5.0f, 0.12f, 0.24f),
        POLISHED_PLUS("Polished+", 0.90f, 0.16f, 8.5f, 0.21f, 0.36f),
        WOW("Wow", 1.00f, 0.23f, 13.0f, 0.30f, 0.52f)
    }

    data class Result(
        val bitmap: Bitmap,
        val style: Style,
        val totalMs: Long,
        val neuralMs: Long,
        val changedPercent: Float,
        val beforeMeanY: Float,
        val afterMeanY: Float,
        val darkSceneProtected: Boolean,
        val clippedChannelPercent: Float
    )

    private fun clamp(x: Float): Float = x.coerceIn(0f, 1f)
    private fun ease(x: Float): Float {
        val t = clamp(x)
        return t * t * (3f - 2f * t)
    }
    private fun lum(p: Int): Float =
        0.2126f * ((p shr 16) and 255) +
        0.7152f * ((p shr 8) and 255) +
        0.0722f * (p and 255)

    /**
     * Gamut mapping: preserve the target luminance and RGB hue direction by
     * reducing chroma together, not clipping channels individually.
     * Intentionally only used by new Polished+ and Wow looks.
     */
    private fun safeChromaScale(targetY: Float, r: Float, g: Float,
                                b: Float, sourceY: Float,
                                desired: Float): Float {
        val upper = max(r, max(g, b)) - sourceY
        val lower = sourceY - min(r, min(g, b))
        var limit = desired
        if (upper > 0.001f) limit = min(limit, (255f - targetY) / upper)
        if (lower > 0.001f) limit = min(limit, targetY / lower)
        return max(0f, limit)
    }

    private fun sample(field: FloatArray, w: Int, h: Int,
                       x: Float, y: Float): Float {
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val ix = fx.toInt()
        val iy = fy.toInt()
        val jx = min(ix + 1, w - 1)
        val jy = min(iy + 1, h - 1)
        val tx = fx - ix
        val ty = fy - iy
        return ((field[iy * w + ix] * (1f - tx) +
            field[iy * w + jx] * tx) * (1f - ty) +
            (field[jy * w + ix] * (1f - tx) +
            field[jy * w + jx] * tx) * ty)
    }

    private fun percentile(hist: IntArray, fraction: Float): Int {
        val total = hist.sum()
        if (total == 0) return 128
        val needed = (total * fraction).roundToInt().coerceAtLeast(1)
        var count = 0
        for (v in 0 until 256) {
            count += hist[v]
            if (count >= needed) return v
        }
        return 255
    }

    fun enhance(context: Context, source: Bitmap, style: Style,
                progress: (Int) -> Unit): Result {
        require(source.width > 0 && source.height > 0)
        val start = SystemClock.elapsedRealtime()
        progress(1)

        // Genuine image-specific pretrained LUT, independent of Auto or the
        // other buttons. Infer once, then make three fixed style choices A/B.
        val neuralReport = AlternativeAIProcessor.adaptiveLut(
            context, source) { pct ->
                progress((1 + pct * 34 / 100).coerceIn(1, 35))
            }
        val neural = neuralReport.bitmap
        progress(36)

        // Robust global analysis at at most 512px long edge; the final
        // processing and JPEG output remain at source decoded resolution.
        val scale = min(1.0, 512.0 / max(source.width, source.height))
        val aw = max(1, (source.width * scale).roundToInt())
        val ah = max(1, (source.height * scale).roundToInt())
        val preview = Bitmap.createScaledBitmap(source, aw, ah, true)
        val previewPixels = IntArray(aw * ah)
        preview.getPixels(previewPixels, 0, aw, 0, 0, aw, ah)
        if (preview !== source) preview.recycle()
        val histogram = IntArray(256)
        val luma = FloatArray(aw * ah)
        for (i in previewPixels.indices) {
            val y = lum(previewPixels[i])
            luma[i] = y
            histogram[y.roundToInt().coerceIn(0, 255)]++
        }
        val p50 = percentile(histogram, 0.50f)
        val p90 = percentile(histogram, 0.90f)
        val darkMixedScene = p50 < 58 && p90 > 100
        val darkLowLight = p50 < 58 && p90 <= 100
        val darkScene = darkMixedScene || darkLowLight

        val base = FloatArray(luma.size)
        val texture = FloatArray(luma.size)
        for (yy in 0 until ah) {
            for (xx in 0 until aw) {
                val i = yy * aw + xx
                val center = luma[i]
                var sum = 0f
                var weights = 0f
                var smooth = 0f
                var sumY = 0f
                var sumY2 = 0f
                for (dy in -2..2) {
                    val py = (yy + dy).coerceIn(0, ah - 1)
                    for (dx in -2..2) {
                        val px = (xx + dx).coerceIn(0, aw - 1)
                        val v = luma[py * aw + px]
                        val sp = if (dy == 0 && dx == 0) 3f
                            else if (dx == 0 || dy == 0) 1.6f
                            else 1f
                        val range = (v - center) / 26f
                        val wt = sp / (1f + range * range)
                        smooth += v * wt
                        weights += wt
                        sum += sp
                        sumY += sp * v
                        sumY2 += sp * v * v
                    }
                }
                base[i] = smooth / weights.coerceAtLeast(0.001f)
                val mean = sumY / sum
                val sigma = sqrt(max(0f, sumY2 / sum - mean * mean))
                texture[i] = ease((sigma - 4f) / 17f) *
                    (1f - 0.47f * ease((sigma - 48f) / 58f))
            }
            if (yy % 45 == 0) progress(36 + (13.0 * yy / ah).roundToInt())
        }
        progress(50)

        // All Polished calculations remain equivalent to the 0.4.3 baseline.
        // New looks use a second, distinct finishing architecture.
        val impact = when (style) {
            Style.POLISHED -> 0f
            Style.POLISHED_PLUS -> 0.62f
            Style.WOW -> 1f
        }
        val output = Bitmap.createBitmap(
            source.width, source.height, Bitmap.Config.ARGB_8888)
        val batch = min(16, source.height)
        val sourceRows = IntArray(batch * source.width)
        val neuralRows = IntArray(batch * source.width)
        val outputRows = IntArray(batch * source.width)
        val totalCount = source.width.toLong() * source.height
        var edited: Long = 0
        var clipped: Long = 0
        var inputTotal = 0.0
        var outputTotal = 0.0

        for (top in 0 until source.height step batch) {
            val nRows = min(batch, source.height - top)
            source.getPixels(sourceRows, 0, source.width,
                0, top, source.width, nRows)
            neural.getPixels(neuralRows, 0, source.width,
                0, top, source.width, nRows)
            for (py in 0 until nRows) {
                val ay = (top + py + 0.5f) * ah / source.height - 0.5f
                for (px in 0 until source.width) {
                    val i = py * source.width + px
                    val original = sourceRows[i]
                    val alpha = original ushr 24
                    if (alpha == 0) {
                        outputRows[i] = original
                        continue
                    }
                    val sr = ((original shr 16) and 255).toFloat()
                    val sg = ((original shr 8) and 255).toFloat()
                    val sb = (original and 255).toFloat()
                    val neuralPixel = neuralRows[i]
                    val nr = ((neuralPixel shr 16) and 255).toFloat()
                    val ng = ((neuralPixel shr 8) and 255).toFloat()
                    val nb = (neuralPixel and 255).toFloat()
                    val originalY = lum(original)
                    inputTotal += originalY

                    // The learnt LUT provides scene-dependent RGB adjustment.
                    val r = sr + (nr - sr) * style.neuralBlend
                    val g = sg + (ng - sg) * style.neuralBlend
                    val b = sb + (nb - sb) * style.neuralBlend
                    val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val hi = max(r, max(g, b))
                    val lo = min(r, min(g, b))
                    val saturation = (hi - lo) / hi.coerceAtLeast(1f)

                    // Scene-aware dramatic tone with near-black anchors;
                    // campfires and other naturally dark scenes stay dark.
                    val shadowGuard = if (darkScene)
                        ease((y - 28f) / 83f)
                    else 0.12f + 0.88f * ease((y - 12f) / 59f)
                    val highlightGuard = 1f -
                        0.80f * ease((y - 182f) / 74f)
                    val dynamic = if (darkScene) 0.45f else 1f
                    val middle = ease((y - 22f) / 65f) *
                        (1f - ease((y - 178f) / 73f))
                    val contrast = (y - 117f) * style.contrast
                    val lift = style.midtoneLift * middle * dynamic
                    val tone = ((contrast + lift) *
                        shadowGuard * highlightGuard).coerceIn(-19f, 19f)

                    // Soft edge-aware existing-detail enhancement, avoiding
                    // natural bokeh and near-opaque black backgrounds.
                    val ax = (px + 0.5f) * aw / source.width - 0.5f
                    val t = sample(texture, aw, ah, ax, ay)
                    val localBase = sample(base, aw, ah, ax, ay)
                    val detail = (originalY - localBase)
                        .coerceIn(-23f, 23f)
                    val skinLike = ease((sr - sg - 5f) / 38f) *
                        ease((sg - sb - 5f) / 37f) *
                        ease((originalY - 51f) / 68f) *
                        (1f - ease((originalY - 218f) / 35f))
                    val redFlower = ease((sr - max(sg, sb) - 25f) / 64f)
                    val highlightSafety = 1f -
                        0.90f * ease((originalY - 193f) / 60f)
                    val smoothDark = if (darkScene)
                        ease((originalY - 37f) / 80f) else
                        ease((originalY - 16f) / 56f)
                    val detailSafety = t * (1f - 0.64f * skinLike) *
                        (1f - 0.33f * redFlower) *
                        smoothDark * highlightSafety
                    val texturePop = (detail * style.detailGain *
                        detailSafety).coerceIn(-6.5f, 6.5f)

                    // Vibrance enriches muted colours most. Warm skin and
                    // saturated flowers receive less chroma amplification.
                    val vividness = style.vibrance *
                        (1f - 0.72f * saturation) *
                        (1f - 0.69f * skinLike) *
                        (1f - 0.50f * redFlower) *
                        (1f - 0.65f * ease((y - 202f) / 52f))
                    val colourScale = (1f + vividness).coerceIn(1f, 1.4f)

                    // Richer midtone light/dark separation, not a uniform
                    // brightness increase. Black floor and specular/channel
                    // highlights are anchored to protect night and petals.
                    val peakOriginal = max(sr, max(sg, sb))
                    val channelHighlightGuard = 1f -
                        0.88f * ease((peakOriginal - 202f) / 49f)
                    val normalizedY = (y - 128f) / 128f
                    val sCurve = normalizedY *
                        (1f - normalizedY * normalizedY)
                    val tonalSeparation = if (impact > 0f)
                        (sCurve * 42f * impact * shadowGuard *
                            channelHighlightGuard *
                            (if (darkScene) 0.53f else 1f))
                            .coerceIn(-14f, 14f)
                    else 0f

                    // Stronger *existing* texture separation in defined
                    // regions, not generative detail or semantic segmentation.
                    // Skin, flowers, shadows and bokeh remain guarded.
                    val structuredArea = ease((t - 0.16f) / 0.48f)
                    val dimension = if (impact > 0f)
                        (detail * (0.36f + 0.80f * impact) *
                            detailSafety * structuredArea *
                            channelHighlightGuard).coerceIn(-11f, 11f)
                    else 0f

                    // Saturation selectively enriches muted colours while
                    // suppressing neon foliage and red/magenta petal damage.
                    val vividGreen = ease((sg - max(sr, sb) - 14f) / 55f) *
                        ease((saturation - 0.20f) / 0.45f)
                    val pinkPetal = ease((sr - sg - 12f) / 55f) *
                        ease((sb - sg - 9f) / 55f) *
                        ease((saturation - 0.20f) / 0.48f)
                    val vibrantGuard = (1f - 0.70f * skinLike) *
                        (1f - 0.68f * max(redFlower, pinkPetal)) *
                        (1f - 0.58f * vividGreen) *
                        channelHighlightGuard
                    val extraColour = if (impact > 0f)
                        impact * (0.12f + 0.13f * (1f - saturation)) *
                            vibrantGuard
                    else 0f

                    // The old Polished branch remains untouched numerically.
                    // Count candidate out-of-gamut pixels before preserving
                    // hue/luminance with chroma-only gamut mapping.
                    val newY = if (impact > 0f)
                        (y + tone + texturePop + tonalSeparation + dimension)
                            .coerceIn(0f, 255f)
                    else y + tone + texturePop
                    val requestedChroma = colourScale + extraColour
                    val proposedR = newY + (r - y) * requestedChroma
                    val proposedG = newY + (g - y) * requestedChroma
                    val proposedB = newY + (b - y) * requestedChroma
                    if (proposedR < 0f || proposedG < 0f || proposedB < 0f ||
                        proposedR > 255f || proposedG > 255f ||
                        proposedB > 255f) clipped++
                    val appliedChroma = if (impact > 0f)
                        safeChromaScale(newY, r, g, b, y, requestedChroma)
                    else requestedChroma
                    val cr = newY + (r - y) * appliedChroma
                    val cg = newY + (g - y) * appliedChroma
                    val cb = newY + (b - y) * appliedChroma
                    val rr = cr.roundToInt().coerceIn(0, 255)
                    val gg = cg.roundToInt().coerceIn(0, 255)
                    val bb = cb.roundToInt().coerceIn(0, 255)
                    val resultPixel = (alpha shl 24) or
                        (rr shl 16) or (gg shl 8) or bb
                    outputRows[i] = resultPixel
                    outputTotal += 0.2126 * rr + 0.7152 * gg + 0.0722 * bb
                    if (resultPixel != original) edited++
                }
            }
            output.setPixels(outputRows, 0, source.width,
                0, top, source.width, nRows)
            progress(50 + (49.0 * (top + nRows) / source.height).roundToInt())
        }
        progress(100)
        if (neural !== source) neural.recycle()
        return Result(
            bitmap = output,
            style = style,
            totalMs = SystemClock.elapsedRealtime() - start,
            neuralMs = neuralReport.inferenceMillis,
            changedPercent = 100f * edited / totalCount.coerceAtLeast(1),
            beforeMeanY = (inputTotal / totalCount.coerceAtLeast(1)).toFloat(),
            afterMeanY = (outputTotal / totalCount.coerceAtLeast(1)).toFloat(),
            darkSceneProtected = darkScene,
            clippedChannelPercent = 100f * clipped / totalCount.coerceAtLeast(1)
        )
    }
}
