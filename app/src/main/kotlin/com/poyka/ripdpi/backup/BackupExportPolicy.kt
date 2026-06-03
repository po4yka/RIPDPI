package com.poyka.ripdpi.backup

import android.content.Context
import android.content.RestrictionsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Best-effort MDM suppression knob for backup export, modelled on
 * amneziawg-android's `AdminKnobs.disable_config_export`.
 *
 * An enterprise device admin (EMM/MDM) can set the boolean app-restriction
 * [DisableExportKey] to `true` to hide / disable the backup-export action. When no
 * managed configuration is present (the common consumer case) the restriction is
 * absent and export is permitted.
 *
 * This is intentionally lightweight: it reads the platform [RestrictionsManager]
 * application restrictions bundle. Declaring the restriction in
 * `res/xml/app_restrictions.xml` is deferred until an MDM integration is actually
 * shipped — an undeclared key is still honoured if an EMM pushes it.
 */
class BackupExportPolicy
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Returns `true` when device policy forbids exporting a backup. */
        fun isExportDisabledByPolicy(): Boolean =
            (context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager)
                ?.applicationRestrictions
                ?.getBoolean(DisableExportKey, false)
                ?: false

        private companion object {
            const val DisableExportKey = "com.poyka.ripdpi.backup.disable_export"
        }
    }
