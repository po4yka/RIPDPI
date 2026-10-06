package com.poyka.ripdpi.data

import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Shared short gate for checked intent transitions and synchronous runtime publication. */
@Singleton
class RuntimeIntentLinearizer
    @Inject
    constructor() {
        private val gate = ReentrantLock()

        fun <T> serialize(action: () -> T): T = gate.withLock(action)

        /** The action may only publish registry and in-memory status; never await, ACK, or call data/native APIs. */
        fun publishIf(
            isCurrent: () -> Boolean,
            publish: () -> Unit,
        ): Boolean =
            gate.withLock {
                if (!isCurrent()) return@withLock false
                publish()
                true
            }
    }

/** Produced only after a matching durable ACK; it cannot be fabricated or copied by service callers. */
class RuntimePublicationPermit internal constructor(
    val authority: PauseAuthorityRef,
    val mode: Mode,
    val commandId: String,
    val appliedIdentity: RuntimeAppliedUseIdentity,
)
