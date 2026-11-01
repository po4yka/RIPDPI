package com.poyka.ripdpi.activities

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest notice a screen has yet to show. A notice raised while its screen is away (for example the result of
 * a network refresh) waits here until that screen shows and acknowledges it, and no other screen sees it.
 */
internal class SettingsNoticeState {
    private val mutableLatest = MutableStateFlow<SettingsEffect.Notice?>(null)
    val latest: StateFlow<SettingsEffect.Notice?> = mutableLatest.asStateFlow()

    fun post(notice: SettingsEffect.Notice) {
        mutableLatest.value = notice
    }

    fun clear() {
        mutableLatest.value = null
    }

    /** Clears [notice] only while it is still the latest, so a newer notice posted meanwhile survives. */
    fun acknowledge(notice: SettingsEffect.Notice) {
        mutableLatest.compareAndSet(notice, null)
    }
}
