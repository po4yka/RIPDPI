package com.poyka.ripdpi.activities

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class DiagnosticsUiFactorySupport
    @Inject
    constructor(
        @param:ApplicationContext
        val context: Context,
        val core: DiagnosticsUiCoreSupport,
    ) {
        constructor(
            @ApplicationContext context: Context,
        ) : this(
            context = context,
            core = DiagnosticsUiCoreSupport(),
        )
    }
