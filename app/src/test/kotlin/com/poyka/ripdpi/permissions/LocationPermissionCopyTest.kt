package com.poyka.ripdpi.permissions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocationPermissionCopyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun detectionPermissionCopyExplainsAndroidLocationLabelAndPrivacyBoundaries() {
        val copy =
            listOf(
                context.getString(R.string.detection_onboarding_body),
                context.getString(R.string.detection_permission_rationale),
                context.getString(R.string.detection_permission_settings),
                context.getString(R.string.home_diagnostics_detection_permission_required),
            ).joinToString(separator = " ")

        assertContains(copy, "Android")
        assertContains(copy, "Wi-Fi")
        assertContains(copy, "cellular")
        assertContains(copy, "tracking")
        assertContains(copy, "raw identifiers")
        assertContains(copy, "diagnostics")
        assertContains(copy, "redact")
    }

    @Test
    fun masqueGeohashCopyExplainsCoarseLocationWithoutPersistence() {
        val copy = context.getString(R.string.config_relay_masque_cloudflare_geohash_helper)

        assertContains(copy, "coarse location")
        assertContains(copy, "not used for tracking")
        assertContains(copy, "not persisted")
    }

    private fun assertContains(
        text: String,
        expected: String,
    ) {
        assertTrue("Expected <$text> to contain <$expected>", text.contains(expected, ignoreCase = true))
    }
}
