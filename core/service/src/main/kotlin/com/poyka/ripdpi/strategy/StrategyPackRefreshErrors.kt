package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.StrategyPackRefreshFailureCode

internal fun Throwable.toFailureCode(): StrategyPackRefreshFailureCode? =
    (this as? StrategyPackRefreshException)?.failureCode

internal fun Throwable.toRejectedSequence(): Long? = (this as? StrategyPackRefreshException)?.rejectedSequence
