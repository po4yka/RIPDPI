package com.poyka.ripdpi.core

import javax.inject.Inject

/** Validates a complete local geo database without changing an active runtime. */
fun interface GeoDatabaseValidator {
    /** Throws [IllegalArgumentException] when [path] does not contain the selected database format. */
    fun validate(
        path: String,
        isGeosite: Boolean,
    )
}

class NativeGeoDatabaseValidator
    @Inject
    constructor() : GeoDatabaseValidator {
        init {
            RipDpiNativeLoader.ensureLoaded()
        }

        override fun validate(
            path: String,
            isGeosite: Boolean,
        ) {
            require(jniValidateGeoDatabase(path, isGeosite)) { "Geo database validation failed" }
        }

        private external fun jniValidateGeoDatabase(
            path: String,
            isGeosite: Boolean,
        ): Boolean
    }
