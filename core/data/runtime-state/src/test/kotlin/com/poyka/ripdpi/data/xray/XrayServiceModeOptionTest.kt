package com.poyka.ripdpi.data.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coverage for the service-mode / provider selection model. */
class XrayServiceModeOptionTest {
    @Test
    fun xrayOptionRequiresProfileAndNativeDoesNot() {
        assertTrue(XrayServiceModeOption.XrayVpn.requiresXrayProfile)
        assertFalse(XrayServiceModeOption.NativeDirect.requiresXrayProfile)
        assertFalse(XrayServiceModeOption.NativeProxy.requiresXrayProfile)
    }

    @Test
    fun optionsMapToExpectedProviderKinds() {
        assertEquals(VpnProviderKind.Native, XrayServiceModeOption.NativeDirect.providerKind)
        assertEquals(VpnProviderKind.Native, XrayServiceModeOption.NativeProxy.providerKind)
        assertEquals(VpnProviderKind.Xray, XrayServiceModeOption.XrayVpn.providerKind)
    }

    @Test
    fun defaultIsNativeDirectAndAllListsEveryOption() {
        assertEquals(XrayServiceModeOption.NativeDirect, XrayServiceModeOption.default)
        assertEquals(XrayServiceModeOption.entries.size, XrayServiceModeOption.all.size)
    }

    @Test
    fun stringKeysAreNonBlankAndDistinct() {
        val titleKeys = XrayServiceModeOption.all.map { it.titleKey }
        assertEquals(titleKeys.size, titleKeys.toSet().size)
        assertTrue(XrayServiceModeOption.all.all { it.titleKey.isNotBlank() && it.descriptionKey.isNotBlank() })
    }
}
