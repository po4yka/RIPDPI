package com.poyka.ripdpi.proxyimport

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity
import java.net.URLEncoder

/**
 * Robolectric tests for [ImportHandlerActivity]: given an inbound VIEW intent, the
 * activity classifies it and either hands a typed import payload onward to
 * [com.poyka.ripdpi.activities.MainActivity] (via a launch-route extra) or, for
 * malformed input, finishes without crashing while surfacing a typed error.
 */
@RunWith(RobolectricTestRunner::class)
class ImportHandlerActivityTest {
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun launch(uri: String): ActivityController<ImportHandlerActivity> {
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(uri),
                ApplicationProvider.getApplicationContext(),
                ImportHandlerActivity::class.java,
            )
        return Robolectric.buildActivity(ImportHandlerActivity::class.java, intent)
    }

    private fun shadowOf(activity: ImportHandlerActivity): ShadowActivity = org.robolectric.Shadows.shadowOf(activity)

    @Test
    fun `proxy uri share link forwards a profile import route to main activity`() {
        val controller =
            launch("vless://11111111-2222-3333-4444-555555555555@example.com:443#Edge").create()

        val activity = controller.get()
        val forwarded = shadowOf(activity).nextStartedActivity

        assertEquals(
            com.poyka.ripdpi.activities.MainActivity::class.java.name,
            forwarded.component?.className,
        )
        assertEquals(
            ImportLaunchRoute.PROFILE_CONFIRM,
            forwarded.getStringExtra(ImportHandlerActivity.EXTRA_IMPORT_ROUTE),
        )
        assertTrue(activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun `singbox deep link forwards a subscription import route to main activity`() {
        val link =
            "singbox://import-remote-profile?url=${enc("https://sub.example.com/c")}" +
                "&name=${enc("Fleet")}"
        val controller = launch(link).create()

        val activity = controller.get()
        val forwarded = shadowOf(activity).nextStartedActivity

        assertEquals(
            ImportLaunchRoute.SUBSCRIPTION_CONFIRM,
            forwarded.getStringExtra(ImportHandlerActivity.EXTRA_IMPORT_ROUTE),
        )
        assertEquals(
            "https://sub.example.com/c",
            forwarded.getStringExtra(ImportHandlerActivity.EXTRA_SUBSCRIPTION_URL),
        )
        assertEquals("Fleet", forwarded.getStringExtra(ImportHandlerActivity.EXTRA_SUBSCRIPTION_NAME))
        assertTrue(activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun `bootstrap deep link forwards the bootstrap flag`() {
        val link =
            "singbox://import-remote-profile?url=" +
                enc("https://sub.example.com/bootstrap/token-abc")
        val controller = launch(link).create()

        val forwarded = shadowOf(controller.get()).nextStartedActivity

        assertTrue(forwarded.getBooleanExtra(ImportHandlerActivity.EXTRA_SUBSCRIPTION_BOOTSTRAP, false))
        controller.destroy()
    }

    @Test
    fun `malformed deep link finishes without forwarding and without crashing`() {
        val controller = launch("singbox://import-remote-profile").create()

        val activity = controller.get()
        val forwarded = shadowOf(activity).nextStartedActivity

        assertNull("No forward intent expected for malformed deep link", forwarded)
        assertTrue(activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun `unsupported proxy scheme finishes without forwarding`() {
        val controller = launch("gopher://example.com:70").create()

        val activity = controller.get()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
        controller.destroy()
    }
}
