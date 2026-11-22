package com.dennisp.s23lab.core

import java.util.Locale

/**
 * The controlled benchmark protocol. Two kinds of run:
 *  - SYNTHETIC: LiteRT-LM's own benchmark() with an exact number of prefill and decode tokens. It
 *    creates a fresh engine every run, so it also measures load time. This is the speed comparison.
 *  - PROMPT: real prompts on a loaded engine, greedy sampling, fixed output cap, thinking off,
 *    a fresh conversation per run. Token counts and rates come from getBenchmarkInfo().
 * No figure here is estimated from characters.
 */
object BenchProtocol {
    const val SYNTHETIC = "synthetic"
    const val PROMPT = "prompt"

    /** Decode-rate variation allowed across measured runs for a result to count as stable. */
    const val STABLE_CV = 0.10

    data class Config(
        val name: String,
        val prefillLengths: List<Int>,
        val decodeTokens: Int,
        val warmups: Int,
        val runs: Int,
        val promptRuns: Int,
        val maxOutputTokens: Int,
    ) {
        init {
            require(prefillLengths.isNotEmpty() && prefillLengths.all { it > 0 })
            require(decodeTokens > 0 && warmups >= 0 && runs > 0 && promptRuns >= 0 && maxOutputTokens > 0)
        }
        /** Synthetic runs per backend, warm-ups included. */
        val syntheticRunsPerBackend: Int get() = prefillLengths.size * (warmups + runs)
    }

    /** About 3 minutes per backend: a quick look, not for quoting. */
    val QUICK = Config("Quick", listOf(256), decodeTokens = 128, warmups = 1, runs = 3, promptRuns = 2, maxOutputTokens = 128)

    /** The Milestone 1 protocol: three prompt lengths, 2 warm-ups discarded, 10 measured runs. */
    val FULL = Config("Full", listOf(64, 256, 1024), decodeTokens = 128, warmups = 2, runs = 10, promptRuns = 5, maxOutputTokens = 128)

    /** Fixed real prompts. The second is shaped like a Master Room measurement report. */
    val PROMPTS = listOf(
        "short" to "In one sentence, explain what offline AI does on a phone.",
        "report" to ("A mastered track measured: integrated loudness -9.1 LUFS, short-term maximum -6.8 LUFS, " +
            "true peak -0.2 dBTP, loudness range 4.1 LU, crest factor 7.9 dB, stereo correlation 0.82, " +
            "low end below 120 Hz is 3 dB above the reference curve, 8 to 12 kHz is 1.5 dB below it. " +
            "The streaming target is -14 LUFS integrated with a true peak ceiling of -1 dBTP. " +
            "In three short sentences, explain which two measurements matter most and why. " +
            "Do not invent any numbers that are not listed above."),
    )

