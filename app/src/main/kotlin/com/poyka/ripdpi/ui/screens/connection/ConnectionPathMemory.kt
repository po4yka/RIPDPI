package com.poyka.ripdpi.ui.screens.connection

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionPathMemory
    @Inject
    constructor() {
        @Volatile
        var browsingRelay: Boolean = false
    }
