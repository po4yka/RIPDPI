package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.platform.StringResolver

internal fun RuntimeConfigurationStrategy.confirmedStrategyLabel(strings: StringResolver): String? =
    if (this.custom) {
        strings.getString(R.string.adaptive_split_badge_custom)
    } else {
        this.family?.let { family ->
            strings.getString(
                when (family) {
                    "split" -> R.string.config_chain_step_split_label
                    "disorder" -> R.string.config_chain_step_disorder_label
                    "multidisorder" -> R.string.config_chain_step_multidisorder_label
                    "fake" -> R.string.config_chain_step_fake_label
                    "hostfake" -> R.string.config_chain_step_hostfake_label
                    "seqovl" -> R.string.config_chain_step_seqovl_label
                    "tlsrec" -> R.string.config_chain_step_tlsrec_label
                    "tlsrandrec" -> R.string.config_chain_step_tlsrandrec_label
                    "ipfrag2" -> R.string.config_chain_step_ipfrag2_label
                    else -> R.string.diagnostics_field_unknown
                },
            )
        }
    }
