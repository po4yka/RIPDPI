package com.poyka.ripdpi.proxyimport

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric resolution tests over the merged [AndroidManifest]. These exercise the
 * static intent-filter declarations the share-sheet / deep-link tasks add: each proxy
 * URI scheme and the `singbox://import-remote-profile` deep link must resolve to
 * [ImportHandlerActivity]. An instrumented test on a device would be ideal but the
 * emulator path is unavailable here, so manifest resolution is asserted via the
 * Robolectric [PackageManager], which still parses the real merged manifest.
 */
@RunWith(RobolectricTestRunner::class)
class ImportIntentFilterTest {
    private val packageManager: PackageManager
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    private val packageName: String
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().packageName

    private fun viewIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

    private fun resolvesToImportHandler(uri: String): Boolean {
        val matches = packageManager.queryIntentActivities(viewIntent(uri), 0)
        return matches.any { resolveInfo ->
            resolveInfo.activityInfo?.packageName == packageName &&
                resolveInfo.activityInfo?.name == ImportHandlerActivity::class.java.name
        }
    }

    @Test
    fun `each single-profile proxy scheme resolves to the import handler`() {
        val schemes =
            listOf(
                "vless://uuid@example.com:443",
                "trojan://pass@example.com:443",
                "ss://YWVzOnB3@example.com:8388",
                "hysteria2://pass@example.com:443",
                "tuic://uuid:pass@example.com:443",
                "anytls://pass@example.com:443",
                "ssh://user@example.com:22",
            )
        schemes.forEach { uri ->
            assertTrue("Expected $uri to resolve to ImportHandlerActivity", resolvesToImportHandler(uri))
        }
    }

    @Test
    fun `removed legacy schemes are no longer claimed by the import handler`() {
        // VMess and Hysteria-v1 were removed; their schemes must not resolve so the
        // OS no longer offers RIPDPI for those links.
        listOf(
            "vmess://eyJhZGQiOiJleGFtcGxlLmNvbSJ9",
            "hysteria://pass@example.com:443",
        ).forEach { uri ->
            assertTrue("Expected $uri NOT to resolve to ImportHandlerActivity", !resolvesToImportHandler(uri))
        }
    }

    @Test
    fun `singbox deep link resolves to the import handler`() {
        assertTrue(
            resolvesToImportHandler("singbox://import-remote-profile?url=https%3A%2F%2Fx.example"),
        )
    }

    @Test
    fun `ripdpi and sn deep links resolve to the import handler`() {
        assertTrue(resolvesToImportHandler("ripdpi://import-remote-profile?url=https%3A%2F%2Fx.example"))
        assertTrue(resolvesToImportHandler("sn://import-remote-profile?url=https%3A%2F%2Fx.example"))
    }

    @Test
    fun `ripdpi import deep link with sub parameter resolves to the import handler`() {
        assertTrue(resolvesToImportHandler("ripdpi://import?sub=https%3A%2F%2Fhost.example%2Fsub%2Ftok"))
    }

    @Test
    fun `ripdpi import deep link with url parameter resolves to the import handler`() {
        assertTrue(resolvesToImportHandler("ripdpi://import?url=https%3A%2F%2Fhost.example%2Fbundle.json"))
    }

    @Test
    fun `https is not claimed by the import handler`() {
        // Browser ordering for plain web links must stay untouched.
        val matches = packageManager.queryIntentActivities(viewIntent("https://example.com/page"), 0)
        val claimed =
            matches.any { resolveInfo ->
                resolveInfo.activityInfo?.packageName == packageName &&
                    resolveInfo.activityInfo?.name == ImportHandlerActivity::class.java.name
            }
        assertTrue("ImportHandlerActivity must not claim https://", !claimed)
    }

    @Test
    fun `import handler is exported so the share sheet can reach it`() {
        val activityInfo =
            packageManager.getActivityInfo(
                android.content.ComponentName(packageName, ImportHandlerActivity::class.java.name),
                0,
            )
        assertTrue("ImportHandlerActivity must be exported", activityInfo.exported)
    }

    @Test
    fun `singbox deep-link filter priority stays below the sing-box-for-android default`() {
        // sing-box-for-Android declares its import-remote-profile filter at the platform
        // default priority (0). RIPDPI must sit strictly below it so SFA stays the user's
        // default when installed; RIPDPI is still offered in the chooser.
        val priorities =
            packageManager
                .queryIntentActivities(
                    viewIntent("singbox://import-remote-profile?url=https%3A%2F%2Fx.example"),
                    PackageManager.GET_RESOLVED_FILTER,
                ).filter { resolveInfo ->
                    resolveInfo.activityInfo?.packageName == packageName &&
                        resolveInfo.activityInfo?.name == ImportHandlerActivity::class.java.name
                }.mapNotNull { it.filter?.priority }
        assertTrue("Expected a resolved filter for the singbox deep link", priorities.isNotEmpty())
        assertTrue(
            "RIPDPI singbox filter priority ${priorities.first()} must be < 0 (SFA default)",
            priorities.all { it < ImportManifestPriority.SING_BOX_FOR_ANDROID_DEFAULT },
        )
    }
}
