package com.poyka.ripdpi.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import com.poyka.ripdpi.proto.AppSettings
import java.io.InputStream
import java.io.OutputStream

object AppSettingsSerializer : Serializer<AppSettings> {
    private val defaultDns = canonicalDefaultEncryptedDnsSettings()

    override val defaultValue: AppSettings =
        AppSettings
            .newBuilder()
            .setAppTheme("system")
            .setRipdpiMode("vpn")
            .setDnsIp(defaultDns.dnsIp)
            .setDnsMode(defaultDns.mode)
            .setDnsProviderId(defaultDns.providerId)
            .setEncryptedDnsProtocol(defaultDns.encryptedDnsProtocol)
            .setEncryptedDnsHost(defaultDns.encryptedDnsHost)
            .setEncryptedDnsPort(defaultDns.encryptedDnsPort)
            .setEncryptedDnsTlsServerName(defaultDns.encryptedDnsTlsServerName)
            .addAllEncryptedDnsBootstrapIps(defaultDns.encryptedDnsBootstrapIps)
            .setEncryptedDnsDohUrl(defaultDns.encryptedDnsDohUrl)
            .setProxyIp("127.0.0.1")
            .setProxyPort(1080)
            .setMaxConnections(512)
            .setBufferSize(16384)
            .setFakeTtl(8)
            .setAdaptiveFakeTtlEnabled(false)
            .setAdaptiveFakeTtlDelta(DefaultAdaptiveFakeTtlDelta)
            .setAdaptiveFakeTtlMin(DefaultAdaptiveFakeTtlMin)
            .setAdaptiveFakeTtlMax(DefaultAdaptiveFakeTtlMax)
            .setAdaptiveFakeTtlFallback(DefaultAdaptiveFakeTtlFallback)
            .setFakeSni(DefaultFakeSni)
            .setFakeOffsetMarker(DefaultFakeOffsetMarker)
            .setFakeTlsSniMode(FakeTlsSniModeFixed)
            .setHttpFakeProfile(FakePayloadProfileCompatDefault)
            .setTlsFakeProfile(FakePayloadProfileCompatDefault)
            .setOobData("a")
            .setDesyncHttp(true)
            .setDesyncHttps(true)
            .setUdpFakeProfile(FakePayloadProfileCompatDefault)
            .setHttpMethodEol(false)
            .setHttpUnixEol(false)
            .setHostsMode("disable")
            .setQuicInitialMode(QuicInitialModeRouteAndCache)
            .setQuicSupportV1(true)
            .setQuicSupportV2(true)
            .setQuicFakeProfile(QuicFakeProfileDisabled)
            .setQuicFakeHost("")
            .setHostAutolearnEnabled(false)
            .setHostAutolearnPenaltyTtlHours(DefaultHostAutolearnPenaltyTtlHours)
            .setHostAutolearnMaxHosts(DefaultHostAutolearnMaxHosts)
            .setNetworkStrategyMemoryEnabled(false)
            .setAppIconVariant("default")
            .setAppIconStyle("themed")
            .setDiagnosticsMonitorEnabled(true)
            .setDiagnosticsSampleIntervalSeconds(15)
            .setDiagnosticsDefaultScanPathMode("raw_path")
            .setDiagnosticsAutoResumeAfterRawScan(true)
            .setDiagnosticsActiveProfileId("default")
            .setDiagnosticsHistoryRetentionDays(14)
            .setDiagnosticsExportIncludeHistory(true)
            .setCanonicalDefaultStrategyChains()
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
