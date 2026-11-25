package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
import com.poyka.ripdpi.core.detection.dpi.ProbeStage
import com.poyka.ripdpi.core.detection.dpi.fieldFailureStage
import com.poyka.ripdpi.data.diagnostics.ClientFieldReportContractVersion
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import com.poyka.ripdpi.data.diagnostics.nativeFieldFailureStage
import com.poyka.ripdpi.data.failureClassFromNativeCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldFailureStageContractTest {
    private fun contract(name: String): JsonObject =
        Json.parseToJsonElement(repoFixture("$ContractDir/$name").readText()).jsonObject

    private fun buckets(report: JsonObject) = report["buckets"]!!.jsonArray.map { it.jsonObject }

    private fun bucketStageIsValid(bucket: JsonObject): Boolean {
        val stage = bucket["failure_stage"]?.jsonPrimitive?.content
        return when (bucket["outcome"]?.jsonPrimitive?.content) {
            "failure" -> stage != null && FieldFailureStage.fromWireValue(stage) != null
            "success" -> stage == null
            else -> false
        }
    }

    @Test
    fun `contract version matches the vendored schema`() {
        val schema = contract("client-field-report.schema.json")
        assertEquals(ClientFieldReportContractVersion, schema["x-contract-version"]!!.jsonPrimitive.int)
        assertEquals(
            ClientFieldReportContractVersion,
            schema["properties"]!!
                .jsonObject["schema_version"]!!
                .jsonObject["const"]!!
                .jsonPrimitive.int,
        )
    }

    @Test
    fun `enum values equal the vendored schema enum`() {
        val schemaValues =
            contract("client-field-report.schema.json")["\$defs"]!!
                .jsonObject["bucket"]!!
                .jsonObject["properties"]!!
                .jsonObject["failure_stage"]!!
                .jsonObject["enum"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertEquals(schemaValues, FieldFailureStage.entries.map { it.wireValue })
    }

    @Test
    fun `golden report stages decode`() {
        val golden = buckets(contract("client-field-report.golden.json"))
        golden.forEach { bucket -> assertTrue("invalid bucket $bucket", bucketStageIsValid(bucket)) }
        assertEquals(
            listOf(FieldFailureStage.TlsHandshake, FieldFailureStage.QuicHandshake),
            golden.mapNotNull { bucket ->
                bucket["failure_stage"]?.jsonPrimitive?.content?.let(FieldFailureStage::fromWireValue)
            },
        )
    }

    @Test
    fun `negative stage fixtures are rejected`() {
        listOf(
            "report-unknown-failure-stage.json",
            "report-failure-without-stage.json",
            "report-success-with-stage.json",
        ).forEach { name ->
            val report = contract("negative/$name")
            assertTrue("$name must be rejected", buckets(report).any { !bucketStageIsValid(it) })
        }
    }

    @Test
    fun `every DpiProbeError maps to the specified stage`() {
        val expected =
            mapOf(
                DpiProbeError.TlsSpoof to FieldFailureStage.TlsHandshake,
                DpiProbeError.TlsAlertSni to FieldFailureStage.TlsHandshake,
                DpiProbeError.TlsAlertHandshake to FieldFailureStage.TlsHandshake,
                DpiProbeError.TlsBlockVersion to FieldFailureStage.TlsHandshake,
                DpiProbeError.TlsRst to FieldFailureStage.TcpReset,
                DpiProbeError.TlsEof to FieldFailureStage.TlsHandshake,
                DpiProbeError.TlsDrop to FieldFailureStage.TlsHandshake,
                DpiProbeError.TlsMitm to FieldFailureStage.TlsHandshake,
                DpiProbeError.SynDrop to FieldFailureStage.TcpConnect,
                DpiProbeError.TcpRst to FieldFailureStage.TcpReset,
                DpiProbeError.TlsRstTls to FieldFailureStage.TcpReset,
                DpiProbeError.TcpAbort to FieldFailureStage.TcpReset,
                DpiProbeError.DnsFail to FieldFailureStage.Dns,
                DpiProbeError.Refused to FieldFailureStage.TcpConnect,
                DpiProbeError.NetUnreach to FieldFailureStage.TcpConnect,
                DpiProbeError.HostUnreach to FieldFailureStage.TcpConnect,
                DpiProbeError.Unknown to FieldFailureStage.Unknown,
            )
        val subclasses =
            DpiProbeError::class.java.declaredClasses
                .filter { DpiProbeError::class.java.isAssignableFrom(it) }
                .map { it.getField("INSTANCE").get(null) as DpiProbeError }
        assertEquals(subclasses.toSet(), expected.keys)
        expected.forEach { (error, stage) -> assertEquals(error.label, stage, error.fieldFailureStage) }
    }

    @Test
    fun `every ProbeStage maps and backs an unclassified probe error`() {
        val expected =
            mapOf(
                ProbeStage.TCP_CONNECT to FieldFailureStage.TcpConnect,
                ProbeStage.TLS_HANDSHAKE to FieldFailureStage.TlsHandshake,
                ProbeStage.SENDING_DATA to FieldFailureStage.Unknown,
                ProbeStage.READING_DATA to FieldFailureStage.Unknown,
            )
        assertEquals(ProbeStage.entries.toSet(), expected.keys)
        expected.forEach { (stage, fieldStage) ->
            assertEquals(stage.name, fieldStage, stage.fieldFailureStage)
            assertEquals(stage.name, fieldStage, fieldFailureStage(DpiProbeError.Unknown, stage))
        }
        assertEquals(FieldFailureStage.Dns, fieldFailureStage(DpiProbeError.DnsFail, ProbeStage.TLS_HANDSHAKE))
    }

    private fun nativeMappingRows(): Map<Pair<String, String>, String> =
        repoFixture(NativeMappingFixture)
            .readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (failureClass, stage, fieldStage) = line.split("\t")
                (failureClass to stage) to fieldStage
            }

    @Test
    fun `native failure codes map as the shared table states`() {
        val rows = nativeMappingRows()
        val stages =
            rows.keys
                .map { it.second }
                .filter { it != "*" }
                .toSet()
        assertEquals(10, stages.size)
        rows.keys.map { it.first }.toSet().forEach { failureClass ->
            stages.forEach { stage ->
                val expected = rows[failureClass to stage] ?: rows.getValue(failureClass to "*")
                assertEquals(
                    "$failureClass at $stage",
                    expected,
                    nativeFieldFailureStage(failureClass, stage).wireValue,
                )
            }
        }
    }

    @Test
    fun `every native class decodes and an unknown code or stage reports unknown`() {
        val classes = nativeMappingRows().keys.map { it.first }.toSet()
        assertEquals(19, classes.size)
        (classes - "unknown").forEach { assertNotNull(it, failureClassFromNativeCode(it)) }
        assertEquals(FieldFailureStage.Unknown, nativeFieldFailureStage("not_a_native_code", "connect"))
        assertEquals(FieldFailureStage.Unknown, nativeFieldFailureStage("silent_drop", null))
        assertEquals(FieldFailureStage.Unknown, nativeFieldFailureStage("silent_drop", "not_a_stage"))
        assertEquals(FieldFailureStage.QuicHandshake, nativeFieldFailureStage("quic_breakage", null))
    }

    private companion object {
        const val ContractDir = "core/data/src/test/resources/contract"
        const val NativeMappingFixture = "contract-fixtures/native_failure_stage_mapping.tsv"
    }
}
