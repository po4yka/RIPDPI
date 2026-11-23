package com.poyka.ripdpi.platform

import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.proto.AppSettings

internal const val hasExtendedPacketControls = true

internal fun AppSettings.rawPacketOperationsEnabled(): Boolean = rootModeEnabled

internal fun StrategyChainSet.supportedInDistribution(): Boolean = true
