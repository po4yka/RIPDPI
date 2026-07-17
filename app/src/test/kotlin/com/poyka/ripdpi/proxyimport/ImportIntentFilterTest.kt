package com.poyka.ripdpi.proxyimport

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val isFullExperience: Boolean
        get() = BuildConfig.APP_EXPERIENCE == "full"

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
        val matches = matchingActivityNames(uri)
        return ImportHandlerActivity::class.java.name in matches
    }

    private fun matchingActivityNames(uri: String): Set<String> =
        packageManager
            .queryIntentActivities(viewIntent(uri), 0)
            .filter { resolveInfo -> resolveInfo.activityInfo?.packageName == packageName }
            .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.name }
            .toSet()

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
            assertEquals(
                "Import handling must match the experience contract for $uri",
                isFullExperience,
                resolvesToImportHandler(uri),
            )
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
        assertEquals(
            isFullExperience,
            resolvesToImportHandler("singbox://import-remote-profile?url=https%3A%2F%2Fx.example"),
        )
    }

    @Test
    fun `ripdpi and sn deep links resolve to the import handler`() {
        assertEquals(
            isFullExperience,
            resolvesToImportHandler("ripdpi://import-remote-profile?url=https%3A%2F%2Fx.example"),
        )
        assertEquals(
            isFullExperience,
            resolvesToImportHandler("sn://import-remote-profile?url=https%3A%2F%2Fx.example"),
        )
    }

    @Test
    fun `ripdpi import deep link with sub parameter resolves to the import handler`() {
        assertEquals(
            isFullExperience,
            resolvesToImportHandler("ripdpi://import?sub=https%3A%2F%2Fhost.example%2Fsub%2Ftok"),
        )
    }

    @Test
    fun `ripdpi import deep link with url parameter resolves to the import handler`() {
        assertEquals(
            isFullExperience,
            resolvesToImportHandler("ripdpi://import?url=https%3A%2F%2Fhost.example%2Fbundle.json"),
        )
    }

    @Test
    fun `ripdpi import deep links are not claimed by main activity catch all filter`() {
        val matches = matchingActivityNames("ripdpi://import?url=https%3A%2F%2Fhost.example%2Fbundle.json")

        assertEquals(
            if (isFullExperience) setOf(ImportHandlerActivity::class.java.name) else emptySet<String>(),
            matches,
        )
        assertFalse(MainActivity::class.java.name in matches)
    }

    @Test
    fun `ripdpi app navigation deep links stay on main activity`() {
        listOf("connect", "config", "diagnostics", "disconnect", "settings", "support-config").forEach { host ->
            val matches = matchingActivityNames("ripdpi://$host")

            assertEquals(
                "Navigation deep links must only be exposed by the full experience: $host",
                isFullExperience,
                MainActivity::class.java.name in matches,
            )
            assertFalse(
                "Import handler must not claim ripdpi://$host",
                ImportHandlerActivity::class.java.name in matches,
            )
        }
    }

    @Test
    fun `web share and support links are only exposed by the full experience`() {
        listOf(
            "https://po4yka.github.io/RIPDPI/share",
            "https://po4yka.github.io/RIPDPI/support-config",
        ).forEach { uri ->
            assertEquals(
                "Web deep links must match the experience contract for $uri",
                isFullExperience,
                MainActivity::class.java.name in matchingActivityNames(uri),
            )
        }
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
        val component = android.content.ComponentName(packageName, ImportHandlerActivity::class.java.name)
        if (isFullExperience) {
            assertTrue(
                "ImportHandlerActivity must be exported",
                packageManager.getActivityInfo(component, 0).exported,
            )
        } else {
            runCatching { packageManager.getActivityInfo(component, 0) }
                .onSuccess { throw AssertionError("Simple experience must not declare ImportHandlerActivity") }
        }
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
        if (isFullExperience) {
            assertTrue("Expected a resolved filter for the singbox deep link", priorities.isNotEmpty())
            assertTrue(
                "RIPDPI singbox filter priority ${priorities.first()} must be < 0 (SFA default)",
                priorities.all { it < ImportManifestPriority.SING_BOX_FOR_ANDROID_DEFAULT },
            )
        } else {
            assertTrue("Simple experience must not expose singbox import filters", priorities.isEmpty())
        }
    }
}
