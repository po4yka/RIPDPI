package com.poyka.ripdpi.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnConsentUiSelectorTest {
    @Test
    fun `ordered dialog packages keep defaults first and de-duplicate hints`() {
        assertEquals(
            listOf(
                "com.android.vpndialogs",
                "com.android.permissioncontroller",
                "com.vendor.vpndialog",
            ),
            VpnConsentUiSelector.orderedDialogPackages(
                listOf(
                    "com.vendor.vpndialog",
                    "com.android.permissioncontroller",
                    " ",
                ),
            ),
        )
    }

    @Test
    fun `known positive button prefers resource order within active dialog package`() {
        assertEquals(
            VpnConsentUiCandidate(
                packageName = "com.android.permissioncontroller",
                resourceName = "android:id/button1",
            ),
            VpnConsentUiSelector.selectKnownPositiveButton(
                activeDialogPackage = "com.android.permissioncontroller",
                candidates =
                    listOf(
                        VpnConsentUiCandidate(
                            packageName = "com.android.permissioncontroller",
                            resourceName = "com.android.permissioncontroller:id/permission_allow_button",
                        ),
                        VpnConsentUiCandidate(
                            packageName = "com.android.permissioncontroller",
                            resourceName = "android:id/button1",
                        ),
                        VpnConsentUiCandidate(
                            packageName = "com.android.vpndialogs",
                            resourceName = "android:id/button1",
                        ),
                    ),
            ),
        )
    }

    @Test
    fun `fallback positive button picks bottom-most then right-most clickable candidate in active package`() {
        assertEquals(
            VpnConsentUiCandidate(
                packageName = "com.android.vpndialogs",
                bottom = 110,
                right = 95,
            ),
            VpnConsentUiSelector.selectFallbackPositiveButton(
                activeDialogPackage = "com.android.vpndialogs",
                candidates =
                    listOf(
                        VpnConsentUiCandidate(
                            packageName = "com.android.vpndialogs",
                            bottom = 100,
                            right = 150,
                        ),
                        VpnConsentUiCandidate(
                            packageName = "com.android.vpndialogs",
                            bottom = 110,
                            right = 95,
                        ),
                        VpnConsentUiCandidate(
                            packageName = "com.android.vpndialogs",
                            clickable = false,
                            bottom = 140,
                            right = 200,
                        ),
                        VpnConsentUiCandidate(
                            packageName = "com.other.package",
                            bottom = 400,
                            right = 400,
                        ),
                    ),
            ),
        )
    }

    @Test
    fun `fallback positive button returns null without active dialog package`() {
        assertNull(
            VpnConsentUiSelector.selectFallbackPositiveButton(
                activeDialogPackage = null,
                candidates =
                    listOf(
                        VpnConsentUiCandidate(
                            packageName = "com.android.vpndialogs",
                            bottom = 100,
                            right = 100,
                        ),
                    ),
            ),
        )
    }
}
