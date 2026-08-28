package com.poyka.ripdpi.data.xray

import com.poyka.ripdpi.core.data.catalog.BuildConfig

/** The pinned core version verified against the packaged libXray provenance. */
object XrayProviderBuildInfo {
    const val upstreamTag: String = BuildConfig.XRAY_CORE_VERSION
}
