package com.poyka.ripdpi.core.detection.vpn

import com.poyka.ripdpi.core.detection.VpnAppKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAppCatalogTest {
    @Test
    fun `v2rayNG found by package name`() {
        val sig = VpnAppCatalog.findByPackageName("com.v2ray.ang")
        assertNotNull(sig)
        assertEquals("v2rayNG", sig!!.appName)
        assertEquals(VpnAppKind.TARGETED_BYPASS, sig.kind)
    }

    @Test
    fun `unknown package returns null`() {
        assertNull(VpnAppCatalog.findByPackageName("com.unknown.app"))
    }

    @Test
    fun `families for port 1080 includes multiple families`() {
        val families = VpnAppCatalog.familiesForPort(1080)
        assertTrue(families.isNotEmpty())
        assertTrue(families.contains(VpnAppCatalog.FAMILY_XRAY))
    }

    @Test
    fun `localhost proxy ports are sorted`() {
        val ports = VpnAppCatalog.localhostProxyPorts
        assertEquals(ports, ports.sorted())
    }

    @Test
    fun `amnezia packages in catalog`() {
        assertNotNull(VpnAppCatalog.findByPackageName("org.amnezia.vpn"))
        assertNotNull(VpnAppCatalog.findByPackageName("org.amnezia.awg"))
    }

    @Test
    fun `all signatures have unique package names`() {
        val names = VpnAppCatalog.signatures.map { it.packageName }
        assertEquals(names.size, names.toSet().size)
    }
}
