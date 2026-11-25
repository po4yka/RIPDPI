package com.poyka.ripdpi.core.detection.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class VpnInventoryBudget(
    private val context: CoroutineContext,
) {
    private val started = TimeSource.Monotonic.markNow()
    private var archives = 0

    var incomplete: Boolean = false
        private set

    fun markIncomplete() {
        incomplete = true
    }

    fun canContinue(): Boolean {
        context.ensureActive()
        val withinDeadline = started.elapsedNow() < ScanBudget
        if (!withinDeadline) markIncomplete()
        return withinDeadline
    }

    fun takeArchive(): Boolean {
        if (!canContinue()) return false
        val hasCapacity = archives < MaxArchives
        if (hasCapacity) {
            archives++
        } else {
            markIncomplete()
        }
        return hasCapacity
    }

    @Suppress("TooGenericExceptionCaught")
    fun <T> readPackageManager(read: () -> T): T? {
        if (!canContinue()) return null
        return try {
            read().also { canContinue() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            markIncomplete()
            null
        }
    }

    private companion object {
        val ScanBudget = 2.seconds
        const val MaxArchives = 128
    }
}
