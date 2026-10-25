package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.DiagnosticsNetworkEpoch
import com.poyka.ripdpi.data.Hysteria2PortHopping
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupSchema
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.subscription.MigrationPathTest
import com.poyka.ripdpi.data.subscription.migrationFingerprint
import com.poyka.ripdpi.data.subscription.migrationSha256
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** These are coordinator contract tests with controlled probe outcomes, not live path evidence. */
class DeviceMigrationHysteriaUpgradeTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `hopping storage upgrade requires reimport and a new path test`() = runTest { verifyUpgrade(hopping = true) }

    @Test
    fun `legacy null hopping fields also invalidate old receipt authority`() =
        runTest { verifyUpgrade(hopping = false) }

    private suspend fun verifyUpgrade(hopping: Boolean) {
        val fixture = fixture(1000)
        val body = payload(hopping)
        server.enqueue(response(200, body))
        assertTrue(fixture.coordinator.refresh(GroupId) is SubscriptionRefreshResult.Updated)
        val original = fixture.repository.list().single()
        val upgraded = migrateLegacy(original, hopping)
        fixture.repository.update(upgraded)
        val authority = upgraded.subscription!!.migration!!
        val expectedPolicy = if (hopping) Hysteria2PortHopping("20000-21000", 45_000L) else null
        var succeeded = false
        var measurements = 0
        val coordinator =
            DeviceMigrationCoordinator(
                groups = fixture.repository,
                loadAwg = { null },
                withCurrentAwg = { _, _ -> false },
                readCurrentAwg = { _, _ -> false },
                mutationGeneration = { 0 },
                relayProbe = { profile, url ->
                    measurements++
                    assertEquals(ProbeUrl, url)
                    assertEquals(expectedPolicy, (profile as ProxyProfile.Hysteria2).portHopping)
                    assertEquals(expectedPolicy, checkNotNull(mapRelayProfile(profile)).profile.hysteriaPortHopping)
                    succeeded
                },
                awgProbe = { _, _ -> error("Not an AWG target") },
                epoch = { Epoch },
                now = { 2000 },
            )
        assertEquals("received", receiptState(coordinator.receipt(GroupId)))
        assertFalse(coordinator.hasCurrentTest(GroupId))
        assertEquals(MigrationCheckResult.InvalidTarget, coordinator.check(GroupId, "green", ProbeUrl))
        assertEquals(0, measurements)
        assertEquals(
            authority,
            fixture.repository
                .list()
                .single()
                .subscription!!
                .migration,
        )

        server.enqueue(response(200, body))
        assertTrue(fixture.coordinator.refresh(GroupId) is SubscriptionRefreshResult.Updated)
        val refreshed = fixture.repository.list().single()
        assertEquals(
            refreshed.members.single().migrationFingerprint(),
            refreshed.subscription!!
                .migration!!
                .targets
                .single()
                .fingerprint,
        )
        assertFalse(coordinator.hasCurrentTest(GroupId))
        assertEquals(MigrationCheckResult.Failed, coordinator.check(GroupId, "green", ProbeUrl))
        assertEquals("received", receiptState(coordinator.receipt(GroupId)))
        succeeded = true
        assertEquals(MigrationCheckResult.Tested, coordinator.check(GroupId, "green", ProbeUrl))
        assertEquals(2, measurements)
        assertTrue(coordinator.hasCurrentTest(GroupId))
        assertEquals("path-tested", receiptState(coordinator.receipt(GroupId)))
    }

    private fun migrateLegacy(
        group: ProxyGroup,
        hopping: Boolean,
    ): ProxyGroup {
        val profile = group.members.single() as ProxyProfile.Hysteria2
        val encoded = RipDpiEncodeDefaultsJson.encodeToJsonElement(ProxyProfile.serializer(), profile).jsonObject
        val legacyProfile =
            JsonObject(
                encoded.entries
                    .flatMap { (key, value) ->
                        if (key == "portHopping") {
                            listOf(
                                "portHopPorts" to if (hopping) JsonPrimitive("20000:21000") else JsonNull,
                                "portHopInterval" to if (hopping) JsonPrimitive("45s") else JsonNull,
                            )
                        } else {
                            listOf(key to value)
                        }
                    }.toMap(),
            )
        val legacyFingerprint = migrationSha256(legacyProfile.toString().toByteArray(Charsets.UTF_8))
        assertNotEquals(profile.migrationFingerprint(), legacyFingerprint)
        val delivery = group.subscription!!.migration!!
        val tested =
            delivery.copy(
                targets = listOf(delivery.targets.single().copy(fingerprint = legacyFingerprint)),
                pathTest = MigrationPathTest("green", 1500, legacyFingerprint),
            )
        val before =
            RipDpiJson
                .encodeToJsonElement(
                    ProxyGroup.serializer(),
                    group.copy(subscription = group.subscription!!.copy(migration = tested)),
                ).jsonObject
        val raw = JsonArray(listOf(JsonObject(before + ("members" to JsonArray(listOf(legacyProfile)))))).toString()
        var persisted: String? = null
        val migrated = ProxyGroupSchema.read(raw, ProxyGroupSchema.SCHEMA_VERSION) { persisted = it }
        assertEquals(migrated, persisted)
        val after =
            RipDpiJson
                .parseToJsonElement(migrated)
                .jsonArray
                .single()
                .jsonObject
        assertEquals(before.getValue("subscription").toString(), after.getValue("subscription").toString())
        val upgraded = RipDpiJson.decodeFromString(ListSerializer(ProxyGroup.serializer()), migrated).single()
        assertEquals(group.ownershipGeneration, upgraded.ownershipGeneration)
        assertEquals(tested, upgraded.subscription!!.migration)
        assertEquals(profile, upgraded.members.single())
        return upgraded
    }

    private fun receiptState(receipt: String?): String =
        RipDpiJson
            .parseToJsonElement(checkNotNull(receipt))
            .jsonObject
            .getValue("state")
            .jsonPrimitive.content

    private fun payload(hopping: Boolean): String {
        val extras =
            if (hopping) {
                """, "hysteria_extras":{"green":{"port_hopping":{"ports":"20000:21000","interval":"45s"}}}"""
            } else {
                ""
            }
        return """
            {"outbounds":[{"type":"hysteria2","tag":"green","server":"new.example",
            "server_port":443,"password":"fixture"}],
            "ripdpi":{"schema_version":2$extras,"migration":{"schema_version":1,
            "migration_id":"11111111-1111-1111-1111-111111111111",
            "delivery_id":"22222222-2222-2222-2222-222222222222",
            "target_tags":["green"],"receipt_key":"${"a".repeat(64)}","awg_previous_tags":{}}}}
            """.trimIndent()
    }

    private data object Epoch : DiagnosticsNetworkEpoch

    companion object {
        private const val GroupId = "subscription-group"
        private const val ProbeUrl = "https://probe.example/body"
    }
}
