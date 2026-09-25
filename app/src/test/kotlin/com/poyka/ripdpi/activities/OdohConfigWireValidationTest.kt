package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceCustomBytes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdohConfigWireValidationTest {
    private val valid =
        OdohResolverFields(
            proxyUrl = "https://proxy.example/dns-query",
            proxyOperatorId = "proxy",
            targetHost = "target.example",
            targetPath = "/",
            targetOperatorId = "target",
            configSource = EncryptedDnsOdohConfigSourceCustomBytes,
            configsHex = ValidOdohConfigsHex,
            configsRetrievedAtSecs = 1_000,
            configsTtlSecs = 60,
        )

    @Test
    fun `valid native config and root target path can be saved while fresh`() {
        assertTrue(valid.isValid(nowSecs = 1_059))
        assertFalse(valid.isValid(nowSecs = 1_060))
    }

    @Test
    fun `malformed length suite and incomplete bytes are rejected`() {
        assertFalse(valid.copy(configsHex = "0102").hasSupportedConfigWire())
        assertFalse(valid.copy(configsHex = "002b" + ValidOdohConfigsHex.drop(4)).hasSupportedConfigWire())
        assertFalse(valid.copy(configsHex = ValidOdohConfigsHex.replaceRange(12, 16, "0021")).hasSupportedConfigWire())
        assertFalse(valid.copy(configsHex = ValidOdohConfigsHex.dropLast(2)).hasSupportedConfigWire())
    }

    @Test
    fun `stale and overflowing lifetimes are rejected`() {
        assertFalse(valid.copy(configsRetrievedAtSecs = 1_000, configsTtlSecs = 1).isValid(nowSecs = 1_001))
        assertFalse(valid.copy(configsRetrievedAtSecs = Long.MAX_VALUE, configsTtlSecs = 2).isValid(nowSecs = 1_000))
    }
}
