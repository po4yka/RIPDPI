package com.poyka.ripdpi.data.selector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectorSelectionStoreConcurrencyTest {
    @Test
    fun `concurrent selection and clearing agree with committed and reopened state`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            RoomProfileFixture(context, testPauseAuthority()).use { fixture ->
                fixture.initialize()
                fixture.groups.add(
                    ProxyGroup(
                        "group",
                        "Group",
                        ProxyGroupType.BASIC,
                        0,
                        true,
                        members =
                            listOf(
                                ProxyProfile.Shadowsocks(
                                    "member",
                                    "Member",
                                    "group",
                                    "test.example",
                                    443,
                                    "aes-256-gcm",
                                    "test-key",
                                ),
                            ),
                    ),
                )
                val store = fixture.selectorSelections
                val first =
                    launch(Dispatchers.Default) {
                        repeat(50) { i ->
                            if (i % 2 ==
                                0
                            ) {
                                store.select("group", "member")
                            } else {
                                store.clearSelection("group")
                            }
                        }
                    }
                val second =
                    launch(Dispatchers.Default) {
                        repeat(50) { i ->
                            if (i % 3 ==
                                0
                            ) {
                                store.clearSelection("group")
                            } else {
                                store.select("group", "member")
                            }
                        }
                    }
                first.join()
                second.join()
                val saved = store.snapshot("group")
                assertEquals(saved.profileId, store.selectedProfileId("group").first())
                fixture.reopen().use { reopened ->
                    reopened.initialize()
                    assertEquals(saved.profileId, reopened.selectorSelections.snapshot("group").profileId)
                    assertEquals(saved.isManual, reopened.selectorSelections.snapshot("group").isManual)
                    assertEquals(saved.profileId, reopened.selectorSelections.selectedProfileId("group").first())
                }
            }
        }
}
