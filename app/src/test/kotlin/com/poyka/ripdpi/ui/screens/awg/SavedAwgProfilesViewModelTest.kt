package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.services.AwgEndpointCheck
import com.poyka.ripdpi.services.AwgEndpointCheckResult
import com.poyka.ripdpi.ui.screens.profiles.ProfileMeasurementUiState
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityFailure
import com.poyka.ripdpi.ui.screens.profiles.SavedAwgProfilesViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedAwgProfilesViewModelTest {
    private val fixture = AwgProfileViewModelFixture()

    @get:Rule
    val dispatcherRule: TestRule = fixture

    private val checked = mutableListOf<AwgActivationRequest>()

    private fun viewModel(result: AwgEndpointCheckResult) =
        SavedAwgProfilesViewModel(
            fixture.repository,
            AwgEndpointCheck { request ->
                checked += request
                result
            },
        )

    @Test
    fun `a failed profile read leaves the picker without AWG rows instead of crashing`() =
        runTest {
            fixture.dao.failReads = true
            val viewModel = viewModel(AwgEndpointCheckResult.NoAnswer)

            backgroundScope.launch { viewModel.profiles.collect {} }
            advanceUntilIdle()

            assertTrue(viewModel.profiles.value.isEmpty())
        }

    @Test
    fun `checking a saved server shows its handshake round trip on that row`() =
        runTest {
            val id = saveProfile()
            val viewModel = viewModel(AwgEndpointCheckResult.Answered(42))
            backgroundScope.launch { viewModel.profiles.collect {} }
            advanceUntilIdle()
            assertEquals(
                ProfileMeasurementUiState.NotChecked,
                viewModel.profiles.value
                    .single()
                    .measurement,
            )

            viewModel.check(id)
            advanceUntilIdle()

            assertEquals("vpn.example.com", checked.single().endpointHost)
            val measured =
                viewModel.profiles.value
                    .single()
                    .measurement as ProfileMeasurementUiState.Measured
            assertEquals(42L, measured.latencyMillis)
        }

    @Test
    fun `a server that does not answer reads as timed out`() =
        runTest {
            val id = saveProfile()
            val viewModel = viewModel(AwgEndpointCheckResult.NoAnswer)
            backgroundScope.launch { viewModel.profiles.collect {} }
            advanceUntilIdle()

            viewModel.check(id)
            advanceUntilIdle()

            assertEquals(
                ProfileMeasurementUiState.Failed(ProfileUtilityFailure.TimedOut),
                viewModel.profiles.value
                    .single()
                    .measurement,
            )
        }

    private suspend fun saveProfile(): String =
        fixture.repository
            .saveSubscriptionProfiles(
                listOf(
                    AwgSubscriptionProfile(
                        "group-one",
                        "tag:home",
                        "Home AWG",
                        AwgActivationRequest(
                            profileId = "",
                            privateKey = "",
                            peerPublicKey = AwgPeerTestKey,
                            endpointHost = "vpn.example.com",
                            endpointPort = 51820,
                            interfaceAddressV4 = "10.8.0.2/32",
                        ),
                    ),
                ),
            ).single()
            .id
}
