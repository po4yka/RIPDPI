package com.poyka.ripdpi.data.persistence

import android.content.Context
import com.poyka.ripdpi.data.resolveAppSettingsStoreFile

/** Runs only after the database import/reset marker has committed. */
internal fun clearLegacyProfileSources(context: Context) {
    listOf(
        "relay_profile_cache",
        "relay_credentials_secure",
        "warp_profiles",
        "warp_credentials_secure",
        "warp_endpoint_cache",
        "awg_credentials_secure",
        "xray_profile_metadata",
        "xray_profile_secrets_secure",
        "xray_provider_selection",
        "proxy_group_cache",
        "selector_selection_store",
        "profile_mutation_journal",
    ).forEach { name ->
        check(
            context
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit(),
        ) {
            "Could not remove migrated profile source"
        }
    }
    val settings = resolveAppSettingsStoreFile(context)
    check(!settings.exists() || settings.delete()) { "Could not remove migrated settings source" }
    check(
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences("ripdpi_boot_session_state", Context.MODE_PRIVATE)
            .edit()
            .remove("boot-session-active-awg-profile-id")
            .commit(),
    ) {
        "Could not remove migrated provider selection"
    }
}
