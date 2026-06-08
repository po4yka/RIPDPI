package com.poyka.ripdpi

import com.poyka.ripdpi.services.DnsPathPreferenceInvalidator
import com.poyka.ripdpi.services.NetworkAutoProfileBindingObserver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStartupRegistrations
    @Inject
    constructor(
        private val dnsPathPreferenceInvalidator: DnsPathPreferenceInvalidator,
        private val networkAutoProfileBindingObserver: NetworkAutoProfileBindingObserver,
    ) {
        fun registerDnsPathInvalidator() {
            dnsPathPreferenceInvalidator.register()
        }

        fun registerNetworkAutoProfiles() {
            networkAutoProfileBindingObserver.register()
        }
    }
