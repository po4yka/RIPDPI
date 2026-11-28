package com.poyka.ripdpi.core

data class LocalProxySessionOverrides(
    val listenPortOverride: Int? = null,
    val listenUnixPath: String? = null,
    val authToken: String? = null,
) {
    fun overlaid(base: LocalProxySessionOverrides): LocalProxySessionOverrides =
        LocalProxySessionOverrides(
            listenPortOverride = listenPortOverride ?: base.listenPortOverride,
            listenUnixPath = listenUnixPath ?: base.listenUnixPath,
            authToken = authToken ?: base.authToken,
        )

    override fun toString(): String = "LocalProxySessionOverrides([REDACTED])"
}
