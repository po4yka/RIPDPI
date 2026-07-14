package com.poyka.ripdpi.services

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WarpStoreMutationLock
    @Inject
    constructor() {
        val mutex = Mutex()
    }
