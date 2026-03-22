package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticProfileWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsCatalogWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsPackWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDiagnosticsProfileImporterTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `importer loads bundled profiles into an empty repository`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock(currentTime = 10L)
            val importer =
                BundledDiagnosticsProfileImporter(
                    profileSource = StaticBundledDiagnosticsProfileSource(sampleBundledProfilesJson(json)),
                    profileCatalog = stores,
                    clock = clock,
                    json = json,
                )

            importer.importProfiles()

            assertFalse(stores.profilesState.value.isEmpty())
            val firstProfile = stores.profilesState.value.first()
            assertTrue(firstProfile.requestJson.isNotBlank())
            assertEquals(firstProfile.version, stores.getPackVersion("default-pack")?.version)
        }

    @Test
    fun `importer updates older bundled versions`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock(currentTime = 10L)
            val importer =
                BundledDiagnosticsProfileImporter(
                    profileSource = StaticBundledDiagnosticsProfileSource(sampleBundledProfilesJson(json)),
                    profileCatalog = stores,
                    clock = clock,
                    json = json,
                )
            importer.importProfiles()
            val bundledProfile = stores.profilesState.value.first()
            stores.upsertProfile(
                bundledProfile.copy(
                    name = "Outdated profile",
                    version = bundledProfile.version - 1,
                ),
            )
            stores.upsertPackVersion(
                TargetPackVersionEntity(
                    packId = "default-pack",
                    version = bundledProfile.version - 1,
                    importedAt = 1L,
                ),
            )

            importer.importProfiles()

            assertEquals(bundledProfile.name, stores.getProfile(bundledProfile.id)?.name)
            assertEquals(bundledProfile.version, stores.getPackVersion("default-pack")?.version)
        }

    @Test
    fun `importer leaves newer local versions untouched`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock(currentTime = 10L)
            val importer =
                BundledDiagnosticsProfileImporter(
                    profileSource = StaticBundledDiagnosticsProfileSource(sampleBundledProfilesJson(json)),
                    profileCatalog = stores,
                    clock = clock,
                    json = json,
                )
            importer.importProfiles()
            val bundledProfile = stores.profilesState.value.first()
            stores.upsertProfile(
                bundledProfile.copy(
                    name = "Custom newer profile",
                    version = bundledProfile.version + 1,
                ),
            )
            stores.upsertPackVersion(
                TargetPackVersionEntity(
                    packId = "default-pack",
                    version = bundledProfile.version + 1,
                    importedAt = 1L,
                ),
            )

            importer.importProfiles()

            assertEquals("Custom newer profile", stores.getProfile(bundledProfile.id)?.name)
            assertEquals(bundledProfile.version + 1, stores.getPackVersion("default-pack")?.version)
        }

    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun `importer rejects malformed bundled catalog shape`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock(currentTime = 10L)
            val importer =
                BundledDiagnosticsProfileImporter(
                    profileSource = StaticBundledDiagnosticsProfileSource("[]"),
                    profileCatalog = stores,
                    clock = clock,
                    json = json,
                )

            importer.importProfiles()
        }
}

private class StaticBundledDiagnosticsProfileSource(
    private val payload: String,
) : BundledDiagnosticsProfileSource {
    override fun readProfilesJson(): String = payload
}

private fun sampleBundledProfilesJson(json: kotlinx.serialization.json.Json): String =
    json.encodeToString(
        BundledDiagnosticsCatalogWire.serializer(),
        BundledDiagnosticsCatalogWire(
            schemaVersion = 2,
            generatedAt = "2026-03-22",
            packs = listOf(BundledDiagnosticsPackWire(id = "default-pack", version = 3)),
            profiles =
                listOf(
                    BundledDiagnosticProfileWire(
                        id = "default",
                        name = "Default",
                        version = 3,
                        request =
                            ProfileSpecWire(
                                profileId = "default",
                                displayName = "Default",
                                executionPolicy =
                                    ProfileExecutionPolicyWire(
                                        manualOnly = false,
                                        allowBackground = false,
                                        requiresRawPath = false,
                                    ),
                                packRefs = listOf("default-pack@3"),
                                domainTargets = listOf(DomainTarget(host = "example.org")),
                            ),
                    ),
                ),
        ),
    )
