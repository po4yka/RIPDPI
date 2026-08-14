package com.poyka.ripdpi.activities

import com.poyka.ripdpi.security.AppLockLifecycleObserver
import javax.inject.Inject

class MainAppLockLifecycleCoordinator
    @Inject
    constructor(
        private val appLockLifecycleObserver: AppLockLifecycleObserver,
    ) {
        private var authenticated = false

        fun start(
            isBiometricEnabled: () -> Boolean,
            onRelockNeeded: () -> Unit,
        ) {
            appLockLifecycleObserver.isAuthenticated = { authenticated }
            appLockLifecycleObserver.isBiometricEnabled = isBiometricEnabled
            appLockLifecycleObserver.onRelockNeeded = {
                authenticated = false
                onRelockNeeded()
            }
            appLockLifecycleObserver.startObserving()
        }

        fun onAuthenticated() {
            authenticated = true
        }
    }
