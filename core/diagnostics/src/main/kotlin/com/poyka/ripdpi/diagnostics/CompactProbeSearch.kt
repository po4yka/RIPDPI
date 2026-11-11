package com.poyka.ripdpi.diagnostics

import kotlin.math.exp
import kotlin.random.Random

internal data class CompactProbe(
    val id: String,
    val signals: Set<String>,
    val cost: Double,
    val mandatory: Boolean,
)

/** Bounded set-cover search. Scores and coverage come from prior observations only. */
internal object CompactProbeSearch {
    private const val Iterations = 512
    private const val MaxTargets = 32
    private const val ConstraintPenalty = 1_000.0
    private const val CostWeight = 0.01
    private const val CoolingRate = 0.99
    private const val InitialTemperature = 0.5

    fun select(
        probes: List<CompactProbe>,
        limit: Int,
        seed: Int,
        minimum: Int = 1,
    ): Set<String>? {
        require(limit > 0 && minimum in 1..limit)
        require(probes.map(CompactProbe::id).distinct().size == probes.size)
        require(probes.all { it.cost.isFinite() && it.cost > 0.0 })
        if (probes.isEmpty() || probes.size > MaxTargets) return null
        val required = probes.flatMap(CompactProbe::signals).toSet()
        val maxCost = probes.maxOf(CompactProbe::cost)
        val costs = probes.map { it.cost / maxCost }
        val scale = costs.sum()

        fun energy(selected: Set<Int>): Double {
            val covered = selected.flatMap { probes[it].signals }.toSet()
            val missing = (required - covered).size + probes.indices.count { probes[it].mandatory && it !in selected }
            val excess = (selected.size - limit).coerceAtLeast(0) + (minimum - selected.size).coerceAtLeast(0)
            return (missing + excess) * ConstraintPenalty + selected.size +
                CostWeight * selected.sumOf { costs[it] } / scale
        }
        var current = probes.indices.toSet()
        var currentEnergy = energy(current)
        var best = current.takeIf { currentEnergy < ConstraintPenalty }
        var bestEnergy = if (best == null) Double.POSITIVE_INFINITY else currentEnergy
        val random = Random(seed)
        var temperature = InitialTemperature
        repeat(Iterations) {
            val neighbor = current.toMutableSet()
            toggle(neighbor, random.nextInt(probes.size))
            if (random.nextBoolean()) toggle(neighbor, random.nextInt(probes.size))
            val candidateEnergy = energy(neighbor)
            val difference = candidateEnergy - currentEnergy
            if (difference <= 0.0 || random.nextDouble() < exp(-difference / temperature)) {
                current = neighbor
                currentEnergy = candidateEnergy
            }
            if (candidateEnergy < ConstraintPenalty && candidateEnergy < bestEnergy) {
                best = neighbor.toSet()
                bestEnergy = candidateEnergy
            }
            temperature *= CoolingRate
        }
        return best?.mapTo(linkedSetOf()) { probes[it].id }
    }

    private fun toggle(
        selected: MutableSet<Int>,
        index: Int,
    ) {
        if (!selected.remove(index)) selected.add(index)
    }
}
