package com.poyka.ripdpi.services

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServiceStartCommandTest {
    @Test
    fun `start cancelled before it acquires the mutex completes its generation`() =
        runTest {
            val entryMutex = Mutex(locked = true)
            var completions = 0
            var handled = false
            val command =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    runStartCommand(entryMutex, { false }, { completions++ }) { handled = true }
                }

            command.cancel()
            command.join()

            assertFalse(handled)
            assertEquals(1, completions)
        }

    @Test
    fun `start handed off to the shell is not completed here`() =
        runTest {
            var completions = 0

            runStartCommand(Mutex(), { true }, { completions++ }) {}

            assertEquals(0, completions)
        }

    @Test
    fun `failed start completes its generation`() =
        runTest {
            var completions = 0

            runCatching {
                runStartCommand(Mutex(), { false }, { completions++ }) { error("recovery failed") }
            }

            assertEquals(1, completions)
        }
}
