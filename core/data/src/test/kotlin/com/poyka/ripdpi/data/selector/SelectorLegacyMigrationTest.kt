package com.poyka.ripdpi.data.selector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.LegacyProfileKeyStore
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectorLegacyMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `new groups without a saved member never acquire legacy activation after import`() =
        runTest {
            LegacyProfileKeyStore().use {
                seedGroups(listOf(group("new")))
                RoomProfileFixture(context, testPauseAuthority()).use { fixture ->
                    fixture.initialize()
                    assertNull(fixture.selectorActiveGroup.activeGroupId.first())
                    preferences().edit().putString("selected-profile-new", "member").commit()
                    fixture.reopen().use { reopened ->
                        reopened.initialize()
                        assertNull(reopened.selectorActiveGroup.activeGroupId.first())
                    }
                }
            }
        }

    @Test
    fun `valid saved legacy member migrates once and later source changes do not replace it`() =
        runTest {
            LegacyProfileKeyStore().use {
                seedGroups(listOf(group("old")))
                preferences().edit().putString("selected-profile-old", "member").commit()
                RoomProfileFixture(context, testPauseAuthority()).use { fixture ->
                    fixture.initialize()
                    assertEquals("old", fixture.selectorActiveGroup.activeGroupId.first())
                    seedGroups(listOf(group("new"), group("old")))
                    preferences().edit().putString("selected-profile-new", "member").commit()
                    fixture.reopen().use { reopened ->
                        reopened.initialize()
                        assertEquals("old", reopened.selectorActiveGroup.activeGroupId.first())
                        assertEquals(listOf("old"), reopened.groupCatalog.snapshot().map { it.id })
                    }
                }
            }
        }

    @Test
    fun `stale member and a member belonging to another group grant no activation`() =
        runTest {
            LegacyProfileKeyStore().use {
                seedGroups(listOf(group("stale"), group("wrong").copy(members = listOf(member("other")))))
                preferences()
                    .edit()
                    .putString("selected-profile-stale", "removed")
                    .putString("selected-profile-wrong", "member")
                    .commit()
                RoomProfileFixture(context, testPauseAuthority()).use { fixture ->
                    fixture.initialize()
                    assertNull(fixture.selectorActiveGroup.activeGroupId.first())
                }
            }
        }

    private fun seedGroups(groups: List<ProxyGroup>) {
        val encoded = RipDpiJson.encodeToString(ListSerializer(ProxyGroup.serializer()), groups)
        check(
            context
                .getSharedPreferences("proxy_group_cache", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putString("proxy-groups", encoded)
                .putInt("proxy-groups-schema-version", 1)
                .commit(),
        )
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

    private fun preferences() = context.getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
}
