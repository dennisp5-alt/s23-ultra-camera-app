package com.dennisp.s23lab.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    @Test fun medianAndPercentile() {
        assertEquals(3.0, Stats.median(listOf(5.0, 1.0, 3.0)), 1e-9)
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 2.0, 3.0)), 1e-9)
        assertEquals(1.0, Stats.percentile(listOf(1.0, 2.0, 3.0), 0.0), 1e-9)
        assertTrue(Stats.median(emptyList()).isNaN())
        assertEquals(2.0, Stats.median(listOf(2.0, Double.NaN)), 1e-9)
    }

    @Test fun variationAndSlope() {
        assertEquals(0.0, Stats.coefficientOfVariation(listOf(10.0, 10.0, 10.0)), 1e-12)
        // Sample sd of 9, 10, 11 is 1; mean 10 → 10%.
        assertEquals(0.1, Stats.coefficientOfVariation(listOf(9.0, 10.0, 11.0)), 1e-9)
        assertTrue(Stats.coefficientOfVariation(listOf(1.0)).isNaN())
        assertEquals(2.0, Stats.slope(listOf(1.0, 3.0, 5.0, 7.0)), 1e-9)
    }
}

class JsonOutTest {
    @Test fun writesValidJson() {
        val text = JsonOut.write(linkedMapOf(
            "s" to "quote \" backslash \\ newline \n tab \t ctrl \u0001", "n" to 3, "d" to 1.5, "nan" to Double.NaN,
            "inf" to Double.POSITIVE_INFINITY, "b" to true, "nil" to null, "list" to listOf(1, "a", null), "small" to 1.0E-7,
        ))
        val j = JSONObject(text)
        assertEquals("quote \" backslash \\ newline \n tab \t ctrl \u0001", j.getString("s"))
        assertEquals(3, j.getInt("n"))
        assertEquals(1.5, j.getDouble("d"), 1e-12)
        assertTrue(j.isNull("nan"))
        assertTrue(j.isNull("inf"))
        assertEquals(1.0E-7, j.getDouble("small"), 1e-15)
        assertEquals(3, j.getJSONArray("list").length())
    }
}

class BenchProtocolTest {
    private fun run(backend: String, label: String, warmup: Boolean, index: Int, decode: Double, prefill: Double = 100.0,
                    init: Double = 1.0, error: String? = null, charging: Boolean = false) =
        BenchProtocol.Run(backend, BenchProtocol.SYNTHETIC, label, 256, warmup, index, init, 0.5, 256, 128, prefill, decode, 1000,
            800.0, 300.0, 200.0, 0, 1, 0.3, 30.0, charging, null, error = error)

    @Test fun warmupsAndFailuresAreExcluded() {
        val runs = listOf(
            run("CPU", "prefill 256", true, 0, 1.0, init = 5.0),
            run("CPU", "prefill 256", false, 1, 20.0, init = 1.0),
            run("CPU", "prefill 256", false, 2, 20.0, init = 1.2),
            run("CPU", "prefill 256", false, 3, 999.0, error = "boom"),
            run("CPU", "prefill 256", false, 4, 20.0, init = 1.1),
        )
        val s = BenchProtocol.summarise(runs).single()
        assertEquals(3, s.measured)
        assertEquals(20.0, s.decodeTpsMedian, 1e-9)
        assertTrue(s.stable)
        // First load is the first run (the warm-up); later loads are the median of the rest.
        assertEquals(5.0, s.firstInitS, 1e-9)
        assertEquals(1.1, s.laterInitMedianS, 1e-9)
    }

    @Test fun unstableWhenDecodeVariesMoreThanTenPercent() {
        val runs = listOf(10.0, 14.0, 18.0).mapIndexed { i, d -> run("GPU", "prefill 256", false, i, d) }
        assertFalse(BenchProtocol.summarise(runs).single().stable)
    }

