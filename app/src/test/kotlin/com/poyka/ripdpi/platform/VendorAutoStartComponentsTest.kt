package com.poyka.ripdpi.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VendorAutoStartComponentsTest {
    @Test
    fun `each known vendor maps to non-empty components`() {
        listOf("xiaomi", "huawei", "oppo", "vivo").forEach { manufacturer ->
            assertTrue(
                "expected non-empty mapping for $manufacturer",
                vendorAutoStartComponents(manufacturer).isNotEmpty(),
            )
        }
    }

    @Test
    fun `xiaomi maps to the MIUI autostart manager`() {
        val components = vendorAutoStartComponents("xiaomi")
        assertEquals(1, components.size)
        assertEquals("com.miui.securitycenter", components[0].packageName)
        assertEquals(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            components[0].className,
        )
    }

    @Test
    fun `huawei exposes both startup and protect activities`() {
        val classNames = vendorAutoStartComponents("huawei").map { it.className }
        assertTrue(classNames.any { it.endsWith("StartupNormalAppListActivity") })
        assertTrue(classNames.any { it.endsWith("ProtectActivity") })
    }

    @Test
    fun `oppo and vivo expose multiple vendor packages`() {
        assertTrue(vendorAutoStartComponents("oppo").size >= 2)
        assertTrue(vendorAutoStartComponents("vivo").size >= 2)
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(
            vendorAutoStartComponents("xiaomi"),
            vendorAutoStartComponents("XIAOMI"),
        )
    }

    @Test
    fun `unknown manufacturer maps to empty list`() {
        assertTrue(vendorAutoStartComponents("pixel").isEmpty())
        assertTrue(vendorAutoStartComponents("").isEmpty())
    }
}
