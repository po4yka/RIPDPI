package com.poyka.ripdpi.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import com.poyka.ripdpi.proto.AppSettings
import java.io.InputStream
import java.io.OutputStream

object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings =
        AppSettings
            .newBuilder()
            .setAppTheme("system")
            .setRipdpiMode("vpn")
            .setDnsIp("1.1.1.1")
            .setProxyIp("127.0.0.1")
            .setProxyPort(1080)
            .setMaxConnections(512)
            .setBufferSize(16384)
            .setDesyncMethod("disorder")
            .setSplitPosition(1)
            .setSplitMarker(DefaultSplitMarker)
            .setFakeTtl(8)
            .setFakeSni(DefaultFakeSni)
            .setFakeOffsetMarker(DefaultFakeOffsetMarker)
            .setFakeTlsSniMode(FakeTlsSniModeFixed)
            .setOobData("a")
            .setDesyncHttp(true)
            .setDesyncHttps(true)
            .setTlsrecMarker(DefaultTlsRecordMarker)
            .setHostsMode("disable")
            .setQuicInitialMode(QuicInitialModeRouteAndCache)
            .setQuicSupportV1(true)
            .setQuicSupportV2(true)
            .setHostAutolearnEnabled(false)
            .setHostAutolearnPenaltyTtlHours(DefaultHostAutolearnPenaltyTtlHours)
            .setHostAutolearnMaxHosts(DefaultHostAutolearnMaxHosts)
            .setAppIconVariant("default")
            .setAppIconStyle("themed")
            .setDiagnosticsMonitorEnabled(true)
            .setDiagnosticsSampleIntervalSeconds(15)
            .setDiagnosticsDefaultScanPathMode("raw_path")
            .setDiagnosticsAutoResumeAfterRawScan(true)
            .setDiagnosticsActiveProfileId("default")
            .setDiagnosticsHistoryRetentionDays(14)
            .setDiagnosticsExportIncludeHistory(true)
            .build()

    override suspend fun readFrom(input: InputStream): AppSettings {
        try {
            return AppSettings.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", e)
        }
    }

    override suspend fun writeTo(
        t: AppSettings,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}
