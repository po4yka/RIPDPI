package com.poyka.ripdpi.services.testsupport

import com.poyka.ripdpi.services.TestProxyRuntime
import com.poyka.ripdpi.services.TestRelayRuntime
import com.poyka.ripdpi.services.TestWarpRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface ScriptedSupervisorExit {
    data class Crash(
        val code: Int,
    ) : ScriptedSupervisorExit
}

internal class ScriptedSupervisorExitSequence(
    vararg exits: ScriptedSupervisorExit,
) {
    private val pending = ArrayDeque(exits.toList())

    fun applyTo(runtime: TestProxyRuntime) {
        when (val exit = requireNextExit()) {
            is ScriptedSupervisorExit.Crash -> runtime.complete(exit.code)
        }
    }

    fun applyTo(runtime: TestRelayRuntime) {
        when (val exit = requireNextExit()) {
            is ScriptedSupervisorExit.Crash -> runtime.complete(exit.code)
        }
    }

    fun applyTo(runtime: TestWarpRuntime) {
        when (val exit = requireNextExit()) {
            is ScriptedSupervisorExit.Crash -> runtime.complete(exit.code)
        }
    }

    private fun requireNextExit(): ScriptedSupervisorExit =
        pending.removeFirstOrNull() ?: error("No scripted supervisor exit remains")
}

internal class HarnessStallGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun stall() {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "Harness stall timed out" }
    }

    fun awaitEntered(): Boolean = entered.await(5, TimeUnit.SECONDS)

    fun release() {
        released.countDown()
    }
}

internal class OverlapTracker {
    private val active = AtomicInteger(0)
    private val peak = AtomicInteger(0)

    fun begin() {
        val current = active.incrementAndGet()
        peak.updateAndGet { maxOf(it, current) }
    }

    fun end() {
        active.decrementAndGet()
    }

    val maxConcurrent: Int
        get() = peak.get()
}
