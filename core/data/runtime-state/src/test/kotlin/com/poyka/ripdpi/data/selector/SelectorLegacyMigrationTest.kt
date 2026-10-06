package com.poyka.ripdpi.data.selector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.testPauseAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectorLegacyMigrationTest {
    @Test
    fun `newly imported groups without a saved member never acquire legacy activation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = testPauseAuthority()
        val before = authority.snapshotAuthority()
        val store = SelectorActiveGroupStore(context, authority)
        store.initializeLegacyGroup(listOf(group("new")))
        assertNull(store.activeGroupId.value)
        preferences(context).edit().putString("selected-profile-new", "member").commit()
        val reconstructed = SelectorActiveGroupStore(context, authority)
        reconstructed.initializeLegacyGroup(listOf(group("new")))
        assertNull(reconstructed.activeGroupId.value)
        assertEquals(before, authority.snapshotAuthority())
    }

    @Test
    fun `valid saved legacy member migrates once and later imports do not replace it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences(context).edit().putString("selected-profile-old", "member").commit()
        val store = SelectorActiveGroupStore(context, testPauseAuthority())
        store.initializeLegacyGroup(listOf(group("old")))
        assertEquals("old", store.activeGroupId.value)
        preferences(context).edit().putString("selected-profile-new", "member").commit()
        store.initializeLegacyGroup(listOf(group("new"), group("old")))
        assertEquals("old", store.activeGroupId.value)
    }

    @Test
    fun `stale saved member and a member belonging to another group grant no activation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences(context)
            .edit()
            .putString("selected-profile-stale", "removed")
            .putString("selected-profile-wrong", "member")
            .commit()
        val store = SelectorActiveGroupStore(context, testPauseAuthority())
        val invalid = group("wrong").copy(members = listOf(member("other")))
        store.initializeLegacyGroup(listOf(group("stale"), invalid))
        assertNull(store.activeGroupId.value)
    }

    private fun group(id: String) = ProxyGroup(id, id, ProxyGroupType.BASIC, 0, true, members = listOf(member(id)))

    private fun member(groupId: String) =
        ProxyProfile.Shadowsocks(
            "member",
            "Fixture",
            groupId,
            "fixture.example",
            443,
            "aes-256-gcm",
            "fixture",
        )

    private fun preferences(context: Context) =
        context.getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
}
