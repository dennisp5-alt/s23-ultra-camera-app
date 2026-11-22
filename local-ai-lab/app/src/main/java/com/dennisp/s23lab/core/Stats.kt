package com.dennisp.s23lab.core

import kotlin.math.sqrt

/** Small, dependency-free statistics used by the benchmark summaries. */
object Stats {
    fun median(values: List<Double>): Double = percentile(values, 50.0)

    /** Linear-interpolated percentile (0..100). Returns NaN for an empty list. */
    fun percentile(values: List<Double>, p: Double): Double {
        val v = values.filter { it.isFinite() }.sorted()
        if (v.isEmpty()) return Double.NaN
        if (v.size == 1) return v[0]
        val rank = (p.coerceIn(0.0, 100.0) / 100.0) * (v.size - 1)
        val lo = rank.toInt()
        val hi = minOf(lo + 1, v.size - 1)
        return v[lo] + (v[hi] - v[lo]) * (rank - lo)
    }

    fun mean(values: List<Double>): Double {
        val v = values.filter { it.isFinite() }
        return if (v.isEmpty()) Double.NaN else v.sum() / v.size
    }

    /** Sample standard deviation divided by the mean, as a fraction (0.05 = 5%). NaN below two values. */
    fun coefficientOfVariation(values: List<Double>): Double {
        val v = values.filter { it.isFinite() }
        if (v.size < 2) return Double.NaN
        val m = v.sum() / v.size
        if (m == 0.0) return Double.NaN
        val variance = v.sumOf { (it - m) * (it - m) } / (v.size - 1)
        return sqrt(variance) / m
    }

    /** Least-squares slope of y against its index (units of y per step). NaN below two values. */
    fun slope(values: List<Double>): Double {
        val v = values.filter { it.isFinite() }
        if (v.size < 2) return Double.NaN
        val n = v.size
        val xMean = (n - 1) / 2.0
        val yMean = v.sum() / n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (i - xMean) * (v[i] - yMean)
            den += (i - xMean) * (i - xMean)
        }
        return num / den
    }
}
