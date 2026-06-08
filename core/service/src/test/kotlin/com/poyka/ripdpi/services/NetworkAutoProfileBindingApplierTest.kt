package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkAutoProfileBinding
import com.poyka.ripdpi.data.NetworkAutoProfileBindingStore
import com.poyka.ripdpi.data.appSettingsFromJson
import com.poyka.ripdpi.data.toJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAutoProfileBindingApplierTest {
    @Test
    fun `matching network binding replaces active settings`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val baseline = AppSettingsSerializer.defaultValue
            val bound =
                baseline
                    .toBuilder()
                    .setRipdpiMode(Mode.Proxy.preferenceValue)
                    .setProxyPort(9090)
                    .build()
            val boundJson = bound.toJson()
            val expectedAppliedSettings = appSettingsFromJson(boundJson)
            val repository = TestAppSettingsRepository(baseline)
            val store =
                InMemoryNetworkAutoProfileBindingStore(
                    binding =
                        bindingFor(
                            fingerprintHash = fingerprint.scopeKey(),
                            settingsJson = boundJson,
                        ),
                )
            val applier =
                NetworkAutoProfileBindingApplier(
                    appSettingsRepository = repository,
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    bindingStore = store,
                )

            val result = applier.apply(settings = baseline, fingerprint = fingerprint)

            assertTrue(result.applied)
            assertEquals(expectedAppliedSettings, result.settings)
            assertEquals(expectedAppliedSettings, repository.snapshot())
        }

    @Test
    fun `different network binding is ignored`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val baseline = AppSettingsSerializer.defaultValue
            val repository = TestAppSettingsRepository(baseline)
            val store =
                InMemoryNetworkAutoProfileBindingStore(
                    binding =
                        bindingFor(
                            fingerprintHash = "other-network",
                            settingsJson =
                                baseline
                                    .toBuilder()
                                    .setProxyPort(9090)
                                    .build()
                                    .toJson(),
                        ),
                )
            val applier =
                NetworkAutoProfileBindingApplier(
                    appSettingsRepository = repository,
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    bindingStore = store,
                )

            val result = applier.apply(settings = baseline, fingerprint = fingerprint)

            assertFalse(result.applied)
            assertEquals(baseline, result.settings)
            assertEquals(baseline, repository.snapshot())
        }

    @Test
    fun `already active binding is not rewritten`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setRipdpiMode(Mode.Proxy.preferenceValue)
                    .build()
            val repository = TestAppSettingsRepository(settings)
            val applier =
                NetworkAutoProfileBindingApplier(
                    appSettingsRepository = repository,
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    bindingStore =
                        InMemoryNetworkAutoProfileBindingStore(
                            binding =
                                bindingFor(
                                    fingerprintHash = fingerprint.scopeKey(),
                                    settingsJson = settings.toJson(),
                                ),
                        ),
                )

            val result = applier.apply(settings = settings, fingerprint = fingerprint)

            assertFalse(result.applied)
            assertEquals(settings, result.settings)
        }

    private fun bindingFor(
        fingerprintHash: String,
        settingsJson: String,
    ): NetworkAutoProfileBinding =
        NetworkAutoProfileBinding(
            id = "binding",
            fingerprintHash = fingerprintHash,
            summary = sampleFingerprint().summary(),
            profileName = "Proxy profile",
            settingsJson = settingsJson,
            createdAt = 1L,
            updatedAt = 1L,
        )
}

private class InMemoryNetworkAutoProfileBindingStore(
    binding: NetworkAutoProfileBinding,
) : NetworkAutoProfileBindingStore {
    private val state = MutableStateFlow(listOf(binding))

    override fun bindings(): Flow<List<NetworkAutoProfileBinding>> = state.asStateFlow()

    override suspend fun list(): List<NetworkAutoProfileBinding> = state.value

    override suspend fun find(fingerprintHash: String): NetworkAutoProfileBinding? =
        state.value.firstOrNull { it.fingerprintHash == fingerprintHash }

    override suspend fun upsert(binding: NetworkAutoProfileBinding) {
        state.value = state.value.filterNot { it.id == binding.id } + binding
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
