package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportPolicyFamilyNormalizationTest {
    @Test
    fun `normalizeStrategyFamilyToTcpFamily preserves transparent tls family labels`() {
        assertEquals(TcpFamily.SEG_PRE_SNI, normalizeStrategyFamilyToTcpFamily("seg_pre_sni"))
        assertEquals(TcpFamily.SEG_MID_SNI, normalizeStrategyFamilyToTcpFamily("seg_mid_sni"))
        assertEquals(TcpFamily.REC_PRE_SNI, normalizeStrategyFamilyToTcpFamily("rec_pre_sni"))
        assertEquals(TcpFamily.REC_MID_SNI, normalizeStrategyFamilyToTcpFamily("rec_mid_sni"))
    }
}
