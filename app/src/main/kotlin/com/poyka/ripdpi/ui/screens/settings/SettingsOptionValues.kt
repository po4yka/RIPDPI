package com.poyka.ripdpi.ui.screens.settings

import android.content.res.Resources
import androidx.annotation.ArrayRes

internal fun settingsOptionValues(
    resources: Resources,
    @ArrayRes resourceId: Int,
): List<String> {
    val array = resources.obtainTypedArray(resourceId)
    return try {
        List(array.length()) { index ->
            checkNotNull(array.getString(index)) { "Missing settings value at index $index" }
        }
    } finally {
        array.recycle()
    }
}
