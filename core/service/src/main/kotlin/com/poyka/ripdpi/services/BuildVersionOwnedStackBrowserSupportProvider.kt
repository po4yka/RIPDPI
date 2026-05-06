package com.poyka.ripdpi.services

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

private const val OwnedStackHttpEngineMinSdk = 34
private const val OwnedStackAndroid17ApiLevel = 37

@Singleton
class BuildVersionOwnedStackBrowserSupportProvider
    @Inject
    constructor() : OwnedStackBrowserSupportProvider {
        override fun current(): OwnedStackBrowserSupport =
            OwnedStackBrowserSupport(
                platformHttpEngineAvailable = Build.VERSION.SDK_INT >= OwnedStackHttpEngineMinSdk,
                android17EchEligible = Build.VERSION.SDK_INT >= OwnedStackAndroid17ApiLevel,
            )
    }
