package com.poyka.ripdpi.e2e

internal data class VpnConsentUiCandidate(
    val packageName: String?,
    val resourceName: String? = null,
    val clickable: Boolean = true,
    val enabled: Boolean = true,
    val bottom: Int = 0,
    val right: Int = 0,
)

internal object VpnConsentUiSelector {
    val defaultDialogPackages: List<String> =
        listOf(
            "com.android.vpndialogs",
            "com.android.permissioncontroller",
        )

    val defaultPositiveButtonResources: List<String> =
        listOf(
            "android:id/button1",
            "com.android.vpndialogs:id/button1",
            "com.android.permissioncontroller:id/permission_allow_button",
        )

    fun orderedDialogPackages(packageHints: List<String>): List<String> =
        buildList {
            addAll(defaultDialogPackages)
            packageHints
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach(::add)
        }.distinct()

    fun selectKnownPositiveButton(
        activeDialogPackage: String?,
        candidates: List<VpnConsentUiCandidate>,
    ): VpnConsentUiCandidate? {
        val enabledCandidates = candidates.filter(VpnConsentUiCandidate::enabled)
        val packageScopedCandidates =
            activeDialogPackage
                ?.let { pkg -> enabledCandidates.filter { it.packageName == pkg } }
                .orEmpty()
        val selectionPool = packageScopedCandidates.ifEmpty { enabledCandidates }

        return defaultPositiveButtonResources
            .asSequence()
            .mapNotNull { resourceName ->
                selectionPool.firstOrNull { it.resourceName == resourceName }
            }.firstOrNull()
    }

    fun selectFallbackPositiveButton(
        activeDialogPackage: String?,
        candidates: List<VpnConsentUiCandidate>,
    ): VpnConsentUiCandidate? {
        if (activeDialogPackage == null) {
            return null
        }

        return candidates
            .asSequence()
            .filter { it.packageName == activeDialogPackage }
            .filter(VpnConsentUiCandidate::enabled)
            .filter(VpnConsentUiCandidate::clickable)
            .maxWithOrNull(compareBy<VpnConsentUiCandidate>({ it.bottom }, { it.right }))
    }
}
