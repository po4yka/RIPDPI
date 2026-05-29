package com.poyka.ripdpi.ui.screens.routes

import android.content.Context
import android.content.pm.PackageManager
import com.poyka.ripdpi.services.InstalledPackagesProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the set of installed packages (from [InstalledPackagesProvider]) into UI-ready
 * [InstalledAppItem]s with human-readable labels. Owns the [Context]/[PackageManager] usage so the
 * rule-editor ViewModel can stay free of an `@ApplicationContext` injection (which the
 * `HiltViewModelApplicationContext` detekt rule forbids in a `@HiltViewModel`).
 *
 * Reusable by the planned split-tunnel surface as well as the rule editor.
 */
@Singleton
class InstalledAppCatalog
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val installedPackagesProvider: InstalledPackagesProvider,
    ) {
        /** Loads installed apps with labels off the main thread, sorted case-insensitively by label. */
        suspend fun installedApps(): List<InstalledAppItem> =
            withContext(Dispatchers.IO) {
                val packageManager = context.packageManager
                installedPackagesProvider
                    .installedPackages()
                    .mapNotNull { packageName -> resolveApp(packageManager, packageName) }
                    .sortedBy { it.label.lowercase() }
            }

        private fun resolveApp(
            packageManager: PackageManager,
            packageName: String,
        ): InstalledAppItem? =
            runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                InstalledAppItem(
                    packageName = packageName,
                    label = packageManager.getApplicationLabel(info).toString(),
                )
            }.getOrNull()
    }
