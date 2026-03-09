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
            .setFakeTtl(8)
            .setFakeSni("www.iana.org")
            .setOobData("a")
            .setDesyncHttp(true)
            .setDesyncHttps(true)
            .setHostsMode("disable")
            .setAppIconVariant("default")
            .setAppIconStyle("themed")
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