    @Test fun gpuOverCpuMatchesLabels() {
        val runs = listOf(
            run("CPU", "prefill 256", false, 0, 10.0, prefill = 200.0), run("CPU", "prefill 256", false, 1, 10.0, prefill = 200.0),
            run("GPU", "prefill 256", false, 0, 25.0, prefill = 1400.0), run("GPU", "prefill 256", false, 1, 25.0, prefill = 1400.0),
            run("GPU", "prefill 64", false, 0, 30.0),
        )
        val r = BenchProtocol.gpuOverCpu(BenchProtocol.summarise(runs)).single()
        assertEquals(2.5, r.decode, 1e-9)
        assertEquals(7.0, r.prefill, 1e-9)
        assertEquals(1.0, r.ttft, 1e-9)
    }

    @Test fun csvHasOneRowPerRunAndQuotesCommas() {
        val runs = listOf(run("CPU", "prefill 256", false, 0, 10.0, error = "bad, worse"))
        val lines = BenchProtocol.toCsv(runs).trim().lines()
        assertEquals(2, lines.size)
        assertEquals(24, lines[0].split(",").size)
        assertEquals(24, lines[1].split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")).size)
        assertTrue(lines[1].endsWith("\"bad, worse\",default"))
    }

    @Test fun jsonExportParses() {
        val runs = listOf(run("CPU", "prefill 256", false, 0, 10.0), run("GPU", "prefill 256", false, 0, 22.0, charging = true))
        val j = JSONObject(BenchProtocol.toJson(mapOf("app_version" to "0.4.0"), runs))
        assertEquals("0.4.0", j.getJSONObject("meta").getString("app_version"))
        assertEquals(2, j.getJSONArray("runs").length())
        assertEquals(2, j.getJSONArray("summaries").length())
        assertEquals(2.2, j.getJSONArray("gpu_over_cpu").getJSONObject(0).getDouble("decode"), 1e-9)
        assertTrue(BenchProtocol.summaryText(BenchProtocol.summarise(runs)).contains("[charging in 1/1 runs]"))
    }

    @Test fun protocolsMatchMilestone() {
        assertEquals(listOf(64, 256, 1024), BenchProtocol.FULL.prefillLengths)
        assertEquals(2, BenchProtocol.FULL.warmups)
        assertEquals(10, BenchProtocol.FULL.runs)
        assertEquals(4, BenchProtocol.FULL.warmupsFor(0))
        assertEquals(2, BenchProtocol.FULL.warmupsFor(1))
        assertEquals(38, BenchProtocol.FULL.syntheticRunsPerBackend)
        // 1,024 prefill + 128 decode must fit the 1,280-token Qwen3 0.6B build.
        assertTrue(BenchProtocol.FULL.prefillLengths.max() + BenchProtocol.FULL.decodeTokens <= 1280)
    }
}

class ModelCheckTest {
    private val paris = ModelCheck.PROBES[0]

    @Test fun onTopicAnswerPasses() {
        val o = ModelCheck.evaluate(paris, "Paris.")
        assertTrue(o.onTopic)
        assertFalse(o.thinkingSeen)
    }

    @Test fun offTopicAndThinkingAreCaught() {
        assertFalse(ModelCheck.evaluate(paris, "Hello! How can I help you today?").onTopic)
        val t = ModelCheck.evaluate(paris, "<think>The user asks about France.</think>Paris")
        assertTrue(t.thinkingSeen)
        assertTrue(t.onTopic)
        assertTrue(ModelCheck.evaluate(paris, "Paris", mapOf("thinking" to "hmm")).thinkingSeen)
        // Word boundaries: "4" must not match "14".
        assertFalse(ModelCheck.evaluate(ModelCheck.PROBES[1], "14").onTopic)
        assertTrue(ModelCheck.evaluate(ModelCheck.PROBES[1], "The answer is 4.").onTopic)
        assertFalse(ModelCheck.evaluate(paris, "Paris", error = "failed").onTopic)
    }

