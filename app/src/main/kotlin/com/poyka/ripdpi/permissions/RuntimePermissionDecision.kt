package com.poyka.ripdpi.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal object RuntimePermissionDecision {
    private const val PreferencesName = "runtime_permission_denials"

    fun recordRequest(
        activity: Activity,
        permission: String,
    ) {
        preferences(activity)
            .edit()
            .putBoolean(
                rationaleKey(permission),
                activity.shouldShowRequestPermissionRationale(permission),
            ).apply()
    }

    fun fromActivityResult(
        activity: Activity,
        permission: String,
        grants: Map<String, Boolean>,
    ): PermissionResult {
        val history = preferences(activity)
        val result =
            classify(
                granted = grants[permission],
                showedRationaleBefore = history.getBoolean(rationaleKey(permission), false),
                shouldShowRationale = activity.shouldShowRequestPermissionRationale(permission),
            )
        history.edit().remove(rationaleKey(permission)).apply()
        when (result) {
            PermissionResult.Granted, PermissionResult.Denied -> {
                clearSettingsRequirement(activity, permission)
            }

            PermissionResult.DeniedPermanently -> {
                history
                    .edit()
                    .putBoolean(permission, true)
                    .remove(unansweredKey(permission))
                    .apply()
            }

            PermissionResult.Cancelled -> {
                if (grants[permission] == false) {
                    history.edit().putBoolean(unansweredKey(permission), true).apply()
                }
            }

            PermissionResult.ReturnedFromSettings -> {}
        }
        return result
    }

    fun status(
        context: Context,
        permission: String,
    ): PermissionStatus =
        when {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED -> {
                clearSettingsRequirement(context, permission)
                PermissionStatus.Granted
            }

            preferences(context).getBoolean(permission, false) -> {
                PermissionStatus.RequiresSettings
            }

            preferences(context).getBoolean(unansweredKey(permission), false) -> {
                PermissionStatus.Unknown
            }

            else -> {
                PermissionStatus.RequiresSystemPrompt
            }
        }

    internal fun classify(
        granted: Boolean?,
        showedRationaleBefore: Boolean,
        shouldShowRationale: Boolean,
    ): PermissionResult =
        when {
            granted == null -> PermissionResult.Cancelled

            granted -> PermissionResult.Granted

            shouldShowRationale -> PermissionResult.Denied

            // Android may return an unchanged grant map when the user closes the dialog.
            showedRationaleBefore -> PermissionResult.DeniedPermanently

            else -> PermissionResult.Cancelled
        }

    private fun unansweredKey(permission: String) = "$permission.unanswered"

    private fun rationaleKey(permission: String) = "$permission.rationale"

    private fun clearSettingsRequirement(
        context: Context,
        permission: String,
    ) {
        val preferences = preferences(context)
        if (preferences.contains(permission) || preferences.contains(unansweredKey(permission))) {
            preferences
                .edit()
                .remove(permission)
                .remove(unansweredKey(permission))
                .apply()
        }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
}