    data class Run(
        val backend: String,
        val kind: String,
        val label: String,
        val prefillTarget: Int,
        val warmup: Boolean,
        val index: Int,
        val initSeconds: Double,
        val ttftSeconds: Double,
        val prefillTokens: Int,
        val decodeTokens: Int,
        val prefillTps: Double,
        val decodeTps: Double,
        val wallMs: Long,
        val peakPssMb: Double,
        val peakNativeMb: Double,
        val peakGraphicsMb: Double,
        val thermalBefore: Int,
        val thermalAfter: Int,
        val headroomBefore: Double,
        val batteryTempC: Double,
        val charging: Boolean,
        val currentMicroA: Long?,
        val outputPreview: String = "",
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    data class Summary(
        val backend: String,
        val kind: String,
        val label: String,
        val prefillTarget: Int,
        val measured: Int,
        val prefillTpsMedian: Double,
        val decodeTpsMedian: Double,
        val decodeCv: Double,
        val ttftMedianS: Double,
        val firstInitS: Double,
        val laterInitMedianS: Double,
        val peakPssMb: Double,
        val peakGraphicsMb: Double,
        val anyCharging: Boolean,
        val maxThermal: Int,
    ) {
        val stable: Boolean get() = decodeCv.isFinite() && decodeCv < STABLE_CV
    }

    /** Summaries per backend, kind and prompt; warm-ups and failed runs are excluded from the statistics. */
    fun summarise(runs: List<Run>): List<Summary> =
        runs.groupBy { Triple(it.backend, it.kind, it.label) }.map { (key, group) ->
            val measured = group.filter { !it.warmup && it.ok }
            // Load time: the first run of a group (warm-up or not) is the first load in this session.
            val inits = group.filter { it.ok }.sortedBy { it.index }.map { it.initSeconds }
            Summary(
                backend = key.first,
                kind = key.second,
                label = key.third,
                prefillTarget = group.first().prefillTarget,
                measured = measured.size,
                prefillTpsMedian = Stats.median(measured.map { it.prefillTps }),
                decodeTpsMedian = Stats.median(measured.map { it.decodeTps }),
                decodeCv = Stats.coefficientOfVariation(measured.map { it.decodeTps }),
                ttftMedianS = Stats.median(measured.map { it.ttftSeconds }),
                firstInitS = inits.firstOrNull() ?: Double.NaN,
                laterInitMedianS = Stats.median(inits.drop(1)),
                peakPssMb = measured.maxOfOrNull { it.peakPssMb } ?: Double.NaN,
                peakGraphicsMb = measured.maxOfOrNull { it.peakGraphicsMb } ?: Double.NaN,
                anyCharging = measured.any { it.charging },
                maxThermal = measured.maxOfOrNull { it.thermalAfter } ?: -1,
            )
        }.sortedWith(compareBy({ it.kind }, { it.prefillTarget }, { it.label }, { it.backend }))

    data class Ratio(val label: String, val prefill: Double, val decode: Double, val ttft: Double)

    /** GPU ÷ CPU for each matching prompt; speeds above 1 mean GPU is faster, ttft below 1 means GPU answers sooner. */
    fun gpuOverCpu(summaries: List<Summary>): List<Ratio> =
        summaries.filter { it.backend == "GPU" }.mapNotNull { g ->
            val c = summaries.firstOrNull { it.backend == "CPU" && it.kind == g.kind && it.label == g.label }
                ?: return@mapNotNull null
            Ratio(g.kind + " " + g.label, g.prefillTpsMedian / c.prefillTpsMedian,
                g.decodeTpsMedian / c.decodeTpsMedian, g.ttftMedianS / c.ttftMedianS)
        }

    private val CSV_HEADER = listOf(
        "backend", "kind", "label", "prefill_target", "warmup", "index", "init_s", "ttft_s",
        "prefill_tokens", "decode_tokens", "prefill_tps", "decode_tps", "wall_ms", "peak_pss_mb",
        "peak_native_mb", "peak_graphics_mb", "thermal_before", "thermal_after", "headroom_before",
        "battery_temp_c", "charging", "current_ua", "error",
    )

    fun toCsv(runs: List<Run>): String = buildString {
        appendLine(CSV_HEADER.joinToString(","))
        for (r in runs) {
            appendLine(listOf(
                r.backend, r.kind, r.label, r.prefillTarget, r.warmup, r.index, n(r.initSeconds), n(r.ttftSeconds),
                r.prefillTokens, r.decodeTokens, n(r.prefillTps), n(r.decodeTps), r.wallMs, n(r.peakPssMb),
                n(r.peakNativeMb), n(r.peakGraphicsMb), r.thermalBefore, r.thermalAfter, n(r.headroomBefore),
                n(r.batteryTempC), r.charging, r.currentMicroA ?: "", r.error ?: "",
            ).joinToString(",") { csvCell(it.toString()) })
        }
    }

    fun toJson(meta: Map<String, Any?>, runs: List<Run>): String {
        val summaries = summarise(runs)
        return JsonOut.write(linkedMapOf(
            "meta" to meta,
            "summaries" to summaries.map { s ->
                linkedMapOf(
                    "backend" to s.backend, "kind" to s.kind, "label" to s.label, "prefill_target" to s.prefillTarget,
                    "measured_runs" to s.measured, "prefill_tps_median" to s.prefillTpsMedian,
                    "decode_tps_median" to s.decodeTpsMedian, "decode_cv" to s.decodeCv, "stable" to s.stable,
                    "ttft_median_s" to s.ttftMedianS, "first_init_s" to s.firstInitS,
                    "later_init_median_s" to s.laterInitMedianS, "peak_pss_mb" to s.peakPssMb,
                    "peak_graphics_mb" to s.peakGraphicsMb, "any_charging" to s.anyCharging, "max_thermal_status" to s.maxThermal,
                )
            },
            "gpu_over_cpu" to gpuOverCpu(summaries).map {
                linkedMapOf("label" to it.label, "prefill" to it.prefill, "decode" to it.decode, "ttft" to it.ttft)
            },
            "runs" to runs.map { r ->
                linkedMapOf(
                    "backend" to r.backend, "kind" to r.kind, "label" to r.label, "prefill_target" to r.prefillTarget,
                    "warmup" to r.warmup, "index" to r.index, "init_s" to r.initSeconds, "ttft_s" to r.ttftSeconds,
                    "prefill_tokens" to r.prefillTokens, "decode_tokens" to r.decodeTokens, "prefill_tps" to r.prefillTps,
                    "decode_tps" to r.decodeTps, "wall_ms" to r.wallMs, "peak_pss_mb" to r.peakPssMb,
                    "peak_native_mb" to r.peakNativeMb, "peak_graphics_mb" to r.peakGraphicsMb,
                    "thermal_before" to r.thermalBefore, "thermal_after" to r.thermalAfter,
                    "headroom_before" to r.headroomBefore, "battery_temp_c" to r.batteryTempC, "charging" to r.charging,
                    "current_ua" to r.currentMicroA, "output_preview" to r.outputPreview, "error" to r.error,
                )
            },
        ))
    }

    /** Short plain-text summary for the screen and for pasting into a chat. */
    fun summaryText(summaries: List<Summary>): String = buildString {
        for (s in summaries) {
            append(s.backend).append(' ').append(s.kind).append(' ').append(s.label).append(": ")
            append(s.measured).append(" runs, prefill ").append(f1(s.prefillTpsMedian)).append(" tok/s, decode ")
            append(f1(s.decodeTpsMedian)).append(" tok/s (variation ").append(pct(s.decodeCv)).append(if (s.stable) ", stable" else ", NOT stable")
            append("), first token ").append(f2(s.ttftMedianS)).append(" s")
            if (s.kind == SYNTHETIC) append(", load ").append(f2(s.firstInitS)).append(" s first / ").append(f2(s.laterInitMedianS)).append(" s later")
            append(", peak ").append(f0(s.peakPssMb)).append(" MB")
            if (s.anyCharging) append(" [charging]")
            if (s.maxThermal >= 2) append(" [thermal ").append(ThermalPolicy.statusName(s.maxThermal)).append(']')
            appendLine()
        }
        val ratios = gpuOverCpu(summaries)
        if (ratios.isNotEmpty()) {
            appendLine("GPU ÷ CPU:")
            for (r in ratios) appendLine("  " + r.label + ": prefill " + f2(r.prefill) + "×, decode " + f2(r.decode) + "×, first token time " + f2(r.ttft) + "×")
        }
    }

    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun n(d: Double) = if (d.isFinite()) String.format(Locale.US, "%.4f", d) else ""
    fun f0(d: Double) = if (d.isFinite()) String.format(Locale.US, "%.0f", d) else "–"
    fun f1(d: Double) = if (d.isFinite()) String.format(Locale.US, "%.1f", d) else "–"
    fun f2(d: Double) = if (d.isFinite()) String.format(Locale.US, "%.2f", d) else "–"
    fun pct(d: Double) = if (d.isFinite()) String.format(Locale.US, "%.1f%%", d * 100) else "–"
}
