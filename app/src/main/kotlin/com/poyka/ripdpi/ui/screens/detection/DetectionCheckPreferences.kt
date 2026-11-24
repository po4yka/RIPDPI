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

        override fun wasPermissionDenied(permission: String): Boolean =
            prefs.getBoolean(deniedPermissionKey(permission), false)

        override fun recordPermissionResults(grants: Map<String, Boolean>) {
            if (grants.isEmpty()) return
            val editor = prefs.edit()
            for ((permission, granted) in grants) {
                if (granted) {
                    editor.remove(deniedPermissionKey(permission))
                } else {
                    editor.putBoolean(deniedPermissionKey(permission), true)
                }
            }
            editor.apply()
        }

        // Old request flags also recorded cancellations and permissions that Android did not ask for.
        private fun deniedPermissionKey(permission: String): String = "perm_denied_$permission"

        private companion object {
            const val PREFS_NAME = "detection_check_prefs"
            const val PREF_ONBOARDING_SHOWN = "detection_onboarding_shown"
        }
    }
