package com.poyka.ripdpi.integration

import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import dagger.hilt.android.testing.HiltAndroidRule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

internal fun HiltAndroidRule.injectBeforeActivityRule(): TestRule =
    TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                inject()
                base.evaluate()
            }
        }
    }

internal fun grantedPermissionSnapshot(): PermissionSnapshot =
    PermissionSnapshot(
        vpnConsent = PermissionStatus.Granted,
        notifications = PermissionStatus.Granted,
        batteryOptimization = PermissionStatus.Granted,
    )
