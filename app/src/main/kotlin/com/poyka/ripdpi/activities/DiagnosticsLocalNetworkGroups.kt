package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.LocalNetworkContextModel
import com.poyka.ripdpi.diagnostics.localConstraintCodes
import com.poyka.ripdpi.diagnostics.toSafeLocalNetworkContext
import kotlinx.collections.immutable.toImmutableList

internal fun DiagnosticsUiFactorySupport.localNetworkOverview(local: LocalNetworkContextModel?): String =
    if (local == null) {
        context.getString(R.string.diagnostics_local_not_collected)
    } else {
        context.getString(
            R.string.diagnostics_local_overview,
            localValue(local.transport),
            local.localConstraintCodes().size,
        )
    }

internal fun DiagnosticsUiFactorySupport.toLocalNetworkGroups(
    snapshot: LocalNetworkContextModel?,
): List<DiagnosticsContextGroupUiModel> {
    val local = snapshot?.toSafeLocalNetworkContext()
    if (local == null) {
        return listOf(
            localGroup(
                R.string.diagnostics_local_title,
                listOf(
                    localField(
                        R.string.diagnostics_local_evidence,
                        context.getString(R.string.diagnostics_local_not_collected),
                    ),
                ),
            ),
        )
    }
    return listOf(localDeviceGroup(local), localSimGroup(local), localReviewGroup(local))
}

private fun DiagnosticsUiFactorySupport.localDeviceGroup(
    local: LocalNetworkContextModel,
): DiagnosticsContextGroupUiModel =
    localGroup(
        R.string.diagnostics_local_title,
        buildList {
            add(
                localField(
                    R.string.diagnostics_local_captured,
                    if (local.capturedAt > 0) {
                        formatTimestamp(local.capturedAt)
                    } else {
                        context.getString(R.string.diagnostics_local_unknown)
                    },
                ),
            )
            add(localField(R.string.diagnostics_local_transport, localValue(local.transport)))
            add(localField(R.string.diagnostics_local_airplane, localValue(local.device.airplaneMode)))
            add(localField(R.string.diagnostics_local_data_saver, localValue(local.device.dataSaver)))
            add(localField(R.string.diagnostics_local_background, localValue(local.device.backgroundRestricted)))
            add(localField(R.string.diagnostics_local_power_save, localValue(local.device.powerSaveMode)))
            add(
                localField(
                    R.string.diagnostics_local_battery_exempt,
                    localValue(local.device.batteryOptimizationExempt),
                ),
            )
            add(localField(R.string.diagnostics_local_idle, localValue(local.device.deviceIdle)))
            add(localField(R.string.diagnostics_local_metered, localValue(local.device.metered)))
            add(
                localField(
                    R.string.diagnostics_local_interpretation,
                    context.getString(R.string.diagnostics_local_caution),
                ),
            )
        },
    )

private fun DiagnosticsUiFactorySupport.localSimGroup(local: LocalNetworkContextModel): DiagnosticsContextGroupUiModel =
    localGroup(
        R.string.diagnostics_local_sim_title,
        buildList {
            add(localField(R.string.diagnostics_local_scope, localValue(local.sim.scope)))
            add(
                localField(
                    R.string.diagnostics_local_active_matches,
                    localValue(local.sim.activeDataMatchesDefault),
                ),
            )
            add(localField(R.string.diagnostics_local_sim_state, localValue(local.sim.simState)))
            add(localField(R.string.diagnostics_local_mobile_data, localValue(local.sim.mobileDataEnabled)))
            add(localField(R.string.diagnostics_local_data_allowed, localValue(local.sim.dataConnectionAllowed)))
            add(localField(R.string.diagnostics_local_roaming, localValue(local.sim.roaming)))
            add(localField(R.string.diagnostics_local_roaming_enabled, localValue(local.sim.roamingEnabled)))
            add(localField(R.string.diagnostics_local_service_state, localValue(local.sim.voiceServiceState)))
            add(localField(R.string.diagnostics_local_data_state, localValue(local.sim.dataState)))
            add(
                localField(
                    R.string.diagnostics_local_interpretation,
                    context.getString(R.string.diagnostics_local_sim_caution),
                ),
            )
        },
    )

private fun DiagnosticsUiFactorySupport.localReviewGroup(
    local: LocalNetworkContextModel,
): DiagnosticsContextGroupUiModel =
    localGroup(
        R.string.diagnostics_local_review,
        local
            .localConstraintCodes()
            .map { code ->
                localField(R.string.diagnostics_local_review_item, context.getString(localConstraintText(code)))
            }.ifEmpty {
                listOf(
                    localField(
                        R.string.diagnostics_local_evidence,
                        context.getString(R.string.diagnostics_local_no_conditions),
                    ),
                )
            },
    )

private fun DiagnosticsUiFactorySupport.localField(
    label: Int,
    value: String,
) = DiagnosticsFieldUiModel(context.getString(label), value)

private fun DiagnosticsUiFactorySupport.localGroup(
    title: Int,
    fields: List<DiagnosticsFieldUiModel>,
) = DiagnosticsContextGroupUiModel(context.getString(title), fields.toImmutableList(), stackedFields = true)
