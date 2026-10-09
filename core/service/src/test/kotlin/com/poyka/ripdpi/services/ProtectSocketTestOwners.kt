package com.poyka.ripdpi.services

internal fun testActiveProtectSocketPathProvider(): ActiveProtectSocketPathProvider =
    ActiveProtectSocketPathProvider().apply { set("/test-owned-protect") { true } }
