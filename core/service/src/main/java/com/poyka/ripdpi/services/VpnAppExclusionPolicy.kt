package com.poyka.ripdpi.services

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface VpnAppExclusionPolicy {
    fun shouldExcludeOwnPackage(): Boolean
}

@Singleton
class DefaultVpnAppExclusionPolicy
    @Inject
    constructor() : VpnAppExclusionPolicy {
        override fun shouldExcludeOwnPackage(): Boolean = true
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnAppExclusionPolicyModule {
    @Binds
    @Singleton
    abstract fun bindVpnAppExclusionPolicy(policy: DefaultVpnAppExclusionPolicy): VpnAppExclusionPolicy
}
