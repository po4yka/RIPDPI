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
            val historyRepository = FakeDiagnosticsHistoryRepository()
            val importer =
                BundledDiagnosticsProfileImporter(
                    profileSource = StaticBundledDiagnosticsProfileSource(sampleBundledProfilesJson(json)),
                    historyRepository = historyRepository,
                    json = json,
                )

            importer.importProfiles()

            assertFalse(historyRepository.profilesState.value.isEmpty())
            val firstProfile = historyRepository.profilesState.value.first()
            assertTrue(firstProfile.requestJson.isNotBlank())
            assertEquals(firstProfile.version, historyRepository.getPackVersion(firstProfile.id)?.version)
        }

    @Test
    fun `importer updates older bundled versions`() =
        runTest {
            val historyRepository = FakeDiagnosticsHistoryRepository()
            val importer =
                BundledDiagnosticsProfileImporter(
                    profileSource = StaticBundledDiagnosticsProfileSource(sampleBundledProfilesJson(json)),
                    historyRepository = historyRepository,
                    json = json,
                )
            importer.importProfiles()
            val bundledProfile = historyRepository.profilesState.value.first()
            historyRepository.upsertProfile(
                bundledProfile.copy(
                    name = "Outdated profile",
                    version = bundledProfile.version - 1,
                ),
            )
            historyRepository.upsertPackVersion(
                TargetPackVersionEntity(
                    packId = bundledProfile.id,
                    version = bundledProfile.version - 1,
                    importedAt = 1L,
                ),
            )

            importer.importProfiles()

            assertEquals(bundledProfile.name, historyRepository.getProfile(bundledProfile.id)?.name)
            assertEquals(bundledProfile.version, historyRepository.getPackVersion(bundledProfile.id)?.version)
        }

    @Test
    fun `importer leaves newer local versions untouched`() =
        runTest {
            val historyRepository = FakeDiagnosticsHistoryRepository()
            val importer =
                BundledDiagnosticsProfileImporter(
                    profileSource = StaticBundledDiagnosticsProfileSource(sampleBundledProfilesJson(json)),
                    historyRepository = historyRepository,
                    json = json,
                )
            importer.importProfiles()
            val bundledProfile = historyRepository.profilesState.value.first()
            historyRepository.upsertProfile(
                bundledProfile.copy(
                    name = "Custom newer profile",
                    version = bundledProfile.version + 1,
                ),
            )
            historyRepository.upsertPackVersion(
                TargetPackVersionEntity(
                    packId = bundledProfile.id,
                    version = bundledProfile.version + 1,
                    importedAt = 1L,
                ),
            )

            importer.importProfiles()

            assertEquals("Custom newer profile", historyRepository.getProfile(bundledProfile.id)?.name)
            assertEquals(bundledProfile.version + 1, historyRepository.getPackVersion(bundledProfile.id)?.version)
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
