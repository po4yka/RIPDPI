@file:Suppress("ReturnCount", "MagicNumber", "TooGenericExceptionCaught")

package com.poyka.ripdpi.core.detection.checker

import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class CellTowerSignal(
    val radio: String,
    val mcc: String,
    val mnc: String,
    val areaCode: Long,
    val cellId: Long,
    val signalStrength: Int? = null,
)

internal data class WifiAccessPointSignal(
    val macAddress: String,
    val signalStrength: Int? = null,
    val frequency: Int? = null,
)

internal data class BeaconDbLookupResult(
    val countryIso: String?,
    val summary: String,
)

internal class BeaconDbClient(
    private val countryResolver: (Double, Double) -> String?,
    private val request: (String) -> HttpResult = ::defaultRequest,
) {
    data class HttpResult(
        val code: Int,
        val body: String,
    )

    fun lookup(
        cellTowers: List<CellTowerSignal>,
        wifiAccessPoints: List<WifiAccessPointSignal>,
    ): BeaconDbLookupResult {
        val supportedCells = cellTowers.take(MaxCellTowers)
        val wifiForLookup = wifiAccessPoints.take(MaxWifiAccessPoints)
        if (supportedCells.isEmpty() && wifiForLookup.isEmpty()) {
            return BeaconDbLookupResult(
                countryIso = null,
                summary = "BeaconDB: insufficient radio data",
            )
        }

        val response =
            try {
                request(buildRequestBody(supportedCells, wifiForLookup))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return BeaconDbLookupResult(
                    countryIso = null,
                    summary = "BeaconDB: ${error.message ?: "request failed"}",
                )
            }

        if (response.code !in 200..299) {
            return BeaconDbLookupResult(
                countryIso = null,
                summary = "BeaconDB: lookup failed ${response.code}",
            )
        }

        val coordinates =
            parseCoordinates(response.body)
                ?: return BeaconDbLookupResult(
                    countryIso = null,
                    summary = "BeaconDB: response missing coordinates",
                )
        val country =
            runCatching {
                countryResolver(coordinates.latitude, coordinates.longitude)
            }.getOrNull()?.uppercase(Locale.US)
        return BeaconDbLookupResult(
            countryIso = country,
            summary = "BeaconDB: exact match",
        )
    }

    internal fun buildRequestBody(
        cellTowers: List<CellTowerSignal>,
        wifiAccessPoints: List<WifiAccessPointSignal>,
    ): String {
        val cellJson =
            cellTowers.take(MaxCellTowers).joinToString(",") { tower ->
                buildString {
                    append("{")
                    append("\"radioType\":\"").append(escapeJson(tower.radio.lowercase(Locale.US))).append("\"")
                    append(",\"mobileCountryCode\":").append(tower.mcc.toInt())
                    append(",\"mobileNetworkCode\":").append(tower.mnc.toInt())
                    append(",\"locationAreaCode\":").append(tower.areaCode)
                    append(",\"cellId\":").append(tower.cellId)
                    tower.signalStrength?.let { append(",\"signalStrength\":").append(it) }
                    append("}")
                }
            }
        val wifiJson =
            wifiAccessPoints.take(MaxWifiAccessPoints).joinToString(",") { accessPoint ->
                buildString {
                    append("{")
                    append("\"macAddress\":\"").append(escapeJson(accessPoint.macAddress)).append("\"")
                    accessPoint.signalStrength?.let { append(",\"signalStrength\":").append(it) }
                    accessPoint.frequency?.let { append(",\"frequency\":").append(it) }
                    append("}")
                }
            }
        return buildString {
            append("{\"considerIp\":false")
            append(",\"cellTowers\":[")
            append(cellJson)
            append("]")
            if (wifiJson.isNotEmpty()) {
                append(",\"wifiAccessPoints\":[")
                append(wifiJson)
                append("]")
            }
            append("}")
        }
    }

    private data class Coordinates(
        val latitude: Double,
        val longitude: Double,
    )

    private fun parseCoordinates(body: String): Coordinates? {
        val locationBody = LocationBlockRegex.find(body)?.groupValues?.get(1) ?: return null
        val latitude =
            doubleFieldRegex("lat")
                .find(locationBody)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()
                ?: return null
        val longitude =
            doubleFieldRegex("lng")
                .find(locationBody)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()
                ?: return null
        return Coordinates(latitude = latitude, longitude = longitude)
    }

    private fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        private const val LookupUrl = "https://api.beacondb.net/v1/geolocate"
        private const val MaxCellTowers = 6
        private const val MaxWifiAccessPoints = 12
        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
        private val LocationBlockRegex = Regex("\"location\"\\s*:\\s*\\{([\\s\\S]*?)\\}")

        private fun doubleFieldRegex(name: String): Regex = Regex("\"$name\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")

        private fun defaultRequest(body: String): HttpResult {
            val client =
                OkHttpClient
                    .Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(LookupUrl)
                    .header("Accept", "application/json")
                    .header("User-Agent", "RIPDPI")
                    .post(body.toRequestBody(JsonMediaType))
                    .build()
            client.newCall(request).execute().use { response ->
                return HttpResult(
                    code = response.code,
                    body = response.body.string(),
                )
            }
        }
    }
}
