package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
import com.poyka.ripdpi.core.detection.dpi.ProbeStage
import com.poyka.ripdpi.core.detection.dpi.fieldFailureStage
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.diagnostics.ClientFieldReportContractVersion
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import com.poyka.ripdpi.data.diagnostics.fieldFailureStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin half of the failure-stage contract. The Rust half lives in
 * `ripdpi-diagnostics-contracts` (`types/field_failure_stage.rs`); both read the same vendored
 * `client-field-report` v1 schema under `core/data/src/test/resources/contract/`.
 */
class FieldFailureStageContractTest {
    private fun contract(name: String): JsonObject =
        Json.parseToJsonElement(repoFixture("$ContractDir/$name").readText()).jsonObject

    private fun buckets(report: JsonObject) = report["buckets"]!!.jsonArray.map { it.jsonObject }

    /** The bucket rule the schema states: a known stage is present exactly when the outcome is failure. */
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

    @Test
    fun `every connection failure class maps to one stage`() {
        val expected =
            mapOf(
                FailureClass.TunnelEstablish to FieldFailureStage.Unknown,
                FailureClass.WarpProvisioning to FieldFailureStage.Unknown,
                FailureClass.WarpEndpoint to FieldFailureStage.Unknown,
                FailureClass.FingerprintPolicy to FieldFailureStage.Unknown,
                FailureClass.DnsInterference to FieldFailureStage.Dns,
                FailureClass.TlsInterference to FieldFailureStage.TlsHandshake,
                FailureClass.Timeout to FieldFailureStage.TcpConnect,
                FailureClass.ResetAbort to FieldFailureStage.TcpReset,
                FailureClass.NetworkHandover to FieldFailureStage.Unknown,
                FailureClass.Compatibility to FieldFailureStage.ProtocolError,
                FailureClass.NativeIo to FieldFailureStage.Unknown,
                FailureClass.Unexpected to FieldFailureStage.Unknown,
            )
        assertEquals(FailureClass.entries.toSet(), expected.keys)
        expected.forEach { (failureClass, stage) ->
            assertEquals(failureClass.name, stage, failureClass.fieldFailureStage)
        }
    }

    private companion object {
        const val ContractDir = "core/data/src/test/resources/contract"
    }
}
