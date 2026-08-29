package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.LocalNetworkAccessRequiredException
import com.poyka.ripdpi.data.Mode
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

enum class ServiceStartPreflightResult {
    Allowed,
    LocalNetworkPermissionRequired,
}

fun interface ServiceStartPreflight {
    suspend fun check(mode: Mode): ServiceStartPreflightResult
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

    override suspend fun check(mode: Mode): ServiceStartPreflightResult {
        if (localNetworkGranted()) return ServiceStartPreflightResult.Allowed
        return try {
            requireLocalNetworkAccess(mode)
            ServiceStartPreflightResult.Allowed
        } catch (_: LocalNetworkAccessRequiredException) {
            ServiceStartPreflightResult.LocalNetworkPermissionRequired
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
