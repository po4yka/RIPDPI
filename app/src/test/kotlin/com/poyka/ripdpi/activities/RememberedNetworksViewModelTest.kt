package com.poyka.ripdpi.activities

import app.cash.turbine.test
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.platform.formatLocalizedTimestamp
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RememberedNetworksViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = RecordingRememberedPolicySource()

    private fun viewModel() =
        RememberedNetworksViewModel(source).apply { updateLocale(Locale.ENGLISH, is24Hour = true) }

    @Test
    fun `maps policies to privacy-safe ui models`() =
        runTest {
            source.policies.value = listOf(validatedWifiPolicy())

            viewModel().uiState.test {
                // initial loading frame may arrive first; advance to the populated frame.
                var state = awaitItem()
                if (state.loading) {
                    state = awaitItem()
                }
                assertFalse(state.loading)
                assertEquals(1, state.entries.size)
                val entry = state.entries.single()
                assertEquals("9f2c4ab18e7d", entry.scopeKeyShort)
                assertEquals("wifi", entry.transportKey)
                assertEquals(RememberedNetworkStatusTone.Validated, entry.statusTone)
                assertTrue(entry.updatedLabel.isNotBlank())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `timestamp uses the selected locale independently of system language`() {
        val timestamp = Instant.parse("2026-05-30T14:05:00Z").toEpochMilli()
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertEquals(
                "5月30日 14:05",
                formatLocalizedTimestamp(
                    timestamp,
                    Locale.SIMPLIFIED_CHINESE,
                    is24Hour = true,
                    zoneId = ZoneId.of("UTC"),
                ),
            )
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals(
                "May 30, 14:05",
                formatLocalizedTimestamp(timestamp, Locale.ENGLISH, is24Hour = true, zoneId = ZoneId.of("UTC")),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `locale change reformats existing policies without a repository update`() =
        runTest {
            val timestamp = Instant.parse("2026-05-30T14:05:00Z").toEpochMilli()
            val policy = validatedWifiPolicy().copy(updatedAt = timestamp)
            source.policies.value = listOf(policy)
            val model = viewModel()
            model.uiState.test {
                var english = awaitItem()
                if (english.loading) english = awaitItem()
                assertTrue(
                    english.entries
                        .single()
                        .updatedLabel
                        .startsWith("May 30,"),
                )
                model.updateLocale(Locale.SIMPLIFIED_CHINESE, is24Hour = true)
                val chinese = awaitItem()
                assertTrue(
                    chinese.entries
                        .single()
                        .updatedLabel
                        .startsWith("5月30日"),
                )
                assertEquals(
                    english.entries.single().copy(updatedLabel = chinese.entries.single().updatedLabel),
                    chinese.entries.single(),
                )
                assertEquals(listOf(policy), source.policies.value)
                assertEquals(
                    timestamp,
                    source.policies.value
                        .single()
                        .updatedAt,
                )
                assertEquals(emptyList<Pair<Long, String>>(), source.deleted)
                assertEquals(0, source.clearCalls)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `policies stay loading until the route supplies its selected locale`() =
        runTest {
            source.policies.value = listOf(validatedWifiPolicy())
            val model = RememberedNetworksViewModel(source)
            model.uiState.test {
                assertTrue(awaitItem().loading)
                model.updateLocale(Locale.SIMPLIFIED_CHINESE, is24Hour = true)
                val chinese = awaitItem()
                assertFalse(chinese.loading)
                assertTrue(
                    chinese.entries
                        .single()
                        .updatedLabel
                        .contains("月"),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ui model never exposes the raw fingerprint at full length`() {
        val entry = validatedWifiPolicy().toRememberedNetworkUiModel { "t" }
        // scopeKeyShort is a truncated SHA-256 prefix, never a network name / identifier.
        assertEquals(12, entry.scopeKeyShort.length)
        assertTrue(entry.fingerprintHash.startsWith(entry.scopeKeyShort))
    }

    @Test
    fun `delete forwards id and fingerprint to the source`() =
        runTest {
            val entry = validatedWifiPolicy().toRememberedNetworkUiModel { "t" }
            viewModel().deletePolicy(entry)
            assertEquals(listOf(7L to FINGERPRINT), source.deleted)
        }

    @Test
    fun `clear all forwards to the source`() =
        runTest {
            viewModel().clearAll()
            assertEquals(1, source.clearCalls)
        }

    private fun validatedWifiPolicy() =
        DiagnosticsRememberedPolicy(
            id = 7L,
            fingerprintHash = FINGERPRINT,
            mode = "vpn",
            summary =
                NetworkFingerprintSummary(
                    transport = "wifi",
                    networkState = "validated",
                    identityKind = "wifi",
                    privateDnsMode = "opportunistic",
                    dnsServerCount = 2,
                ),
            proxyConfigJson = "{}",
            source = RememberedNetworkPolicySource.MANUAL_SESSION,
            status = "validated",
            successCount = 5,
            failureCount = 0,
            firstObservedAt = 1L,
            updatedAt = 100L,
        )

    private companion object {
        const val FINGERPRINT = "9f2c4ab18e7d5c0a3b6f1d2e4c5a6b7c8d9e0f1a2b3c4d5e6f70819a2b3c4d5e"
    }
}

private class RecordingRememberedPolicySource : DiagnosticsRememberedPolicySource {
    val policies = MutableStateFlow<List<DiagnosticsRememberedPolicy>>(emptyList())
    val deleted = mutableListOf<Pair<Long, String>>()
    var clearCalls = 0

    override fun observePolicies(limit: Int): Flow<List<DiagnosticsRememberedPolicy>> = policies

    override suspend fun deletePolicy(policy: DiagnosticsRememberedPolicy) {
        deleted += policy.id to policy.fingerprintHash
        policies.value = policies.value.filterNot { it.id == policy.id }
    }

    override suspend fun clearAll() {
        clearCalls += 1
        policies.value = emptyList()
    }
}
