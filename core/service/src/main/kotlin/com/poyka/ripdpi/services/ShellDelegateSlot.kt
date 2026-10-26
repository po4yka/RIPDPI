package com.poyka.ripdpi.services

/**
 * Holds the shell delegate of the current attach. onRevoke runs on the main thread while an attach
 * builds the delegate on IO, so a revoke that arrives before the delegate exists is kept and
 * delivered to it. A released delegate is dropped, so a re-attach after a pause never routes a
 * revoke to the closed delegate of the previous attach.
 */
internal class ShellDelegateSlot<T : Any>(
    private val deliverRevoke: (T) -> Unit,
) {
    private var delegate: T? = null
    private var pendingRevoke = false

    val current: T?
        @Synchronized get() = delegate

    /** Drops a revoke kept by an earlier attach that failed before its delegate existed. */
    @Synchronized
    fun discardPendingRevoke() {
        pendingRevoke = false
    }

    fun install(created: T) {
        val deliver =
            synchronized(this) {
                delegate = created
                pendingRevoke.also { pendingRevoke = false }
            }
        if (deliver) deliverRevoke(created)
    }

    /** Detaches the delegate for closing; revokes until the next [install] are kept. */
    @Synchronized
    fun release(): T? = delegate.also { delegate = null }

    fun onRevoke() {
        val target = synchronized(this) { delegate ?: null.also { pendingRevoke = true } }
        target?.let(deliverRevoke)
    }
}
