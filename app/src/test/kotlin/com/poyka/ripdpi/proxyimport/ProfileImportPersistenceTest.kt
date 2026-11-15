package com.poyka.ripdpi.proxyimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmViewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileImportPersistenceTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private var room: RoomProfileFixture? = null

    @After fun closeStorage() {
        room?.close()
    }

    @Test
    fun `confirmed imports retain names and credentials without selecting until explicit activation`() =
        runTest {
            var storage =
                RoomProfileFixture(ApplicationProvider.getApplicationContext<Context>(), testPauseAuthority()).also {
                    room = it
                    it.initialize()
                }
            val beforeSettings = storage.settings.snapshot()
            val ids = mutableListOf<String>()
            for (label in listOf("first", "second")) {
                val profile = requireNotNull(ProxyUriCodec.parse("trojan://audit-$label@$label.example:443#$label"))
                ids += profile.id
                val viewModel =
                    ProfileImportConfirmViewModel(RelayProfileActivator(storage.coordinator))
                viewModel.setProfile(profile)
                viewModel.confirm()
                assertNull(viewModel.uiState.first { !it.importing }.errorRes)
                assertEquals(beforeSettings, storage.settings.snapshot())
            }
            assertEquals(2, ids.toSet().size)
            storage =
                storage.reopen().also {
                    room = it
                    it.initialize()
                }
            assertEquals(beforeSettings, storage.settings.snapshot())
            val groups = storage.groups.list()
            assertEquals(2, groups.size)
            assertEquals(ids.toSet(), groups.flatMap { it.members }.map { it.id }.toSet())
            assertEquals(
                ids.toSet(),
                storage.relayProfiles
                    .list()
                    .map { it.id }
                    .toSet(),
            )
            for (group in groups) {
                val member = group.members.single() as ProxyProfile.Trojan
                assertEquals(group.id, member.groupId)
                assertEquals(group.name, member.displayName)
                val saved = requireNotNull(storage.relayProfiles.load(member.id))
                val secret = requireNotNull(storage.relayCredentials.load(member.id))
                storage.coordinator.upsertRelay(
                    storage.coordinator.captureMutation(ProfileMutationOrigin.ExplicitActivation),
                    saved,
                    secret,
                    enabled = true,
                    select = true,
                )
                assertEquals(member.id, storage.settings.snapshot().relayProfileId)
                val shared = requireNotNull(RelayProfileShareMapper.toShareable(saved, secret))
                val reimported = ProxyUriCodec.parse(shared.shareUri) as ProxyProfile.Trojan
                assertEquals(member.displayName, reimported.displayName)
                assertEquals(member.server, reimported.server)
                assertEquals(member.password, reimported.password)
            }
            assertTrue(storage.settings.snapshot().relayEnabled)
        }
}
