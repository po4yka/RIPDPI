package com.poyka.ripdpi.ui.screens.detection

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DetectionCheckPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : DetectionCheckPreferenceStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun wasOnboardingShown(): Boolean = prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)

        override fun markOnboardingShown() {
            prefs.edit().putBoolean(PREF_ONBOARDING_SHOWN, true).apply()
        }

        override fun wasPermissionRequested(permission: String): Boolean =
            prefs.getBoolean(permissionRequestedKey(permission), false)

        override fun markPermissionsRequested(permissions: Array<String>) {
            val editor = prefs.edit()
            for (permission in permissions) {
                editor.putBoolean(permissionRequestedKey(permission), true)
            }
            editor.apply()
        }

        private fun permissionRequestedKey(permission: String): String = "perm_requested_$permission"

        private companion object {
            const val PREFS_NAME = "detection_check_prefs"
            const val PREF_ONBOARDING_SHOWN = "detection_onboarding_shown"
        }
    }
