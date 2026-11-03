package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import java.util.Locale

internal fun StringResolver.signatureHttpFakeProfileLabel(value: String): String =
    when (value.lowercase(Locale.US)) {
        "compat_default" -> getString(R.string.diagnostics_signature_compatibility_default)
        "iana_get" -> "IANA GET"
        "cloudflare_get" -> "Cloudflare GET"
        else -> value
    }
