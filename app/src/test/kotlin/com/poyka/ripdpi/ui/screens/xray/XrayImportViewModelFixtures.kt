package com.poyka.ripdpi.ui.screens.xray

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import kotlinx.coroutines.Dispatchers

internal object XrayImportViewModelFixtures {
    const val UUID = "550e8400-e29b-41d4-a716-446655440000"
    const val PUBLIC_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    class RecordingPersistence : XrayProfilePersistence {
        override val emptyInputMessage: String = "empty"
        override val noSupportedNodesMessage: String = "no-supported"
        override val unparseableMessage: String = "unparseable"
        override val inputTooLargeMessage: String = "too-large"
        override val persistFailedMessage: String = "activation-failed"
        override val restoreFailedMessage: String = "restore-failed"
        override val unsupportedTypedProfileMessage: String = "typed-unsupported"
        override val missingTypedProfileFieldMessage: String = "typed-missing"
        override val unsafeTypedProfileMessage: String = "typed-unsafe"
        var failNextRestore = false
        var restored: XrayProviderSelection = XrayProviderSelection()
        var persisted: Triple<XrayServiceModeOption, List<ProxyProfile>, XrayProfile?>? = null

        override suspend fun restoreSelection(): XrayProviderSelection {
            if (failNextRestore) {
                failNextRestore = false
                error("durable read failed")
            }
            return restored
        }

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
            acceptedProfile: XrayProfile?,
        ) {
            persisted = Triple(option, profiles, acceptedProfile)
        }
    }

    /** Persistence that throws on the first call, then succeeds — exercises the retry path. */
    class FlakyPersistence : XrayProfilePersistence {
        override val emptyInputMessage: String = "empty"
        override val noSupportedNodesMessage: String = "no-supported"
        override val unparseableMessage: String = "unparseable"
        override val inputTooLargeMessage: String = "too-large"
        override val persistFailedMessage: String = "activation-failed"
        override val restoreFailedMessage: String = "restore-failed"
        override val unsupportedTypedProfileMessage: String = "typed-unsupported"
        override val missingTypedProfileFieldMessage: String = "typed-missing"
        override val unsafeTypedProfileMessage: String = "typed-unsafe"
        var attempts: Int = 0

        override suspend fun restoreSelection(): XrayProviderSelection = XrayProviderSelection()

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
            acceptedProfile: XrayProfile?,
        ) {
            attempts += 1
            if (attempts == 1) error("store write failed")
        }
    }

    fun testDispatchers() = AppCoroutineDispatchers(Dispatchers.Main, Dispatchers.Main, Dispatchers.Main)

    fun viewModel(persistence: XrayProfilePersistence = RecordingPersistence()) =
        XrayProfileImportViewModel(persistence, testDispatchers())
}
