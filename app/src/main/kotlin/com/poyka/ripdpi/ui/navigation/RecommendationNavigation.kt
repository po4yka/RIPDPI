package com.poyka.ripdpi.ui.navigation

import com.poyka.ripdpi.core.detection.RecommendationDestination

internal fun RecommendationDestination.toRoute(): Route =
    when (this) {
        RecommendationDestination.MODE_SETTINGS -> Route.ModeEditor
        RecommendationDestination.PROXY_SETTINGS -> Route.LocalBypassConfig
        RecommendationDestination.DNS_SETTINGS -> Route.DnsSettings
        RecommendationDestination.GENERAL_SETTINGS -> Route.Settings
        RecommendationDestination.ADVANCED_SETTINGS -> Route.AdvancedSettings
    }
