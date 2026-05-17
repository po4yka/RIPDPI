package com.poyka.ripdpi.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

const val EXTRA_SELECT_GROUP_ID = "com.poyka.ripdpi.extra.SELECT_GROUP_ID"
const val EXTRA_SELECT_PROFILE_ID = "com.poyka.ripdpi.extra.SELECT_PROFILE_ID"

private const val STOP_VPN_SHORTCUT_ID = "stop_vpn"
private const val MAX_DYNAMIC_SHORTCUTS = 4

private data class DynamicProfileEntry(
    val groupId: String,
    val groupName: String,
    val profileId: String,
)

@Singleton
class AppShortcutsPublisher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val serviceStateStore: ServiceStateStore,
        private val proxyGroupRepository: ProxyGroupRepository,
        private val selectorSelectionStore: SelectorSelectionStore,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        private val started = AtomicBoolean(false)

        @OptIn(ExperimentalCoroutinesApi::class)
        fun start() {
            if (!started.compareAndSet(false, true)) return

            applicationScope.launch {
                serviceStateStore.status
                    .map { it.first }
                    .distinctUntilChanged()
                    .collect { status ->
                        runCatching {
                            when (status) {
                                AppStatus.Running -> {
                                    val manifestShortcut =
                                        ShortcutManagerCompat
                                            .getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST)
                                            .firstOrNull { it.id == STOP_VPN_SHORTCUT_ID }
                                    if (manifestShortcut != null) {
                                        ShortcutManagerCompat.enableShortcuts(context, listOf(manifestShortcut))
                                    }
                                }

                                AppStatus.Halted -> {
                                    ShortcutManagerCompat.disableShortcuts(
                                        context,
                                        listOf(STOP_VPN_SHORTCUT_ID),
                                        context.getString(R.string.shortcut_stop_vpn_disabled),
                                    )
                                }
                            }
                        }.onFailure { err ->
                            Logger.w(err) { "AppShortcutsPublisher: failed to toggle stop_vpn shortcut" }
                        }
                    }
            }

            applicationScope.launch {
                proxyGroupRepository
                    .groups()
                    .flatMapLatest { groups ->
                        val selectorGroups = groups.filter { it.isSelector }
                        if (selectorGroups.isEmpty()) {
                            flowOf(emptyList<DynamicProfileEntry>())
                        } else {
                            val perGroupFlows: List<kotlinx.coroutines.flow.Flow<DynamicProfileEntry?>> =
                                selectorGroups.map { group ->
                                    selectorSelectionStore.selectedProfileId(group.id).map { profileId ->
                                        profileId?.let {
                                            DynamicProfileEntry(
                                                groupId = group.id,
                                                groupName = group.name,
                                                profileId = it,
                                            )
                                        }
                                    }
                                }
                            combine(perGroupFlows) { entries -> entries.filterNotNull().toList() }
                        }
                    }.distinctUntilChanged()
                    .collect { entries ->
                        publishDynamicShortcuts(entries)
                    }
            }
        }

        private fun publishDynamicShortcuts(entries: List<DynamicProfileEntry>) {
            runCatching {
                val platformLimit =
                    ShortcutManagerCompat
                        .getMaxShortcutCountPerActivity(context)
                        .takeIf { it > 0 }
                        ?: MAX_DYNAMIC_SHORTCUTS
                val limit = min(MAX_DYNAMIC_SHORTCUTS, platformLimit)
                val infos = entries.take(limit).map { buildShortcut(it) }
                ShortcutManagerCompat.setDynamicShortcuts(context, infos)
            }.onFailure { err ->
                Logger.w(err) { "AppShortcutsPublisher: failed to publish dynamic shortcuts" }
            }
        }

        private fun buildShortcut(entry: DynamicProfileEntry): ShortcutInfoCompat =
            ShortcutInfoCompat
                .Builder(context, "profile_${entry.groupId}")
                .setShortLabel(entry.groupName.take(10))
                .setLongLabel(entry.groupName)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_vpn_key_outline))
                .setIntent(buildSelectGroupIntent(entry))
                .build()

        private fun buildSelectGroupIntent(entry: DynamicProfileEntry): Intent =
            Intent(Intent.ACTION_VIEW, "ripdpi://connect".toUri()).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_SELECT_GROUP_ID, entry.groupId)
                putExtra(EXTRA_SELECT_PROFILE_ID, entry.profileId)
            }
    }
