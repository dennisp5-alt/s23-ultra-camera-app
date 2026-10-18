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
        val lowLightScene: Boolean,
        val personMaskActive: Boolean = false,
        val personMaskCoverage: Float = 0f,
        val personInferenceMs: Long = 0,
        val personStatus: String = "Not requested",
        val skyCoverage: Float = 0f,
        val waterCoverage: Float = 0f,
        val sceneSegmentationMs: Long = 0,
        val sceneStatus: String = "Not requested"
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

    /**
     * A separate semantic experiment. The existing `enhance` path is invoked
     * unchanged when the app's old "Adaptive Masked AI" button is tapped.
     * Person segmentation may be absent on distant/occluded subjects.
     */
    fun enhanceSemantic(context: Context, src: Bitmap, progress: (Int) -> Unit): Report {
        progress(1)
        val person = PersonSegmentation.infer(context, src)
        progress(12)
        val result = enhance(context, src, person) { pct ->
            progress(12 + (pct * 88 / 100).coerceIn(0, 88))
        }
        return result.copy(
            personMaskActive = person.detected,
            personMaskCoverage = person.coverage,
            personInferenceMs = person.elapsedMillis,
            personStatus = person.note,
            totalMillis = result.totalMillis + person.elapsedMillis
        )
    }

    /** Experimental real four-class sky/water/person scene segmentation. */
    fun enhanceScene(context: Context, src: Bitmap,
                     progress: (Int) -> Unit): Report {
        progress(1)
        val scene = SceneSegmentation.infer(context, src)
        progress(15)
        val result = enhance(context, src, scene = scene) { pct ->
            progress(15 + (pct * 85 / 100).coerceIn(0, 85))
        }
        return result.copy(
            skyCoverage = scene.skyCoverage,
            waterCoverage = scene.waterCoverage,
            personMaskActive = scene.detected &&
                scene.personCoverage >= 0.004f,
            personMaskCoverage = scene.personCoverage,
            sceneSegmentationMs = scene.elapsedMillis,
            sceneStatus = scene.note,
            totalMillis = result.totalMillis + scene.elapsedMillis
        )
    }

    fun enhance(context: Context, src: Bitmap,
                semantic: PersonSegmentation.Result? = null,
                scene: SceneSegmentation.Result? = null,
                progress: (Int) -> Unit): Report {
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

            // Score the neural predictions on the same mask-grid as the
            // original. These are real trained-model outputs, not handpicked
            // colour targets. Small previews make regional analysis cheap.
            val mobilePreview = Bitmap.createScaledBitmap(mobile.bitmap, w, h, true)
            val lutPreview = Bitmap.createScaledBitmap(lut.bitmap, w, h, true)
            val mobilePixels = IntArray(w * h)
            val lutPixels = IntArray(w * h)
            mobilePreview.getPixels(mobilePixels, 0, w, 0, 0, w, h)
            lutPreview.getPixels(lutPixels, 0, w, 0, 0, w, h)
            if (mobilePreview !== mobile.bitmap) mobilePreview.recycle()
            if (lutPreview !== lut.bitmap) lutPreview.recycle()

            val luma = FloatArray(pixels.size) { i -> luminance(pixels[i]) }
            val p50 = percentile(luma, 0.5f)
            val p90 = percentile(luma, 0.9f)
            val lowLight = p90 < 95 && p50 < 50

            // Pass 2: physically interpretable region masks and local trust
            // estimates from both actual AI outputs. We deliberately do not
            // pretend to recognise people, roses, sky or any semantic objects.
            val mobileMask = FloatArray(pixels.size)
            val lutMask = FloatArray(pixels.size)
            val detailMask = FloatArray(pixels.size)

            // Soft person/background neural segmentation. Sky, foliage and
            // water below are only *tonal/colour/position candidates* — never
            // asserted as pixel-accurate object classes.
            val personRaw = if (semantic?.mask != null ||
                scene?.person != null) FloatArray(pixels.size) else null
            val skyRaw = if (semantic != null || scene != null)
                FloatArray(pixels.size) else null
            val hazeRaw = if (semantic != null || scene != null)
                FloatArray(pixels.size) else null
            val neighborhood = if (semantic != null || scene != null)
                FloatArray(pixels.size) else null
            val darkBackgroundWithSubject = p50 < 54 && p90 > 108

            fun modelTrust(base: Int, predicted: Int, originalY: Float,
                           sensitiveColour: Float): Float {
                val dr = (((predicted shr 16) and 255) -
                    ((base shr 16) and 255)).toFloat()
                val dg = (((predicted shr 8) and 255) -
                    ((base shr 8) and 255)).toFloat()
                val db = ((predicted and 255) - (base and 255)).toFloat()
                val dy = 0.2126f * dr + 0.7152f * dg + 0.0722f * db
                val hueError = (abs(dr - dy) + abs(dg - dy) +
                    abs(db - dy)) / 3f
                // Soft confidence decay for unexpectedly extreme neural
                // exposure / colour changes, not a hard seam-producing cutoff.
                val exposureOutlier = abs(dy) / if (lowLight) 25f else 16f
                val colourOutlier = hueError / if (sensitiveColour > 0.4f) 8f else 14f
                val risk = exposureOutlier * exposureOutlier +
                    colourOutlier * colourOutlier
                val shadowRisk = if (dy < 0f && originalY < 78f)
                    0.26f else 0f
                return (1f / (1f + risk + shadowRisk)).coerceIn(0.10f, 1f)
            }

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val p = pixels[i]
                    val r = ((p shr 16) and 255).toFloat()
                    val g = ((p shr 8) and 255).toFloat()
                    val b = (p and 255).toFloat()
                    val lum = luma[i]

                    val black = 1f - ease((lum - 18f) / 56f)
                    val highlight = ease((lum - 139f) / 101f)
                    val pinkFamily = min(ease((r - g - 9f) / 55f),
                        ease((b - g - 3f) / 50f)) * ease((r - 65f) / 80f)
                    val greenFamily = ease((g - max(r, b) - 7f) / 48f) *
                        ease((g - 43f) / 88f)
                    // Colour ranges are *not* skin/flower recognition: regions
                    // can share a hue. Sensitive warm/pink areas get less
                    // chroma alteration irrespective of their actual subject.
                    val warmSensitive = ease((r - g - 6f) / 44f) *
                        ease((g - b + 10f) / 55f) *
                        ease((r - 65f) / 85f)
                    val colourSensitive = max(pinkFamily, warmSensitive)
                    val rightY = luma[y * w + min(x + 1, w - 1)]
                    val downY = luma[min(y + 1, h - 1) * w + x]
                    val edge = ease(max(abs(lum - rightY), abs(lum - downY)) / 42f)
                    detailMask[i] = edge
                    val person = if (scene?.person != null && personRaw != null) {
                        val sx = (x + 0.5f) * scene.width / w - 0.5f
                        val sy = (y + 0.5f) * scene.height / h - 0.5f
                        val confidence = sample(scene.person, scene.width,
                            scene.height, sx, sy)
                        ease((confidence - 0.35f) / 0.56f)
                    } else if (personRaw != null && semantic?.mask != null) {
                        val mx = (x + 0.5f) * PersonSegmentation.SIDE / w - 0.5f
                        val my = (y + 0.5f) * PersonSegmentation.SIDE / h - 0.5f
                        val confidence = sample(semantic.mask,
                            PersonSegmentation.SIDE, PersonSegmentation.SIDE, mx, my)
                        // Avoid abrupt people/background cutouts: segmentation
                        // confidence converted to a gradual blending weight.
                        ease((confidence - 0.23f) / 0.58f)
                    } else 0f
                    personRaw?.set(i, person)
                    val sat = (max(r, max(g, b)) - min(r, min(g, b))) /
                        max(40f, max(r, max(g, b)))
                    val upper = 1f - ease((y.toFloat()/h - 0.19f)/0.24f)
                    val paleBlueGray = (1f-ease((sat-0.07f)/0.23f)) *
                        ease((lum - 154f)/67f) *
                        (1f-ease((r-b-28f)/40f))
                    val skyCandidate = if (scene?.sky != null) {
                        val sx = (x + 0.5f) * scene.width / w - 0.5f
                        val sy = (y + 0.5f) * scene.height / h - 0.5f
                        ease((sample(scene.sky,scene.width,scene.height,sx,sy)
                            -0.37f)/0.55f) * (1f-person)
                    } else upper * paleBlueGray * (1f-person)
                    skyRaw?.set(i, skyCandidate)
                    val lower = ease((y.toFloat()/h - 0.53f)/0.25f)
                    // Conservative low-texture gray-blue region estimate —
                    // not a trained water detector.
                    val lowTexture = 1f - ease((max(abs(lum-rightY),
                        abs(lum-downY))-5f)/30f)
                    val grayTone = (1f-ease((sat-0.11f)/0.21f)) *
                        ease((lum-40f)/55f) *
                        (1f-ease((lum-201f)/47f))
                    val waterCandidate = if (scene?.water != null) {
                        val sx = (x + 0.5f) * scene.width / w - 0.5f
                        val sy = (y + 0.5f) * scene.height / h - 0.5f
                        ease((sample(scene.water,scene.width,scene.height,
                            sx,sy)-0.32f)/0.58f) * (1f-person) *
                            (1f-skyCandidate)
                    } else lower * lowTexture * grayTone *
                        (1f-person) * (1f-skyCandidate)
                    hazeRaw?.set(i, waterCandidate)
                    if (neighborhood != null) {
                        // Small local luminance average to guide *very small*
                        // contrast recovery in flat, misty-looking regions.
                        val left = luma[y*w+max(0,x-1)]
                        val above = luma[max(0,y-1)*w+x]
                        neighborhood[i] = (lum*4f + rightY + downY +
                            left + above) / 8f
                    }

                    val mobileY = luminance(mobilePixels[i])
                    val lutY = luminance(lutPixels[i])
                    val disagreement = abs(mobileY - lutY)
                    val agreement = 1f / (1f + (disagreement / 16f) *
                        (disagreement / 16f))
                    val mobileTrust = modelTrust(p, mobilePixels[i],
                        lum, colourSensitive)
                    val lutTrust = modelTrust(p, lutPixels[i],
                        lum, colourSensitive)

                    val middle = ease((lum - 19f) / 57f)
                    val mobilePreference = (if (lowLight) 0.67f else 0.32f) *
                        (0.19f + 0.81f * middle) *
                        (1f - 0.83f * highlight) *
                        (1f - 0.56f * pinkFamily) *
                        (if (lowLight) 1f else 1f - 0.80f * black)
                    val lutPreference = (0.87f -
                        0.48f * black -
                        0.50f * highlight -
                        0.39f * pinkFamily -
                        0.16f * greenFamily -
                        0.12f * edge)
                        .coerceIn(0.03f, 0.84f)

                    // More confidence where both neural predictions agree;
                    // otherwise favour whichever has fewer tone/chroma risks.
                    val confidence = 0.68f + 0.32f * agreement
                    val focus = 1f - 0.16f * edge
                    val sceneFactor = if (darkBackgroundWithSubject &&
                        black > 0.65f) 0.35f else 1f
                    // In the experimental semantic path favour trustworthy
                    // subject-local corrections while keeping identified sky
                    // and low-texture water-like backgrounds more restrained.
                    val scenicProtection = if (semantic != null || scene != null)
                        (1f - 0.55f*(skyRaw?.get(i) ?: 0f) -
                            0.24f*(hazeRaw?.get(i) ?: 0f))
                            .coerceIn(0.30f, 1f) else 1f
                    mobileMask[i] = (mobilePreference * mobileTrust *
                        confidence * focus * sceneFactor * scenicProtection *
                        (1f + 0.22f*person))
                        .coerceIn(0f, 0.68f)
                    lutMask[i] = (lutPreference * lutTrust *
                        confidence * focus * sceneFactor * scenicProtection *
                        (1f + 0.30f*person))
                        .coerceIn(0f, 0.83f)
                }
            }
            progress(78)

            // Refinement pass: edge-aware feathered masks, no hard segmentation.
            // Two small passes suppress quantisation/segmentation boundaries
            // while respecting strong tone boundaries; no box-shaped tiles.
            val softMobile = smoothEdgeAware(
                smoothEdgeAware(mobileMask, luma, w, h), luma, w, h)
            val softLut = smoothEdgeAware(
                smoothEdgeAware(lutMask, luma, w, h), luma, w, h)
            val softDetail = smoothEdgeAware(detailMask, luma, w, h)
            val softPerson = personRaw?.let {
                smoothEdgeAware(smoothEdgeAware(it,luma,w,h),luma,w,h)
            }
            val softSky = skyRaw?.let { smoothEdgeAware(it,luma,w,h) }
            val softHaze = hazeRaw?.let { smoothEdgeAware(it,luma,w,h) }
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
                        if (total > 0.94f) {
                            val factor = 0.94f / total
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
                        val fall = if (lum < 50f) -1.3f else -5f
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
                progress(82 + (13.0 * (top + rows) / src.height).roundToInt())
            }
            // Pass 4: quality-control pass at native output resolution.
            // Recheck the *proposed* edited image against the untouched
            // original rather than blindly adding more passes/strength.
            // An edge/highlight can need *less* editing, not more.
            progress(95)
            val checked = Bitmap.createBitmap(src.width, src.height,
                Bitmap.Config.ARGB_8888)
            val candidateRow = IntArray(src.width * batch)
            for (top in 0 until src.height step batch) {
                val rows = min(batch, src.height - top)
                src.getPixels(originalRow, 0, src.width, 0, top, src.width, rows)
                edited.getPixels(candidateRow, 0, src.width, 0, top, src.width, rows)
                for (ry in 0 until rows) {
                    val maskY = (top + ry + 0.5f) * h / src.height - 0.5f
                    for (x in 0 until src.width) {
                        val i = ry * src.width + x
                        val op = originalRow[i]
                        val alpha = op ushr 24
                        if (alpha == 0) continue
                        val np = candidateRow[i]
                        val r = ((op shr 16) and 255).toFloat()
                        val g = ((op shr 8) and 255).toFloat()
                        val b = (op and 255).toFloat()
                        val nr = ((np shr 16) and 255).toFloat()
                        val ng = ((np shr 8) and 255).toFloat()
                        val nb = (np and 255).toFloat()
                        val originalY = luminance(op)
                        val currentY = luminance(np)
                        val mx = (x + 0.5f) * w / src.width - 0.5f
                        val edge = sample(softDetail, w, h, mx, maskY)
                        val highlight = ease((originalY - 168f) / 72f)
                        val deepBlack = 1f - ease((originalY - 12f) / 40f)

                        // A brighter *new* image is not automatically better.
                        // Screen edges, backlit flower petals and deep black
                        // areas should keep their original texture/tonality.
                        val safetyBlend = (0.14f * edge +
                            0.37f * highlight + 0.31f * deepBlack)
                            .coerceIn(0f, 0.67f)
                        val person = if (softPerson != null)
                            sample(softPerson,w,h,mx,maskY) else 0f
                        val sky = if (softSky != null)
                            sample(softSky,w,h,mx,maskY) else 0f
                        val waterLike = if (softHaze != null)
                            sample(softHaze,w,h,mx,maskY) else 0f
                        val proposedY = currentY - originalY
                        val maxRise = min(
                            if (lowLight) 21f else 15f,
                            (251f - max(r, max(g, b))).coerceAtLeast(0f))
                        val toneClamp = proposedY.coerceIn(
                            if (originalY < 48f) -1.4f else -8f,
                            if (originalY < 20f) 1.2f else maxRise)
                        val redDelta = nr - r
                        val greenDelta = ng - g
                        val blueDelta = nb - b
                        val excess = proposedY - toneClamp
                        // Revert selectively to original when new correction
                        // violates per-region exposure, colour or edge safety.
                        fun finalChannel(base: Float, delta: Float): Int =
                            (base + (delta - excess) * (1f - safetyBlend))
                                .roundToInt().coerceIn(0, 255)
                        // Independently exposure-balance underexposed subject
                        // regions when confident person segmentation exists.
                        // Same addition to RGB channels preserves skin hue.
                        val subjectLift = if (semantic != null || scene != null)
                            (3.7f*person*ease((originalY-26f)/48f) *
                                (1f-ease((originalY-123f)/73f)) *
                                (1f-highlight) *
                                (1f - ease((max(r,max(g,b))-239f)/12f)))
                                .coerceIn(0f,3.7f) else 0f
                        // Very small local contrast for low-texture background
                        // regions (not a generative dehaze operation).
                        val atmosphericContrast = if (neighborhood != null) {
                            val local = sample(neighborhood,w,h,mx,maskY)
                            ((originalY-local)*0.22f*waterLike*
                                (1f-person)*(1f-sky)).coerceIn(-2.3f,2.3f)
                        } else 0f
                        // Trained water masks selectively protect brightness,
                        // while gently increasing *existing* local contrast.
                        // Clipping and native detail safety remain hard limits.
                        val waterConservation = if (scene?.water != null)
                            (1f - 0.42f*waterLike).coerceIn(0.50f,1f)
                            else 1f
                        val finalTone = (subjectLift +
                            atmosphericContrast*waterConservation)
                            .coerceIn(-2.3f,
                                (250f - max(r,max(g,b))).coerceAtLeast(0f))
                        val fr = (finalChannel(r, redDelta)+finalTone)
                            .roundToInt().coerceIn(0,255)
                        val fg = (finalChannel(g, greenDelta)+finalTone)
                            .roundToInt().coerceIn(0,255)
                        val fb = (finalChannel(b, blueDelta)+finalTone)
                            .roundToInt().coerceIn(0,255)
                        candidateRow[i] = (alpha shl 24) or
                            (fr shl 16) or (fg shl 8) or fb
                    }
                }
                checked.setPixels(candidateRow, 0, src.width, 0, top,
                    src.width, rows)
                progress(95 + (5.0 * (top + rows) / src.height).roundToInt())
            }
            edited.recycle()
            progress(100)
            return Report(checked, mobile.inferenceMillis, lut.inferenceMillis,
                SystemClock.elapsedRealtime() - start, lowLight)
        } finally {
            mobile.bitmap.recycle()
            lut.bitmap.recycle()
        }
    }
}
