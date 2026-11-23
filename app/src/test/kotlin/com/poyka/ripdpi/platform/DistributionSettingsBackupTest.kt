package com.poyka.ripdpi.platform

import com.poyka.ripdpi.data.advancedTcpSignatureRequested
import com.poyka.ripdpi.data.appSettingsFromJson
import com.poyka.ripdpi.data.toJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DistributionSettingsBackupTest {
    @Test
    fun `only extended editions restore and export the optional packet setting`() {
        val settings = appSettingsFromJson("""{"fakeMd5Sig":true,"proxyPort":1234}""")
        val fields = Json.parseToJsonElement(settings.toJson()).jsonObject

        assertEquals(1234, settings.proxyPort)
        assertEquals(hasExtendedPacketControls, settings.advancedTcpSignatureRequested())
        assertEquals(
            true.takeIf { hasExtendedPacketControls },
            fields["fakeMd5Sig"]?.jsonPrimitive?.boolean,
        )
    }
}
