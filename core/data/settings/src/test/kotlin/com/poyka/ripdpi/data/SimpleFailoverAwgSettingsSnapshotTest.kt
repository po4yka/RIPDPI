package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleFailoverAwgSettingsSnapshotTest {
    @Test
    fun `simple failover AWG profile id round-trips through settings json`() {
        val settings =
            AppSettingsSerializer
                .defaultValue
                .toBuilder()
                .setRelayEnabled(false)
                .setSimpleFailoverAwgProfileId("awg-persisted")
                .build()

        val restored = appSettingsFromJson(settings.toJson())

        assertEquals("awg-persisted", restored.simpleFailoverAwgProfileId)
        assertEquals(false, restored.relayEnabled)
    }
}