    @Test fun verdicts() {
        val good = ModelCheck.PROBES.map { ModelCheck.evaluate(it, it.expected.first()) }
        assertTrue(ModelCheck.verdict(good).passed)
        val empty = ModelCheck.PROBES.map { ModelCheck.evaluate(it, "I'm here to help!") }
        val v = ModelCheck.verdict(empty)
        assertFalse(v.passed)
        assertTrue(v.text.contains("no-think"))
        val thinking = ModelCheck.PROBES.map { ModelCheck.evaluate(it, "<think>x</think>" + it.expected.first()) }
        assertFalse(ModelCheck.verdict(thinking).passed)
        assertFalse(ModelCheck.verdict(good.take(3)).passed)
    }
}

class ThermalPolicyTest {
    @Test fun gates() {
        assertTrue(ThermalPolicy.beforeRun(0, 0.3, 80, false, false).ok)
        assertFalse(ThermalPolicy.beforeRun(3, 0.3, 80, false, false).ok)
        assertFalse(ThermalPolicy.beforeRun(1, 0.96, 80, false, false).ok)
        assertTrue(ThermalPolicy.beforeRun(1, Double.NaN, 80, false, false).ok)
        assertFalse(ThermalPolicy.beforeRun(0, 0.1, 10, false, false).ok)
        assertTrue(ThermalPolicy.beforeRun(0, 0.1, 10, true, false).ok)
        assertTrue(ThermalPolicy.beforeRun(0, 0.1, -1, false, false).ok)
        assertFalse(ThermalPolicy.beforeRun(0, 0.1, 80, false, true).ok)
        assertFalse(ThermalPolicy.duringRun(3).ok)
        assertTrue(ThermalPolicy.duringRun(2).ok)
        assertTrue(ThermalPolicy.duringRun(-1).ok)
    }

    @Test fun headroomIsRateLimitedToTenSeconds() {
        var now = 0L
        var reads = 0
        val values = ArrayDeque(listOf(0.4f, 0.5f, Float.NaN))
        val cache = ThermalPolicy.HeadroomCache(read = { reads++; values.removeFirst() }, clock = { now })
        assertEquals(0.4, cache.get(), 1e-6)
        now = 9_999
        assertEquals(0.4, cache.get(), 1e-6)
        assertEquals(1, reads)
        now = 10_000
        assertEquals(0.5, cache.get(), 1e-6)
        now = 20_000
        // A NaN after good readings keeps the last good value.
        assertEquals(0.5, cache.get(), 1e-6)
        assertEquals(3, reads)
    }

    @Test fun headroomUnsupportedWhenOnlyNaNOrZero() {
        val cache = ThermalPolicy.HeadroomCache(read = { 0f }, clock = { 0L })
        assertTrue(cache.get().isNaN())
        assertFalse(cache.supported)
    }
}

class MemoryReportTest {
    private fun snap(phase: String, total: Double) =
        MemoryReport.Snapshot(phase, total, 20.0, total - 60.0, 10.0, 20.0, 1.0, 5.0, 4.0, 5000.0, false)

    @Test fun leakVerdict() {
        val idle = snap("idle", 160.0)
        val first = snap("first unload", 210.0)
        assertTrue(MemoryReport.leakVerdict(idle, first, List(20) { snap("c$it", 220.0) }).passed)
        assertFalse(MemoryReport.leakVerdict(idle, first, List(20) { snap("c$it", 300.0) }).passed)
        // Within the margin at the end, but climbing 6 MB per cycle.
        assertFalse(MemoryReport.leakVerdict(idle, first, List(8) { snap("c$it", 210.0 + it * 6.0) }).passed)
        assertFalse(MemoryReport.leakVerdict(idle, first, emptyList()).passed)
    }

    /** The S23 Ultra GPU profile of 22 Nov 2026: a one-time 52 MB after the first load, then flat. Not a leak. */
    @Test fun s23UltraProfileIsNotALeak() {
        val v = MemoryReport.leakVerdict(snap("idle", 158.6), snap("after unload", 211.0),
            listOf(216.1, 224.7, 221.0, 213.8, 223.5).mapIndexed { i, t -> snap("c$i", t) })
        assertTrue(v.text, v.passed)
        assertEquals(52.4, v.oneTimeMb, 0.1)
        assertTrue(v.slopeMbPerCycle < 1.0)
    }

    @Test fun attributionAddsUp() {
        val s = snap("x", 500.0)
        assertEquals(0.0, s.unattributedMb, 1e-9)
        val j = JSONObject(MemoryReport.toJson(mapOf("kind" to "memory"), listOf(s), emptyList(), null))
        assertEquals(1, j.getJSONArray("phases").length())
        assertTrue(j.isNull("leak_verdict"))
    }
}
