package com.poyka.ripdpi.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Reject values that the version 13 network snapshot model could not decode. */
internal fun requireLegacyNetworkSnapshotShape(snapshot: JsonObject) {
    snapshot.requireShape(rootFields, requiredRootFields)
}

private enum class FieldShape {
    STRING,
    NULLABLE_STRING,
    STRINGS,
    LONG,
    NULLABLE_LONG,
    NULLABLE_INT,
    BOOLEAN,
    NULLABLE_BOOLEAN,
    WIFI,
    CELLULAR,
    PATH_VALIDATION,
    PATH_PAIR,
    ROUTE_EVIDENCE,
    PATH_OBSERVATION,
}

private val rootFields =
    mapOf(
        "transport" to FieldShape.STRING,
        "capabilities" to FieldShape.STRINGS,
        "dnsServers" to FieldShape.STRINGS,
        "privateDnsMode" to FieldShape.STRING,
        "mtu" to FieldShape.NULLABLE_INT,
        "localAddresses" to FieldShape.STRINGS,
        "publicIp" to FieldShape.NULLABLE_STRING,
        "publicAsn" to FieldShape.NULLABLE_STRING,
        "captivePortalDetected" to FieldShape.BOOLEAN,
        "networkValidated" to FieldShape.BOOLEAN,
        "systemPrivateDnsStatus" to FieldShape.STRING,
        "wifiDetails" to FieldShape.WIFI,
        "cellularDetails" to FieldShape.CELLULAR,
        "pathValidation" to FieldShape.PATH_VALIDATION,
        "pathSnapshots" to FieldShape.PATH_PAIR,
        "capturedAt" to FieldShape.LONG,
    )

private val requiredRootFields =
    setOf(
        "transport",
        "capabilities",
        "dnsServers",
        "privateDnsMode",
        "localAddresses",
        "captivePortalDetected",
        "networkValidated",
        "capturedAt",
    )

private val wifiFields =
    mapOf(
        "ssid" to FieldShape.STRING,
        "bssid" to FieldShape.STRING,
        "hiddenSsid" to FieldShape.NULLABLE_BOOLEAN,
        "frequencyMhz" to FieldShape.NULLABLE_INT,
        "band" to FieldShape.STRING,
        "channelWidth" to FieldShape.STRING,
        "wifiStandard" to FieldShape.STRING,
        "rssiDbm" to FieldShape.NULLABLE_INT,
        "linkSpeedMbps" to FieldShape.NULLABLE_INT,
        "rxLinkSpeedMbps" to FieldShape.NULLABLE_INT,
        "txLinkSpeedMbps" to FieldShape.NULLABLE_INT,
        "networkId" to FieldShape.NULLABLE_INT,
        "isPasspoint" to FieldShape.NULLABLE_BOOLEAN,
        "isOsuAp" to FieldShape.NULLABLE_BOOLEAN,
        "gateway" to FieldShape.NULLABLE_STRING,
        "dhcpServer" to FieldShape.NULLABLE_STRING,
        "ipAddress" to FieldShape.NULLABLE_STRING,
        "subnetMask" to FieldShape.NULLABLE_STRING,
        "leaseDurationSeconds" to FieldShape.NULLABLE_INT,
    )

private val cellularFields =
    mapOf(
        "carrierName" to FieldShape.STRING,
        "simOperatorName" to FieldShape.STRING,
        "networkOperatorName" to FieldShape.STRING,
        "networkCountryIso" to FieldShape.STRING,
        "simCountryIso" to FieldShape.STRING,
        "operatorCode" to FieldShape.STRING,
        "simOperatorCode" to FieldShape.STRING,
        "dataNetworkType" to FieldShape.STRING,
        "voiceNetworkType" to FieldShape.STRING,
        "dataState" to FieldShape.STRING,
        "serviceState" to FieldShape.STRING,
        "isNetworkRoaming" to FieldShape.NULLABLE_BOOLEAN,
        "carrierId" to FieldShape.NULLABLE_INT,
        "simCarrierId" to FieldShape.NULLABLE_INT,
        "signalLevel" to FieldShape.NULLABLE_INT,
        "signalDbm" to FieldShape.NULLABLE_INT,
    )

private val pathValidationFields =
    mapOf(
        "captureStatus" to FieldShape.STRING,
        "callingDefaultObserverRole" to FieldShape.STRING,
        "underlayAssociation" to FieldShape.STRING,
        "underlayGeneration" to FieldShape.NULLABLE_LONG,
        "underlayPresent" to FieldShape.NULLABLE_BOOLEAN,
        "underlayTransport" to FieldShape.NULLABLE_STRING,
        "underlayInternet" to FieldShape.NULLABLE_BOOLEAN,
        "underlayValidated" to FieldShape.NULLABLE_BOOLEAN,
        "underlayCaptivePortal" to FieldShape.NULLABLE_BOOLEAN,
        "vpnAssociation" to FieldShape.STRING,
        "vpnPresent" to FieldShape.NULLABLE_BOOLEAN,
        "vpnInternet" to FieldShape.NULLABLE_BOOLEAN,
        "vpnValidated" to FieldShape.NULLABLE_BOOLEAN,
        "vpnCaptivePortal" to FieldShape.NULLABLE_BOOLEAN,
        "vpnRouteEvidence" to FieldShape.ROUTE_EVIDENCE,
    )

