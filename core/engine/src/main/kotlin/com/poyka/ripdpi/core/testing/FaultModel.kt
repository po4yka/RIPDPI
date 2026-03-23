package com.poyka.ripdpi.core.testing

import java.io.IOException
import java.util.ArrayDeque

enum class FaultScope {
    ONE_SHOT,
    PERSISTENT,
}

enum class FaultOutcome {
    EXCEPTION,
    TIMEOUT,
    DROP,
    RESET,
    MALFORMED_PAYLOAD,
    BLANK_PAYLOAD,
    PANIC,
}

data class FaultSpec<T>(
    val target: T,
    val outcome: FaultOutcome,
    val scope: FaultScope = FaultScope.ONE_SHOT,
    val message: String? = null,
    val payload: String? = null,
)

class FaultQueue<T> {
    private val faults = ArrayDeque<FaultSpec<T>>()

    @Synchronized
    fun enqueue(spec: FaultSpec<T>) {
        faults.addLast(spec)
    }

    @Synchronized
    fun clear() {
        faults.clear()
    }

    @Synchronized
    fun next(target: T): FaultSpec<T>? {
        val iterator = faults.iterator()
        while (iterator.hasNext()) {
            val spec = iterator.next()
            if (spec.target == target) {
                if (spec.scope == FaultScope.ONE_SHOT) {
                    iterator.remove()
                }
                return spec
            }
        }
        return null
    }

    @Synchronized
    fun snapshot(): List<FaultSpec<T>> = faults.toList()
}

fun faultThrowable(
    outcome: FaultOutcome,
    message: String? = null,
): Throwable =
    when (outcome) {
        FaultOutcome.EXCEPTION -> IOException(message ?: "fault injected exception")

        FaultOutcome.TIMEOUT -> IOException(message ?: "fault injected timeout")

        FaultOutcome.DROP -> IOException(message ?: "fault injected drop")

        FaultOutcome.RESET -> IOException(message ?: "fault injected reset")

        FaultOutcome.PANIC -> IllegalStateException(message ?: "fault injected panic")

        FaultOutcome.MALFORMED_PAYLOAD,
        FaultOutcome.BLANK_PAYLOAD,
        -> IllegalArgumentException("Payload fault $outcome does not map to a throwable")
    }
