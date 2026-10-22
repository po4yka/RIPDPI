package com.poyka.ripdpi.subscription

import android.content.Context
import com.poyka.ripdpi.data.ProxyGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Keeps the Android work scheduler outside the import ViewModel. */
class SubscriptionAutoUpdateScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun schedule(groups: List<ProxyGroup>) {
            SubscriptionAutoUpdateWorker.enqueuePeriodic(context, groups)
        }
    }
