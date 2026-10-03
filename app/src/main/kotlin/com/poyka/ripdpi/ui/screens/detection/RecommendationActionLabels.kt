package com.poyka.ripdpi.ui.screens.detection

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.RecommendationDestination

@StringRes
internal fun RecommendationDestination.actionLabelRes(): Int =
    when (this) {
        RecommendationDestination.MODE_SETTINGS -> R.string.detection_recommendation_open_mode
        RecommendationDestination.PROXY_SETTINGS -> R.string.detection_recommendation_open_proxy
        RecommendationDestination.DNS_SETTINGS -> R.string.detection_recommendation_open_dns
        RecommendationDestination.GENERAL_SETTINGS -> R.string.detection_recommendation_open_settings
        RecommendationDestination.ADVANCED_SETTINGS -> R.string.detection_recommendation_open_advanced
    }
