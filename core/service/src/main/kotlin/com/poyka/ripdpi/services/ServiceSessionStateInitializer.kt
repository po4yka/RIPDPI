package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/** Owns the generation-bound state writer for exactly one service component. */
@ServiceSessionScope
internal class ServiceSessionStateInitializer
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
    ) : AutoCloseable {
        private val sessionStateStore = AtomicReference<ServiceStateStore?>()
        private var closed = false

        fun initialize(mode: Mode): ServiceStateStore =
            synchronized(this) {
                check(!closed) { "A closed service session cannot be initialized again" }
                sessionStateStore.get()
                    ?: serviceStateStore.beginSession(mode).also(sessionStateStore::set)
            }

        fun requireInitialized(): ServiceStateStore =
            synchronized(this) {
                check(!closed) { "Service session state has already been closed" }
                checkNotNull(sessionStateStore.get()) {
                    "Service session state must be initialized before constructing the runtime graph"
                }
            }

        override fun close() = closeSession(sender = null, reason = null)

        fun close(
            sender: Sender,
            reason: FailureReason,
        ) = closeSession(sender, reason)

        private fun closeSession(
            sender: Sender?,
            reason: FailureReason?,
        ) {
            val session =
                synchronized(this) {
                    if (closed) return
                    closed = true
                    sessionStateStore.getAndSet(null)
                }
            session?.endSession(sender, reason)
        }
    }
