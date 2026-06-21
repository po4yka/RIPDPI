package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.withLocalProxySessionOverrides
import com.poyka.ripdpi.core.withProxyLogContext
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
    withProxyLogContext(logContext)

internal fun RipDpiProxyPreferences.withSessionLocalProxyOverrides(
    listenPortOverride: Int? = null,
    authToken: String? = null,
): RipDpiProxyPreferences =
    withLocalProxySessionOverrides(
        listenPortOverride = listenPortOverride,
        authToken = authToken,
    )

internal fun RipDpiProxyPreferences.withLocalAuthToken(token: String?): RipDpiProxyPreferences =
    withSessionLocalProxyOverrides(authToken = token)
