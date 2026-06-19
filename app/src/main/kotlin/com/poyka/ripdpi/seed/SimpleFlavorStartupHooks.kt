package com.poyka.ripdpi.seed

import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates the optional simple-flavor startup hooks so [com.poyka.ripdpi.AppStartupInitializer]
 * depends on one type instead of one parameter per hook. Both members are empty on every flavor
 * other than `simple`, where Hilt supplies the real bindings.
 */
@Singleton
class SimpleFlavorStartupHooks
    @Inject
    constructor(
        val seeder: Optional<SimpleFlavorSeeder>,
        val sessionWatcher: Optional<SimpleFlavorSessionWatcher>,
    )
