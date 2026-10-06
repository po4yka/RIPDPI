package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.LocalNetworkAccessRequiredException
import com.poyka.ripdpi.data.Mode
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

enum class LocalNetworkStartPreflightResult {
    Allowed,
    LocalNetworkPermissionRequired,
}

fun interface ServiceStartPreflight {
    suspend fun check(mode: Mode): LocalNetworkStartPreflightResult
}

@Singleton
class DefaultServiceStartPreflight internal constructor(
    private val localNetworkGranted: () -> Boolean,
    private val requireLocalNetworkAccess: suspend (Mode) -> Unit,
) : ServiceStartPreflight {
    @Inject
    constructor(
        runtimePermissionChecker: RuntimePermissionChecker,
        localNetworkPreflight: ServiceStartLocalNetworkPreflight,
    ) : this(
        localNetworkGranted = { runtimePermissionChecker.check().localNetworkGranted },
        requireLocalNetworkAccess = localNetworkPreflight::requireAccess,
    )

    override suspend fun check(mode: Mode): LocalNetworkStartPreflightResult {
        if (localNetworkGranted()) return LocalNetworkStartPreflightResult.Allowed
        return try {
            requireLocalNetworkAccess(mode)
            LocalNetworkStartPreflightResult.Allowed
        } catch (_: LocalNetworkAccessRequiredException) {
            LocalNetworkStartPreflightResult.LocalNetworkPermissionRequired
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceStartPreflightModule {
    @Binds
    @Singleton
    abstract fun bindServiceStartPreflight(preflight: DefaultServiceStartPreflight): ServiceStartPreflight
}
