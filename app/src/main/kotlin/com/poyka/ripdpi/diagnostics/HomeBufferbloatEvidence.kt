package com.poyka.ripdpi.diagnostics

private const val GradeAMaxDelta = 5
private const val GradeBMaxDelta = 30
private const val GradeCMaxDelta = 100
private const val GradeDMaxDelta = 250

internal data class HomeRttSample(
    val startedNs: Long,
    val finishedNs: Long,
    val elapsedMs: Long,
)

internal data class HomeLoadEvidence(
    val successful: Boolean,
    val bytesRead: Long,
    val firstByteNs: Long?,
    val lastByteNs: Long?,
)

internal fun homeBufferbloatResult(
    idle: List<HomeRttSample>,
    loaded: List<HomeRttSample>,
    load: HomeLoadEvidence,
): HomeBufferbloatResult {
    val firstByte = load.firstByteNs
    val lastByte = load.lastByteNs
    val overlapping =
        if (load.successful && load.bytesRead > 0 && firstByte != null && lastByte != null && lastByte > firstByte) {
            loaded.filter { it.startedNs >= firstByte && it.finishedNs <= lastByte }
        } else {
            emptyList()
        }
    val idleMs =
        idle
            .takeIf { it.isNotEmpty() }
            ?.map { it.elapsedMs }
            ?.average()
            ?.toInt()
    val loadedMs =
        overlapping
            .takeIf { it.isNotEmpty() }
            ?.map { it.elapsedMs }
            ?.average()
            ?.toInt()
    val deltaMs = if (idleMs != null && loadedMs != null) loadedMs - idleMs else null
    val grade =
        when {
            deltaMs == null -> HomeBufferbloatGrade.UNKNOWN
            deltaMs <= GradeAMaxDelta -> HomeBufferbloatGrade.A
            deltaMs <= GradeBMaxDelta -> HomeBufferbloatGrade.B
            deltaMs <= GradeCMaxDelta -> HomeBufferbloatGrade.C
            deltaMs <= GradeDMaxDelta -> HomeBufferbloatGrade.D
            else -> HomeBufferbloatGrade.F
        }
    return HomeBufferbloatResult(grade = grade, idleRttMs = idleMs, loadedRttMs = loadedMs, deltaMs = deltaMs)
}