private val routeEvidenceFields =
    mapOf(
        "observerRole" to FieldShape.STRING,
        "observerSource" to FieldShape.STRING,
        "lifecycleGeneration" to FieldShape.NULLABLE_LONG,
        "lifecycleState" to FieldShape.STRING,
        "callbackState" to FieldShape.STRING,
        "callbackRevision" to FieldShape.NULLABLE_LONG,
        "ownerVerification" to FieldShape.STRING,
        "evidenceAgeBand" to FieldShape.STRING,
        "intendedDefaultRouteFamilies" to FieldShape.STRINGS,
        "observedDefaultRouteFamilies" to FieldShape.STRINGS,
        "addressFamilies" to FieldShape.STRINGS,
        "dnsServerFamilies" to FieldShape.STRINGS,
        "appRoutingShape" to FieldShape.STRING,
        "configuredAppCount" to FieldShape.NULLABLE_INT,
        "ownPackageExcluded" to FieldShape.NULLABLE_BOOLEAN,
        "mtuBand" to FieldShape.STRING,
        "metered" to FieldShape.NULLABLE_BOOLEAN,
        "appliedTunnelReceiptGeneration" to FieldShape.NULLABLE_LONG,
        "routeConsistency" to FieldShape.STRING,
        "vpnPresent" to FieldShape.NULLABLE_BOOLEAN,
        "hasInternet" to FieldShape.NULLABLE_BOOLEAN,
        "validated" to FieldShape.NULLABLE_BOOLEAN,
        "captivePortal" to FieldShape.NULLABLE_BOOLEAN,
        "forwardingOutcome" to FieldShape.STRING,
        "forwardingLifecycleGeneration" to FieldShape.NULLABLE_LONG,
        "forwardingTerminal" to FieldShape.NULLABLE_BOOLEAN,
    )

private val pathPairFields =
    mapOf(
        "captureGeneration" to FieldShape.LONG,
        "vpn" to FieldShape.PATH_OBSERVATION,
        "underlay" to FieldShape.PATH_OBSERVATION,
    )

private val pathObservationFields =
    mapOf(
        "association" to FieldShape.STRING,
        "generation" to FieldShape.NULLABLE_LONG,
        "transport" to FieldShape.STRING,
        "hasInternet" to FieldShape.NULLABLE_BOOLEAN,
        "validated" to FieldShape.NULLABLE_BOOLEAN,
        "captivePortal" to FieldShape.NULLABLE_BOOLEAN,
        "metered" to FieldShape.NULLABLE_BOOLEAN,
        "roaming" to FieldShape.NULLABLE_BOOLEAN,
        "suspended" to FieldShape.NULLABLE_BOOLEAN,
        "congested" to FieldShape.NULLABLE_BOOLEAN,
        "restricted" to FieldShape.NULLABLE_BOOLEAN,
        "addressFamilies" to FieldShape.STRINGS,
        "defaultRouteFamilies" to FieldShape.STRINGS,
        "dnsServerFamilies" to FieldShape.STRINGS,
        "addressCount" to FieldShape.NULLABLE_INT,
        "routeCount" to FieldShape.NULLABLE_INT,
        "dnsServerCount" to FieldShape.NULLABLE_INT,
        "countsTruncated" to FieldShape.BOOLEAN,
        "nat64Present" to FieldShape.NULLABLE_BOOLEAN,
        "privateDnsCategory" to FieldShape.STRING,
        "mtuBand" to FieldShape.STRING,
        "upstreamBandwidthBand" to FieldShape.STRING,
        "downstreamBandwidthBand" to FieldShape.STRING,
    )

private fun JsonObject.requireShape(
    fields: Map<String, FieldShape>,
    required: Set<String> = emptySet(),
) {
    require(keys.containsAll(required))
    for ((key, value) in this) {
        val shape = fields[key] ?: throw IllegalArgumentException("Unknown snapshot field")
        value.requireShape(shape)
    }
}

private fun JsonElement.requireShape(shape: FieldShape) {
    if (this == JsonNull && shape in nullableShapes) return
    when (shape) {
        FieldShape.WIFI -> requireObject(wifiFields)
        FieldShape.CELLULAR -> requireObject(cellularFields)
        FieldShape.PATH_VALIDATION -> requireObject(pathValidationFields, setOf("captureStatus"))
        FieldShape.ROUTE_EVIDENCE -> requireObject(routeEvidenceFields)
        FieldShape.PATH_PAIR -> requireObject(pathPairFields, pathPairFields.keys)
        FieldShape.PATH_OBSERVATION -> requireObject(pathObservationFields)
        else -> requirePrimitiveShape(shape)
    }
}

private fun JsonElement.requirePrimitiveShape(shape: FieldShape) {
    when (shape) {
        FieldShape.STRING, FieldShape.NULLABLE_STRING -> {
            require(this is JsonPrimitive && isString)
        }

        FieldShape.STRINGS -> {
            require(this is JsonArray && all { it is JsonPrimitive && it.isString })
        }

        FieldShape.LONG, FieldShape.NULLABLE_LONG -> {
            require(this is JsonPrimitive && !isString && longOrNull != null)
        }

        FieldShape.NULLABLE_INT -> {
            require(this is JsonPrimitive && !isString && intOrNull != null)
        }

        FieldShape.BOOLEAN, FieldShape.NULLABLE_BOOLEAN -> {
            require(this is JsonPrimitive && !isString && booleanOrNull != null)
        }

        else -> {
            error("Object shape requires an object validator")
        }
    }
}

private val nullableShapes =
    setOf(
        FieldShape.NULLABLE_STRING,
        FieldShape.NULLABLE_LONG,
        FieldShape.NULLABLE_INT,
        FieldShape.NULLABLE_BOOLEAN,
        FieldShape.WIFI,
        FieldShape.CELLULAR,
        FieldShape.PATH_VALIDATION,
        FieldShape.ROUTE_EVIDENCE,
        FieldShape.PATH_PAIR,
    )

private fun JsonElement.requireObject(
    fields: Map<String, FieldShape>,
    required: Set<String> = emptySet(),
) {
    require(this is JsonObject)
    requireShape(fields, required)
}
