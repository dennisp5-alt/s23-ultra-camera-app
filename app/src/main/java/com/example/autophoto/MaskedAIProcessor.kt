package com.example.autophoto

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Experimental local-mask compositor. Unlike semantic segmentation, this
 * identifies continuous tonal/colour ranges, not particular objects.
 *
 * Pass 1: trained MobileIE + trained image-adaptive 3D-LUT each run exactly once.
 * Pass 2: low-resolution tonal, pink-family, foliage and texture-sensitive masks.
 * Pass 3: edge-aware soft-mask feathering.
 * Pass 4: locally blended neural corrections with highlight/black-point limits.
 *
 * Neither pretrained model, image size, original Auto, nor existing AI buttons
 * are modified by this pipeline.
 */
object MaskedAIProcessor {
    data class Report(
        val bitmap: Bitmap,
        val mobileInferenceMs: Long,
        val lutInferenceMs: Long,
        val totalMillis: Long,
        val lowLightScene: Boolean
    )

    private const val MASK_MAX_EDGE = 640

    private fun ease(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun luminance(p: Int): Float =
        0.2126f * ((p shr 16) and 255) +
        0.7152f * ((p shr 8) and 255) +
        0.0722f * (p and 255)

    private fun percentile(samples: FloatArray, f: Float): Int {
        val hist = IntArray(256)
        val stride = max(1, samples.size / 50000)
        var n = 0
        for (i in samples.indices step stride) {
            hist[samples[i].roundToInt().coerceIn(0, 255)]++
            n++
        }
        val target = max(1, (f * n).roundToInt())
        var sum = 0
        for (v in 0..255) {
            sum += hist[v]
            if (sum >= target) return v
        }
        return 255
    }

    /** Bilinear sampling of smoothed masks, no discontinuous rectangular tiles. */
    private fun sample(mask: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val px = x.coerceIn(0f, (w - 1).toFloat())
        val py = y.coerceIn(0f, (h - 1).toFloat())
        val ix = px.toInt()
        val iy = py.toInt()
        val rx = min(ix + 1, w - 1)
        val ry = min(iy + 1, h - 1)
        val fx = px - ix
        val fy = py - iy
        val a = mask[iy * w + ix]
        val b = mask[iy * w + rx]
        val c = mask[ry * w + ix]
        val d = mask[ry * w + rx]
        return ((a * (1f - fx) + b * fx) * (1f - fy) +
            (c * (1f - fx) + d * fx) * fy)
    }

    /** Smooth masks without spreading corrections across high-contrast edges. */
    private fun smoothEdgeAware(source: FloatArray, luminance: FloatArray,
                                w: Int, h: Int): FloatArray {
        val result = FloatArray(source.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val center = luminance[i]
                var weighted = 0f
                var weightSum = 0f
                for (dy in -1..1) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val j = sy * w + sx
                        val spatial = if (dx == 0 && dy == 0) 4f
                            else if (dx == 0 || dy == 0) 2f else 1f
                        val contrast = abs(center - luminance[j]) / 18f
                        val weight = spatial / (1f + contrast * contrast)
                        weighted += source[j] * weight
                        weightSum += weight
                    }
                }
                result[i] = (weighted / weightSum.coerceAtLeast(0.00001f))
                    .coerceIn(0f, 1f)
            }
        }
        return result
    }

    fun enhance(context: Context, src: Bitmap, progress: (Int) -> Unit): Report {
        val start = SystemClock.elapsedRealtime()
        progress(1)

        // Run each trained model only once, rather than rerunning per-region.
        // The costly part remains fast; masks are lightweight mathematical
        // weight maps to guide *where* the neural corrections are applied.
        val mobile = AlternativeAIProcessor.mobileIE(context, src) { percent ->
            progress((percent * 34 / 100).coerceIn(1, 35))
        }
        progress(36)
        val lut: AlternativeAIProcessor.Report
        try {
            lut = AlternativeAIProcessor.adaptiveLut(context, src) { percent ->
                progress(36 + percent * 36 / 100)
            }
        } catch (failure: Throwable) {
            mobile.bitmap.recycle()
            throw failure
        }
        progress(73)

        try {
            val scale = min(1.0, MASK_MAX_EDGE.toDouble() / max(src.width, src.height))
            val w = max(1, (src.width * scale).roundToInt())
            val h = max(1, (src.height * scale).roundToInt())
            val preview = Bitmap.createScaledBitmap(src, w, h, true)
            val pixels = IntArray(w * h)
            preview.getPixels(pixels, 0, w, 0, 0, w, h)
            if (preview !== src) preview.recycle()

            val luma = FloatArray(pixels.size) { i -> luminance(pixels[i]) }
            val p50 = percentile(luma, 0.5f)
            val p90 = percentile(luma, 0.9f)
            val lowLight = p90 < 95 && p50 < 50

            // Region masks are *soft probabilities* estimated from actual pixel
            // values. No false claim of semantic object segmentation is made.
            val mobileMask = FloatArray(pixels.size)
            val lutMask = FloatArray(pixels.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val p = pixels[i]
                    val r = ((p shr 16) and 255).toFloat()
                    val g = ((p shr 8) and 255).toFloat()
                    val b = (p and 255).toFloat()
                    val lum = luma[i]

                    val black = 1f - ease((lum - 17f) / 65f)
                    val highlight = ease((lum - 150f) / 90f)
                    val pinkFamily = min(ease((r - g - 9f) / 55f),
                        ease((b - g - 3f) / 50f)) * ease((r - 65f) / 80f)
                    val greenFamily = ease((g - max(r, b) - 7f) / 48f) *
                        ease((g - 43f) / 88f)
                    val localGradient = max(
                        abs(lum - luma[y * w + min(x + 1, w - 1)]),
                        abs(lum - luma[min(y + 1, h - 1) * w + x])
                    )
                    val edge = ease(localGradient / 55f)

                    val lutWeight = (0.77f - 0.39f * black -
                        0.38f * highlight - 0.31f * pinkFamily -
                        0.11f * greenFamily - 0.07f * edge)
                        .coerceIn(0.04f, 0.74f)
                    val middle = ease((lum - 16f) / 55f)
                    val mobileWeight = ((if (lowLight) 0.53f else 0.27f) *
                        (0.35f + 0.65f * middle) *
                        (1f - 0.75f * highlight) *
                        (if (lowLight) 1f else 1f - 0.68f * black) *
                        (1f - 0.42f * pinkFamily))
                        .coerceIn(0f, 0.56f)

                    mobileMask[i] = mobileWeight
                    lutMask[i] = lutWeight
                }
            }
            progress(78)

            // Refinement pass: edge-aware feathered masks, no hard segmentation.
            val softMobile = smoothEdgeAware(mobileMask, luma, w, h)
            val softLut = smoothEdgeAware(lutMask, luma, w, h)
            progress(82)

            // Final native-resolution compositing and safeguards, row-by-row
            // (avoids an additional 12MP pixel array for every input).
            val edited = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val batch = min(12, src.height)
            val originalRow = IntArray(src.width * batch)
            val mobileRow = IntArray(src.width * batch)
            val lutRow = IntArray(src.width * batch)
            for (top in 0 until src.height step batch) {
                val rows = min(batch, src.height - top)
                src.getPixels(originalRow, 0, src.width, 0, top, src.width, rows)
                mobile.bitmap.getPixels(mobileRow, 0, src.width, 0, top, src.width, rows)
                lut.bitmap.getPixels(lutRow, 0, src.width, 0, top, src.width, rows)
                for (ry in 0 until rows) {
                    val my = (top + ry + 0.5f) * h / src.height - 0.5f
                    for (x in 0 until src.width) {
                        val i = ry * src.width + x
                        val p = originalRow[i]
                        val alpha = p ushr 24
                        if (alpha == 0) continue
                        val px = (x + 0.5f) * w / src.width - 0.5f
                        var mw = sample(softMobile, w, h, px, my)
                        var lw = sample(softLut, w, h, px, my)
                        val total = mw + lw
                        if (total > 0.91f) {
                            val factor = 0.91f / total
                            mw *= factor
                            lw *= factor
                        }
                        val r = ((p shr 16) and 255).toFloat()
                        val g = ((p shr 8) and 255).toFloat()
                        val b = (p and 255).toFloat()
                        val mp = mobileRow[i]
                        val lp = lutRow[i]
                        val dr = mw * (((mp shr 16) and 255) - r) +
                            lw * (((lp shr 16) and 255) - r)
                        val dg = mw * (((mp shr 8) and 255) - g) +
                            lw * (((lp shr 8) and 255) - g)
                        val db = mw * ((mp and 255) - b) +
                            lw * ((lp and 255) - b)

                        val lum = luminance(p)
                        val tone = 0.2126f * dr + 0.7152f * dg + 0.0722f * db
                        val highest = max(r, max(g, b))
                        // Maintain dark backgrounds and prevent clipped
                        // roses/skin/bright areas in the final blend.
                        val limit = when {
                            lum < 22f -> 1.2f
                            lum < 53f -> if (lowLight) 7f else 3f
                            lum < 150f -> if (lowLight) 16f else 12f
                            lum < 205f -> 6f
                            else -> 2.5f
                        }
                        val headroom = (252f - highest).coerceAtLeast(0f)
                        val rise = min(limit, headroom)
                        val fall = if (lum < 50f) -1.6f else -5f
                        val protectedTone = tone.coerceIn(fall, rise)

                        val pink = r > g * 1.20f && b > g * 1.04f && r > 85f
                        val chromaLimit = if (pink) 1.0f else 2.0f
                        val rr = (r + protectedTone +
                            (dr - tone).coerceIn(-chromaLimit, chromaLimit))
                            .roundToInt().coerceIn(0, 255)
                        val gg = (g + protectedTone +
                            (dg - tone).coerceIn(-chromaLimit, chromaLimit))
                            .roundToInt().coerceIn(0, 255)
                        val bb = (b + protectedTone +
                            (db - tone).coerceIn(-chromaLimit, chromaLimit))
                            .roundToInt().coerceIn(0, 255)
                        originalRow[i] = (alpha shl 24) or
                            (rr shl 16) or (gg shl 8) or bb
                    }
                }
                edited.setPixels(originalRow, 0, src.width, 0, top, src.width, rows)
                progress(82 + (18.0 * (top + rows) / src.height).roundToInt())
            }
            progress(100)
            return Report(edited, mobile.inferenceMillis, lut.inferenceMillis,
                SystemClock.elapsedRealtime() - start, lowLight)
        } finally {
            mobile.bitmap.recycle()
            lut.bitmap.recycle()
        }
    }
}
