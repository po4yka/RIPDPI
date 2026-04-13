package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.RipDpiProxyJsonPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy

internal fun ServiceRuntimeSession.buildLogContext(
    activePolicy: ActiveConnectionPolicy?,
    diagnosticsSessionId: String? = null,
): RipDpiLogContext =
    RipDpiLogContext(
        runtimeId = runtimeId,
        mode = mode.preferenceValue,
        policySignature = activePolicy?.policySignature,
        fingerprintHash = activePolicy?.fingerprintHash,
        diagnosticsSessionId = diagnosticsSessionId,
    )

internal fun RipDpiProxyPreferences.withLogContext(logContext: RipDpiLogContext?): RipDpiProxyPreferences =
    if (logContext == null) {
        this
    } else {
        RipDpiProxyJsonPreferences(
            configJson = toNativeConfigJson(),
            logContext = logContext,
        )
    }

internal fun RipDpiProxyPreferences.withSessionLocalProxyOverrides(
    listenPortOverride: Int? = null,
    authToken: String? = null,
): RipDpiProxyPreferences =
    RipDpiProxyJsonPreferences(
        configJson = toNativeConfigJson(),
        localListenPortOverride = listenPortOverride,
        localAuthToken = authToken,
    )

internal fun RipDpiProxyPreferences.withLocalAuthToken(token: String?): RipDpiProxyPreferences =
    withSessionLocalProxyOverrides(authToken = token)
