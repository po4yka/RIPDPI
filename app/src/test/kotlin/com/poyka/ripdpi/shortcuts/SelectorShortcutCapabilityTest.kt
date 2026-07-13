package com.poyka.ripdpi.shortcuts

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectorShortcutCapabilityTest {
    private val application: Application by lazy { ApplicationProvider.getApplicationContext() }
    private val capability by lazy { SelectorShortcutCapability(application) }

    @Before
    fun clearCapability() {
        application
            .getSharedPreferences("selector_shortcut_capability", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `signed selector intent verifies`() {
        val intent = selectorIntent(groupId = "group", profileId = "profile")

        assertTrue(capability.verifies(intent))
    }

    @Test
    fun `missing or tampered capability is rejected`() {
        val unsigned =
            Intent()
                .putExtra(ExtraSelectGroupId, "group")
                .putExtra(ExtraSelectProfileId, "profile")
        val tamperedProfile =
            selectorIntent(groupId = "group", profileId = "profile")
                .putExtra(ExtraSelectProfileId, "other-profile")
        val tamperedToken =
            selectorIntent(groupId = "group", profileId = "profile")
                .putExtra(ExtraSelectorCapability, "invalid")

        assertFalse(capability.verifies(unsigned))
        assertFalse(capability.verifies(tamperedProfile))
        assertFalse(capability.verifies(tamperedToken))
    }

    private fun selectorIntent(
        groupId: String,
        profileId: String,
    ): Intent =
        Intent()
            .putExtra(ExtraSelectGroupId, groupId)
            .putExtra(ExtraSelectProfileId, profileId)
            .putExtra(ExtraSelectorCapability, capability.sign(groupId, profileId))
}
