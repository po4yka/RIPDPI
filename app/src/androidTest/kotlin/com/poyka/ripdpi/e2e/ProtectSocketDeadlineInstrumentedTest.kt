package com.poyka.ripdpi.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.services.ProtectSocketDeadlineProbe
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectSocketDeadlineInstrumentedTest {
    @Test
    fun silentClientsExpireAndTheNextDescriptorRequestIsHandled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = ProtectSocketDeadlineProbe().run(context.filesDir)

        assertEquals(listOf(-1, -1), result.silentClientResults)
        assertEquals("The real request reaches the intentional protection denial", 1, result.nextRequestAck)
    }
}
