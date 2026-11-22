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

    /** Allowed difference between idle and after-unload total PSS. */
    const val RETURN_MARGIN_MB = 50.0

    /** Allowed upward drift per load/unload cycle (least-squares slope). */
    const val DRIFT_PER_CYCLE_MB = 5.0

    data class LeakVerdict(val passed: Boolean, val returnDeltaMb: Double, val slopeMbPerCycle: Double, val cycles: Int, val text: String)

    fun leakVerdict(idle: Snapshot, afterUnload: List<Snapshot>): LeakVerdict {
        if (afterUnload.isEmpty()) return LeakVerdict(false, Double.NaN, Double.NaN, 0, "No cycles run.")
        val delta = afterUnload.last().totalPssMb - idle.totalPssMb
        val slope = Stats.slope(afterUnload.map { it.totalPssMb })
        val returns = delta <= RETURN_MARGIN_MB
        val flat = !slope.isFinite() || slope <= DRIFT_PER_CYCLE_MB
        val passed = returns && flat
        val text = (if (passed) "Passed: " else "Failed: ") +
            "after ${afterUnload.size} cycles, memory is ${BenchProtocol.f0(delta)} MB above idle (limit ${RETURN_MARGIN_MB.toInt()}) " +
            "and drifts ${BenchProtocol.f1(slope)} MB per cycle (limit ${DRIFT_PER_CYCLE_MB.toInt()})."
        return LeakVerdict(passed, delta, slope, afterUnload.size, text)
    }

    fun toJson(meta: Map<String, Any?>, phases: List<Snapshot>, cycles: List<Snapshot>, verdict: LeakVerdict?): String =
        JsonOut.write(linkedMapOf(
            "meta" to meta,
            "phases" to phases.map(::row),
            "cycles" to cycles.map(::row),
            "leak_verdict" to verdict?.let {
                linkedMapOf("passed" to it.passed, "return_delta_mb" to it.returnDeltaMb, "slope_mb_per_cycle" to it.slopeMbPerCycle, "cycles" to it.cycles, "text" to it.text)
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
