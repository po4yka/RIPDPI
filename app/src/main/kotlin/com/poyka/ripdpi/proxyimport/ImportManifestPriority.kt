package com.poyka.ripdpi.proxyimport

/**
 * Intent-filter priority constants for the import-handler manifest declarations.
 *
 * sing-box-for-Android registers its `singbox://import-remote-profile` filter at the
 * Android platform default priority ([SING_BOX_FOR_ANDROID_DEFAULT] = 0). RIPDPI declares
 * its deep-link filter at [RIPDPI_DEEP_LINK] — strictly below that default — so when SFA
 * is installed it stays the user's default subscription handler; RIPDPI is still offered
 * in the chooser. There is no programmatic preferred-app grab; a settings entry explains
 * how to set RIPDPI as default via the system "Open by default" screen.
 *
 * Keep [RIPDPI_DEEP_LINK] in sync with `android:priority` on the deep-link
 * `<intent-filter>` in `app/src/main/AndroidManifest.xml`.
 */
object ImportManifestPriority {
    /** The Android platform default intent-filter priority sing-box-for-Android uses. */
    const val SING_BOX_FOR_ANDROID_DEFAULT: Int = 0

    /** RIPDPI's deep-link filter priority — strictly below the SFA default. */
    const val RIPDPI_DEEP_LINK: Int = -100
}
