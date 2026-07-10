package com.poyka.ripdpi.subscription

import android.app.Application
import android.app.NotificationManager
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SubscriptionStatusNotifierTest {
    private lateinit var application: Application
    private lateinit var manager: NotificationManager
    private lateinit var notifier: SubscriptionStatusNotifier

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        manager = application.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        application
            .getSharedPreferences("subscription_status_notifications", Application.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        notifier = SubscriptionStatusNotifier(application)
    }

    @Test
    fun `first actionable state posts one redacted deep-linked notification`() {
        notifier.publish(listOf(expiredGroup()), nowEpochMillis = 1_000L)

        val notification = shadowOf(manager).getNotification(SubscriptionStatusNotifier.NotificationId)
        assertNotNull(notification)
        assertTrue(
            notification.extras
                .getString("android.text")
                .orEmpty()
                .contains("Fixture subscription"),
        )
        assertTrue(
            !notification.extras
                .getString("android.text")
                .orEmpty()
                .contains("secret-token"),
        )
        assertEquals(
            SubscriptionStatusNotifier.DeepLink,
            shadowOf(notification.contentIntent).savedIntent.dataString,
        )
    }

    @Test
    fun `repeated fingerprint is deduplicated and recovery cancels notification`() {
        val groups = listOf(expiredGroup())
        notifier.publish(groups, nowEpochMillis = 1_000L)
        val first = shadowOf(manager).getNotification(SubscriptionStatusNotifier.NotificationId)

        notifier.publish(groups, nowEpochMillis = 2_000L)
        val repeated = shadowOf(manager).getNotification(SubscriptionStatusNotifier.NotificationId)
        assertTrue(first === repeated)

        notifier.publish(emptyList(), nowEpochMillis = 3_000L)
        assertEquals(null, shadowOf(manager).getNotification(SubscriptionStatusNotifier.NotificationId))
    }

    @Test
    fun `changed aggregate state replaces the existing notification`() {
        notifier.publish(listOf(expiredGroup()), nowEpochMillis = 1_000L)
        val expiredText =
            shadowOf(manager)
                .getNotification(SubscriptionStatusNotifier.NotificationId)
                .extras
                .getString("android.text")

        notifier.publish(listOf(unavailableGroup()), nowEpochMillis = 2_000L)
        val unavailableText =
            shadowOf(manager)
                .getNotification(SubscriptionStatusNotifier.NotificationId)
                .extras
                .getString("android.text")

        assertNotEquals(expiredText, unavailableText)
        assertTrue(unavailableText.orEmpty().contains("unavailable"))
    }

    private fun expiredGroup(): ProxyGroup =
        ProxyGroup(
            id = "group-id",
            name = "Fixture subscription",
            type = ProxyGroupType.SUBSCRIPTION,
            order = 0,
            isSelector = false,
            subscription =
                Subscription(
                    link = "https://example.test/sub/secret-token",
                    lifecycleState = SubscriptionLifecycleState.EXPIRED,
                ),
        )

    private fun unavailableGroup(): ProxyGroup =
        expiredGroup().copy(
            subscription =
                expiredGroup().subscription?.copy(
                    lifecycleState = SubscriptionLifecycleState.UNAVAILABLE,
                ),
        )
}
