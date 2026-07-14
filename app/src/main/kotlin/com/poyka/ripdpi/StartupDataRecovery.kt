package com.poyka.ripdpi

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/** Startup-only recovery dependencies that must complete before other subsystems read durable state. */
@Singleton
class StartupDataRecovery
    @Inject
    constructor(
        val appCompatibilityResetter: AppCompatibilityResetter,
        val profileMutations: ProfileMutationCoordinator,
    )
