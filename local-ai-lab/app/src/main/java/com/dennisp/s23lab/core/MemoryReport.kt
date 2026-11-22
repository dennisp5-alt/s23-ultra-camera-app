package com.dennisp.s23lab.core

/**
 * Phase-by-phase memory attribution. Figures come from Debug.MemoryInfo summary stats (KB converted to MB):
 * Java heap, native heap, graphics (GPU buffers the kernel attributes to the app), code, stack, other,
 * and total PSS. Some driver allocations may not appear in app-level figures at all; confirm with
 * `adb shell dumpsys meminfo com.dennisp.s23lab` and Perfetto.
 */
object MemoryReport {
    data class Snapshot(
        val phase: String,
        val totalPssMb: Double,
        val javaHeapMb: Double,
        val nativeHeapMb: Double,
        val graphicsMb: Double,
        val codeMb: Double,
        val stackMb: Double,
        val privateOtherMb: Double,
        val systemMb: Double,
        val availMemMb: Double,
        val lowMemory: Boolean,
    ) {
        /** The part of total PSS not covered by the named categories (normally small). */
        val unattributedMb: Double get() = totalPssMb - (javaHeapMb + nativeHeapMb + graphicsMb + codeMb + stackMb + privateOtherMb + systemMb)
    }

    /** Allowed rise from the first after-unload reading to the last cycle. */
    const val RETURN_MARGIN_MB = 50.0

    /** Allowed upward drift per load/unload cycle (least-squares slope). */
    const val DRIFT_PER_CYCLE_MB = 5.0

    data class LeakVerdict(
        val passed: Boolean,
        /** Memory kept after the first load and unload (native library and allocator state); reported, not judged. */
        val oneTimeMb: Double,
        val returnDeltaMb: Double,
        val slopeMbPerCycle: Double,
        val cycles: Int,
        val text: String,
    )

    /**
     * A leak is memory that keeps growing with each load/unload cycle. The first load leaves a one-time
     * amount behind (the loaded native library, allocator caches), so cycles are compared with the first
     * after-unload reading, not with idle before any load.
     */
    fun leakVerdict(idle: Snapshot, firstUnload: Snapshot, cycles: List<Snapshot>): LeakVerdict {
        val oneTime = firstUnload.totalPssMb - idle.totalPssMb
        if (cycles.isEmpty()) return LeakVerdict(false, oneTime, Double.NaN, Double.NaN, 0, "No cycles run.")
        val delta = cycles.last().totalPssMb - firstUnload.totalPssMb
        val slope = Stats.slope(cycles.map { it.totalPssMb })
        val passed = delta <= RETURN_MARGIN_MB && (!slope.isFinite() || slope <= DRIFT_PER_CYCLE_MB)
        val text = (if (passed) "Passed: " else "Failed: ") +
            "after ${cycles.size} cycles, memory is ${BenchProtocol.f0(delta)} MB above the first unload (limit ${RETURN_MARGIN_MB.toInt()}) " +
            "and drifts ${BenchProtocol.f1(slope)} MB per cycle (limit ${DRIFT_PER_CYCLE_MB.toInt()}). " +
            "One-time amount kept after the first load: ${BenchProtocol.f0(oneTime)} MB."
        return LeakVerdict(passed, oneTime, delta, slope, cycles.size, text)
    }

    fun toJson(meta: Map<String, Any?>, phases: List<Snapshot>, cycles: List<Snapshot>, verdict: LeakVerdict?): String =
        JsonOut.write(linkedMapOf(
            "meta" to meta,
            "phases" to phases.map(::row),
            "cycles" to cycles.map(::row),
            "leak_verdict" to verdict?.let {
                linkedMapOf("passed" to it.passed, "one_time_mb" to it.oneTimeMb, "return_delta_mb" to it.returnDeltaMb, "slope_mb_per_cycle" to it.slopeMbPerCycle, "cycles" to it.cycles, "text" to it.text)
            },
        ))

    private fun row(s: Snapshot) = linkedMapOf(
        "phase" to s.phase, "total_pss_mb" to s.totalPssMb, "java_heap_mb" to s.javaHeapMb, "native_heap_mb" to s.nativeHeapMb,
        "graphics_mb" to s.graphicsMb, "code_mb" to s.codeMb, "stack_mb" to s.stackMb, "private_other_mb" to s.privateOtherMb,
        "system_mb" to s.systemMb, "unattributed_mb" to s.unattributedMb, "avail_mem_mb" to s.availMemMb, "low_memory" to s.lowMemory,
    )

    fun line(s: Snapshot): String =
        s.phase + ": total " + BenchProtocol.f0(s.totalPssMb) + " MB = native " + BenchProtocol.f0(s.nativeHeapMb) +
            " + graphics " + BenchProtocol.f0(s.graphicsMb) + " + Java " + BenchProtocol.f0(s.javaHeapMb) +
            " + code " + BenchProtocol.f0(s.codeMb) + " + other " + BenchProtocol.f0(s.privateOtherMb + s.stackMb + s.systemMb + s.unattributedMb) +
            " | free RAM " + BenchProtocol.f0(s.availMemMb) + " MB"
}
