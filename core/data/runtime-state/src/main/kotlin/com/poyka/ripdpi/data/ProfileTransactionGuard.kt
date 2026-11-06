package com.poyka.ripdpi.data

/** A synchronous command guard acquired before a database transaction on an IO thread. */
interface ProfileTransactionGuard {
    fun runIfCurrent(action: () -> Unit): Boolean
}
