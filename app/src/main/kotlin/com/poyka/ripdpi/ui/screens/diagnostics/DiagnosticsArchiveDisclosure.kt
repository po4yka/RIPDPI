package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R

@Composable
internal fun diagnosticsArchiveBody(
    @StringRes bodyResource: Int,
    vararg formatArgs: Any,
): String {
    val body = stringResource(bodyResource, *formatArgs)
    val disclosure = stringResource(R.string.diagnostics_archive_artifact_disclosure)
    return "$body\n\n$disclosure"
}
