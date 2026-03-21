package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

class DiagnosticsArchiveRedactor
    @Inject
    constructor(
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        fun redact(model: NetworkSnapshotModel): NetworkSnapshotModel =
            model.copy(
                publicIp = model.publicIp?.let { "redacted" },
                publicAsn = model.publicAsn?.let { "redacted" },
                dnsServers =
                    if (model.dnsServers.isNotEmpty()) {
                        listOf("redacted(${model.dnsServers.size})")
                    } else {
                        model.dnsServers
                    },
                localAddresses =
                    if (model.localAddresses.isNotEmpty()) {
                        listOf("redacted(${model.localAddresses.size})")
                    } else {
                        model.localAddresses
                    },
                wifiDetails =
                    model.wifiDetails?.let { wifi ->
                        wifi.copy(
                            ssid = if (wifi.ssid != "unknown") "redacted" else wifi.ssid,
                            bssid = if (wifi.bssid != "unknown") "redacted" else wifi.bssid,
                            gateway = wifi.gateway?.let { "redacted" },
                            dhcpServer = wifi.dhcpServer?.let { "redacted" },
                            ipAddress = wifi.ipAddress?.let { "redacted" },
                            subnetMask = wifi.subnetMask?.let { "redacted" },
                        )
                    },
            )

        fun redact(model: DiagnosticContextModel): DiagnosticContextModel =
            model.copy(
                service =
                    model.service.copy(
                        proxyEndpoint =
                            if (model.service.proxyEndpoint != "unknown") {
                                "redacted"
                            } else {
                                model.service.proxyEndpoint
                            },
                    ),
            )

        fun redact(entity: NetworkSnapshotEntity): NetworkSnapshotEntity {
            val model = decodeNetworkSnapshot(entity) ?: return entity
            return entity.copy(
                payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), redact(model)),
            )
        }

        fun redact(entity: DiagnosticContextEntity): DiagnosticContextEntity {
            val model = decodeDiagnosticContext(entity) ?: return entity
            return entity.copy(
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), redact(model)),
            )
        }

        fun decodeNetworkSnapshot(entity: NetworkSnapshotEntity?): NetworkSnapshotModel? =
            entity?.payloadJson?.let { payloadJson ->
                runCatching {
                    json.decodeFromString(NetworkSnapshotModel.serializer(), payloadJson)
                }.getOrNull()
            }

        fun decodeDiagnosticContext(entity: DiagnosticContextEntity?): DiagnosticContextModel? =
            entity?.payloadJson?.let { payloadJson ->
                runCatching {
                    json.decodeFromString(DiagnosticContextModel.serializer(), payloadJson)
                }.getOrNull()
            }
    }
