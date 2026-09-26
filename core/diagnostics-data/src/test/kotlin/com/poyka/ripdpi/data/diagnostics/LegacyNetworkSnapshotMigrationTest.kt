package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.serialization.RipDpiPrettyContractJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyNetworkSnapshotMigrationTest {
    @Test
    fun `migration 13 to 14 redacts existing snapshots and isolates malformed rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "diagnostics-v13-v14-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false).also { db ->
            db.openHelper.writableDatabase
            db.close()
        }
        seedVersion13NetworkSnapshots(context, dbName)

        val migrated = DiagnosticsDatabaseModule.buildDiagnosticsDatabase(context, dbName, false)
        try {
            val sql = migrated.openHelper.writableDatabase
            sql.query("SELECT COUNT(*) FROM network_snapshots").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(131, cursor.getInt(0))
            }
            assertMigratedWifiSnapshot(sql)
            assertMigratedCellularSnapshot(sql)
            sql.query("SELECT payloadJson FROM network_snapshots WHERE id = 'no-public-ip'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val json = Json.parseToJsonElement(cursor.getString(0)).jsonObject
                assertEquals(
                    "redacted",
                    json
                        .getValue("dnsServers")
                        .jsonArray
                        .single()
                        .jsonPrimitive.content,
                )
                assertFalse(json.containsKey("publicIp"))
            }
            sql
                .query("SELECT COUNT(*) FROM network_snapshots WHERE payloadJson LIKE '%Home WiFi%' OR id LIKE 'bad%'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            sql.query("SELECT COUNT(*) FROM native_session_events WHERE id = 'unrelated'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun seedVersion13NetworkSnapshots(
        context: Context,
        dbName: String,
    ) {
        val noPublicIpPayload =
            RipDpiPrettyContractJson.encodeToString(
                LegacyNullableSnapshotPayload(
                    transport = "wifi",
                    capabilities = listOf("internet"),
                    dnsServers = listOf("192.0.2.53"),
                    privateDnsMode = "off",
                    mtu = null,
                    localAddresses = emptyList(),
                    publicIp = null,
                    publicAsn = null,
                    captivePortalDetected = false,
                    networkValidated = true,
                    capturedAt = 125L,
                ),
            )
        assertFalse(noPublicIpPayload.contains("\"mtu\""))
        assertFalse(noPublicIpPayload.contains("\"publicIp\""))
        assertFalse(noPublicIpPayload.contains("\"publicAsn\""))
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { legacyDb ->
            repeat(129) { index ->
                legacyDb.insertSnapshot("wifi-$index", legacyWifiPayload, "scan-1", "connection-1", 123L)
            }
            legacyDb.insertSnapshot("cellular", legacyCellularPayload, "scan-2", null, 124L)
            legacyDb.insertSnapshot("no-public-ip", noPublicIpPayload, "scan-3", null, 125L)
            legacyDb.insertSnapshot("bad", "{malformed", null, null, 125L)
            legacyDb.insertSnapshot(
                "bad-extra-root",
                """{"transport":"wifi","capturedAt":125,"extra":"Home WiFi"}""",
                null,
                null,
                125L,
            )
            legacyDb.insertSnapshot("bad-missing", """{"transport":"wifi","capturedAt":125}""", null, null, 125L)
            legacyDb.insertSnapshot(
                "bad-extra-wifi",
                legacyWifiPayload.replace("\"band\":\"5 GHz\"", "\"band\":\"5 GHz\",\"extra\":\"Home WiFi\""),
                null,
                null,
                125L,
            )
            legacyDb.insertSnapshot(
                "bad-extra-path",
                legacyWifiPayload.replace(
                    "\"configuredAppCount\":2",
                    "\"configuredAppCount\":2,\"extra\":\"Home WiFi\"",
                ),
                null,
                null,
                125L,
            )
            legacyDb.insertSnapshot(
                "bad-shape",
                """{"transport":"wifi","capturedAt":125,"dnsServers":"192.0.2.53"}""",
                null,
                null,
                125L,
            )
            legacyDb.execSQL(
                "INSERT INTO native_session_events (id, source, level, message, createdAt) " +
                    "VALUES ('unrelated', 'test', 'info', 'keep', 126)",
            )
            legacyDb.execSQL("PRAGMA user_version = 13")
        }
    }

    private fun android.database.sqlite.SQLiteDatabase.insertSnapshot(
        id: String,
        payloadJson: String,
        sessionId: String?,
        connectionSessionId: String?,
        capturedAt: Long,
    ) {
        execSQL(
            "INSERT INTO network_snapshots " +
                "(id, sessionId, connectionSessionId, snapshotKind, payloadJson, capturedAt) " +
                "VALUES (?, ?, ?, 'before', ?, ?)",
            arrayOf<Any?>(id, sessionId, connectionSessionId, payloadJson, capturedAt),
        )
    }

    private fun assertMigratedWifiSnapshot(sql: SupportSQLiteDatabase) {
        sql
            .query(
                "SELECT payloadJson, sessionId, connectionSessionId, capturedAt " +
                    "FROM network_snapshots WHERE id = 'wifi-128'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val json = Json.parseToJsonElement(cursor.getString(0)).jsonObject
                assertEquals(
                    listOf("redacted", "redacted"),
                    json.getValue("dnsServers").jsonArray.map { it.jsonPrimitive.content },
                )
                assertEquals(
                    listOf("redacted"),
                    json.getValue("localAddresses").jsonArray.map { it.jsonPrimitive.content },
                )
                assertEquals("strict", json.getValue("privateDnsMode").jsonPrimitive.content)
                val wifi = json.getValue("wifiDetails").jsonObject
                assertEquals("redacted", wifi.getValue("ssid").jsonPrimitive.content)
                assertEquals("redacted", wifi.getValue("bssid").jsonPrimitive.content)
                assertEquals("null", wifi.getValue("networkId").toString())
                listOf("gateway", "dhcpServer", "ipAddress", "subnetMask").forEach { key ->
                    assertEquals("redacted", wifi.getValue(key).jsonPrimitive.content)
                }
                assertEquals("5 GHz", wifi.getValue("band").jsonPrimitive.content)
                val validation = json.getValue("pathValidation").jsonObject
                val path = json.getValue("pathSnapshots").jsonObject
                assertEquals("ok", validation.getValue("captureStatus").jsonPrimitive.content)
                assertEquals("7", path.getValue("captureGeneration").jsonPrimitive.content)
                assertEquals("198.51.100.1", json.getValue("publicIp").jsonPrimitive.content)
                assertEquals("scan-1", cursor.getString(1))
                assertEquals("connection-1", cursor.getString(2))
                assertEquals(123L, cursor.getLong(3))
            }
    }

    private fun assertMigratedCellularSnapshot(sql: SupportSQLiteDatabase) {
        sql.query("SELECT payloadJson FROM network_snapshots WHERE id = 'cellular'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            val cellular =
                Json
                    .parseToJsonElement(cursor.getString(0))
                    .jsonObject
                    .getValue("cellularDetails")
                    .jsonObject
            listOf("carrierName", "simOperatorName", "networkOperatorName", "operatorCode", "simOperatorCode")
                .forEach { key -> assertEquals("redacted", cellular.getValue(key).jsonPrimitive.content) }
            assertEquals("null", cellular.getValue("carrierId").toString())
            assertEquals("null", cellular.getValue("simCarrierId").toString())
            assertEquals("3", cellular.getValue("signalLevel").jsonPrimitive.content)
        }
    }
}

private val legacyWifiPayload =
    """
    {
      "transport":"wifi","capabilities":["internet"],
      "dnsServers":["192.0.2.53","192.0.2.54"],"privateDnsMode":"dns.example",
      "mtu":1500,"localAddresses":["192.0.2.2"],"publicIp":"198.51.100.1",
      "publicAsn":"AS123","captivePortalDetected":false,"networkValidated":true,
      "wifiDetails":{"ssid":"Home WiFi","bssid":"aa:bb:cc:dd:ee:ff",
        "networkId":42,"gateway":"192.0.2.1","dhcpServer":"192.0.2.3",
        "ipAddress":"192.0.2.2","subnetMask":"255.255.255.0","band":"5 GHz"},
      "pathValidation":{"captureStatus":"ok","vpnRouteEvidence":{
        "addressFamilies":["ipv4"],"configuredAppCount":2}},
      "pathSnapshots":{"captureGeneration":7,"vpn":{"association":"service_binder",
        "addressCount":1},"underlay":{"transport":"wifi","dnsServerCount":2}},
      "capturedAt":123
    }
    """.trimIndent()
private val legacyCellularPayload =
    """
    {
      "transport":"cellular","capabilities":[],"dnsServers":[],
      "privateDnsMode":"off","mtu":null,"localAddresses":[],
      "publicIp":null,"publicAsn":null,"captivePortalDetected":false,
      "networkValidated":true,"cellularDetails":{"carrierName":"Carrier Secret",
        "simOperatorName":"SIM Secret","networkOperatorName":"Network Secret",
        "operatorCode":"12345","simOperatorCode":"67890","carrierId":77,
        "simCarrierId":88,"signalLevel":3},"capturedAt":124
    }
    """.trimIndent()

@Serializable
private data class LegacyNullableSnapshotPayload(
    val transport: String,
    val capabilities: List<String>,
    val dnsServers: List<String>,
    val privateDnsMode: String,
    val mtu: Int?,
    val localAddresses: List<String>,
    val publicIp: String?,
    val publicAsn: String?,
    val captivePortalDetected: Boolean,
    val networkValidated: Boolean,
    val capturedAt: Long,
)
