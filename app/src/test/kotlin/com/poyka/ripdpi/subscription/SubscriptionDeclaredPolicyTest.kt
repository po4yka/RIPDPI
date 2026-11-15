package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionDeclaredPolicyTest {
    private val member = ProxyProfile.Trojan("member", "Node", "g", "node.example", 443, "fixture")
    private val policy = SelectorFailover("https://probe.example/204", 180, 50)
    private val group =
        ProxyGroup("g", "Group", ProxyGroupType.SUBSCRIPTION, 0, true, members = listOf(member), failover = policy)

    @Test
    fun `an explicit manual selector clears an older imported automatic policy`() {
        val refreshed = SubscriptionMemberPersistence.apply(group, listOf(member), isSelector = true)
        assertNull(refreshed.failover)
    }

    @Test
    fun `an unstructured node list does not erase an imported policy`() {
        val refreshed = SubscriptionMemberPersistence.apply(group, listOf(member))
        assertEquals(policy, refreshed.failover)
    }
}
