package com.poyka.ripdpi.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles one start command under [entryMutex]. [completeStart] runs unless [consumeShellStartHandoff], read under the
 * mutex, reports that the shell took the start over; it also runs when the command is cancelled while it waits for the
 * mutex, or a pending start would block DoQ saves for the process lifetime.
 */
internal suspend fun runStartCommand(
    entryMutex: Mutex,
    consumeShellStartHandoff: () -> Boolean,
    completeStart: () -> Unit,
    handle: suspend () -> Unit,
) {
    var handedOff = false
    try {
        entryMutex.withLock {
            try {
                handle()
            } finally {
                handedOff = consumeShellStartHandoff()
            }
        }
    } finally {
        if (!handedOff) completeStart()
    }
}
