package com.poyka.ripdpi.services.routing

import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationRoutingPolicySourceTest {
    @Test
    fun `loads and compiles one immutable ordered repository snapshot`() =
        runTest {
            var loads = 0
            val source =
                RoomDestinationRoutingPolicySource {
                    loads += 1
                    listOf(
                        RuleEntity(
                            id = 2,
                            userOrder = 2,
                            domains = ".direct.example",
                            outboundTag = OutboundTag.Bypass,
                        ),
                        RuleEntity(
                            id = 1,
                            userOrder = 1,
                            domains = "blocked.example",
                            outboundTag = OutboundTag.Block,
                        ),
                    )
                }

            val snapshot = source.snapshot() as DestinationRoutingPolicySnapshot.Available

            assertEquals(1, loads)
            assertEquals(
                listOf(DestinationRoutingAction.BLOCK, DestinationRoutingAction.DIRECT),
                snapshot.policy.rules.map { it.action },
            )
            assertTrue(snapshot.policy.canonicalDigest.isNotEmpty())
        }

    @Test
    fun `reports compiler failure without publishing a partial policy`() =
        runTest {
            val source =
                RoomDestinationRoutingPolicySource {
                    listOf(
                        RuleEntity(id = 7, userOrder = 1, domains = "a.example"),
                        RuleEntity(id = 8, userOrder = 1, domains = "b.example"),
                    )
                }

            val snapshot = source.snapshot()

            assertTrue(snapshot is DestinationRoutingPolicySnapshot.Unavailable)
            assertTrue(
                (snapshot as DestinationRoutingPolicySnapshot.Unavailable).reason.contains("DUPLICATE_RULE_ORDER"),
            )
        }

    @Test
    fun `maps repository failure to unavailable without aborting policy resolution`() =
        runTest {
            val source = RoomDestinationRoutingPolicySource { error("room unavailable") }

            val snapshot = source.snapshot()

            assertEquals(
                DestinationRoutingPolicySnapshot.Unavailable("rule_source_unavailable"),
                snapshot,
            )
        }
}
