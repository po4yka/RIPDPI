package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.platform.StringResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class DiagnosticsUiFactorySupport
    @Inject
    constructor(
        val context: StringResolver,
        val core: DiagnosticsUiCoreSupport,
    ) {
        constructor(
            @ApplicationContext context: Context,
        ) : this(
            context = AndroidStringResolver(context),
            core = DiagnosticsUiCoreSupport(),
        )
    }
