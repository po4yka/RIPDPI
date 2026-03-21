package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
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
            assertEquals(firstProfile.version, stores.getPackVersion(firstProfile.id)?.version)
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
                    packId = bundledProfile.id,
                    version = bundledProfile.version - 1,
                    importedAt = 1L,
                ),
            )

            importer.importProfiles()

            assertEquals(bundledProfile.name, stores.getProfile(bundledProfile.id)?.name)
            assertEquals(bundledProfile.version, stores.getPackVersion(bundledProfile.id)?.version)
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
                    packId = bundledProfile.id,
                    version = bundledProfile.version + 1,
                    importedAt = 1L,
                ),
            )

            importer.importProfiles()

            assertEquals("Custom newer profile", stores.getProfile(bundledProfile.id)?.name)
            assertEquals(bundledProfile.version + 1, stores.getPackVersion(bundledProfile.id)?.version)
        }
}

private class StaticBundledDiagnosticsProfileSource(
    private val payload: String,
) : BundledDiagnosticsProfileSource {
    override fun readProfilesJson(): String = payload
}

private fun sampleBundledProfilesJson(json: kotlinx.serialization.json.Json): String =
    json.encodeToString(
        ListSerializer(BundledDiagnosticProfile.serializer()),
        listOf(
            BundledDiagnosticProfile(
                id = "default",
                name = "Default",
                version = 3,
                request =
                    ScanRequest(
                        profileId = "default",
                        displayName = "Default",
                        pathMode = ScanPathMode.RAW_PATH,
                        domainTargets = listOf(DomainTarget(host = "example.org")),
                    ),
            ),
        ),
    )
