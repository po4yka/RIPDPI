package com.poyka.ripdpi.diagnostics

import android.net.LinkProperties
import android.os.Build

internal fun linkPropertiesMtuOrNull(linkProperties: LinkProperties?): Int? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        linkProperties?.mtu?.takeIf { it > 0 }
    } else {
        null
    }
