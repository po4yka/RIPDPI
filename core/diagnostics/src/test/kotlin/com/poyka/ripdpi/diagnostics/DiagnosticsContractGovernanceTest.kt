package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.DiagnosticsEngineSchemaVersion
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsCatalogSchemaVersion
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsCatalogWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsContractGovernanceTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `shared contract fixtures decode successfully`() {
        val currentProfileSpec =
            json.decodeFromString(
                ProfileSpecWire.serializer(),
                repoFixture("diagnostics-contract-fixtures/profile_spec_current.json").readText(),
            )
        val legacyProfileSpec =
            json.decodeProfileSpecWireCompat(
                repoFixture("diagnostics-contract-fixtures/profile_spec_legacy.json").readText(),
            )
        val engineRequest =
            json.decodeFromString(
                EngineScanRequestWire.serializer(),
                repoFixture("diagnostics-contract-fixtures/engine_request_current.json").readText(),
            )
        val engineReport =
            json.decodeFromString(
                EngineScanReportWire.serializer(),
                repoFixture("diagnostics-contract-fixtures/engine_report_current.json").readText(),
            )
        val engineProgress =
            json.decodeFromString(
                EngineProgressWire.serializer(),
                repoFixture("diagnostics-contract-fixtures/engine_progress_current.json").readText(),
            )
        val outcomeTaxonomy =
            json
                .parseToJsonElement(
                    repoFixture("diagnostics-contract-fixtures/outcome_taxonomy_current.json").readText(),
                ).jsonObject

        assertNotNull(currentProfileSpec.executionPolicy)
        assertNotNull(currentProfileSpec.executionPolicy?.probePersistencePolicy)
        assertTrue(legacyProfileSpec.executionPolicyOrCompat().requiresRawPath)
        assertEquals(DiagnosticsEngineSchemaVersion, engineRequest.schemaVersion)
        assertEquals(DiagnosticsEngineSchemaVersion, engineReport.schemaVersion)
        assertEquals(DiagnosticsEngineSchemaVersion, engineProgress.schemaVersion)
        assertEquals(1, outcomeTaxonomy["schemaVersion"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `bundled catalog fixture matches committed asset and schema version`() {
        val assetFile = repoFixture("core/diagnostics/src/main/assets/diagnostics/default_profiles.json")
        val fixtureFile = repoFixture("diagnostics-contract-fixtures/profile_catalog_current.json")
        val assetCatalog = json.decodeFromString(BundledDiagnosticsCatalogWire.serializer(), assetFile.readText())
        val fixtureCatalog = json.decodeFromString(BundledDiagnosticsCatalogWire.serializer(), fixtureFile.readText())

        assertEquals(BundledDiagnosticsCatalogSchemaVersion, assetCatalog.schemaVersion)
        assertEquals(assetCatalog, fixtureCatalog)
    }

    @Test
    fun `engine schema version matches rust contract constant`() {
        val rustWire = repoFixture("native/rust/crates/ripdpi-monitor/src/wire.rs").readText()
        val match =
            Regex("""DIAGNOSTICS_ENGINE_SCHEMA_VERSION:\s*u32\s*=\s*(\d+)""")
                .find(rustWire)
        assertNotNull(match)
        assertEquals(DiagnosticsEngineSchemaVersion, match!!.groupValues[1].toInt())
    }
}
