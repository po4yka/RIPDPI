package com.poyka.ripdpi.services

import javax.inject.Inject
import javax.inject.Singleton

/** Reads Android policy from the current live service, never from persisted cached observations. */
@Singleton
class LiveVpnLockdownReader
    @Inject
    constructor() {
        private var owner: Any? = null
        private var reader: (() -> AndroidHardKillSwitchSnapshot)? = null

        @Synchronized fun register(
            owner: Any,
            reader: () -> AndroidHardKillSwitchSnapshot,
        ) {
            this.owner = owner
            this.reader = reader
        }

        @Synchronized fun unregister(owner: Any) {
            if (this.owner === owner) {
                this.owner = null
                reader = null
            }
        }

        @Synchronized
        fun read(): AndroidHardKillSwitchSnapshot = reader?.invoke() ?: AndroidHardKillSwitchStateReader.unknown()
    }
