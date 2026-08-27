package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.subscription.AmneziaWgSubscriptionProfile
import com.poyka.ripdpi.data.subscription.BootstrapConsumeResult
import com.poyka.ripdpi.data.subscription.BootstrapConsumer
import com.poyka.ripdpi.data.subscription.toActivationRequest
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** UI state for the subscription-add import-confirmation screen. */
data class SubscriptionImportConfirmUiState(
    val url: String = "",
    val name: String = "",
    val bootstrap: Boolean = false,
    val importing: Boolean = false,
    val importFailed: Boolean = false,
)

/**
 * Backing [ViewModel] for the subscription-add import-confirmation destination.
 *
 * This is the import-confirmation surface, not a full subscription editor: it shows the
 * pre-filled URL + name (and a bootstrap badge when the deep link's URL path was
 * `/bootstrap/...`) and an "Add" action that persists a [ProxyGroupType.SUBSCRIPTION]
 * group via [ProxyGroupRepository]. The user confirms here before any network request is
 * made. The handler activity navigates here after parsing a
 * `singbox://import-remote-profile?url=…` deep link.
 */
@HiltViewModel
class SubscriptionImportConfirmViewModel
    private constructor(
        private val repository: ProxyGroupRepository,
        private val bootstrapConsumer: BootstrapConsumer,
        private val groupIdFactory: () -> String,
        private val saveAwgProfiles: suspend (List<AmneziaWgSubscriptionProfile>) -> Unit,
        @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit,
    ) : ViewModel() {
        @Inject
        constructor(
            repository: ProxyGroupRepository,
            awgProfileRepository: AwgProfileRepository,
        ) : this(
            repository = repository,
            bootstrapConsumer = BootstrapConsumer(),
            groupIdFactory = { UUID.randomUUID().toString() },
            saveAwgProfiles = { profiles ->
                val existingByName =
                    awgProfileRepository
                        .observeProfiles()
                        .first()
                        .associateBy { it.name }
                profiles.forEach { profile ->
                    awgProfileRepository.save(
                        name = profile.displayName,
                        request = profile.toActivationRequest(),
                        existingId = existingByName[profile.displayName]?.id,
                    )
                }
            },
            constructorMarker = Unit,
        )

        internal constructor(repository: ProxyGroupRepository) : this(
            repository = repository,
            bootstrapConsumer = BootstrapConsumer(),
            groupIdFactory = { UUID.randomUUID().toString() },
            saveAwgProfiles = {},
            constructorMarker = Unit,
        )

        internal constructor(
            repository: ProxyGroupRepository,
            bootstrapConsumer: BootstrapConsumer,
            groupIdFactory: () -> String = { UUID.randomUUID().toString() },
            saveAwgProfiles: suspend (List<AmneziaWgSubscriptionProfile>) -> Unit = {},
        ) : this(repository, bootstrapConsumer, groupIdFactory, saveAwgProfiles, Unit)

        private val _uiState = MutableStateFlow(SubscriptionImportConfirmUiState())
        val uiState: StateFlow<SubscriptionImportConfirmUiState> = _uiState.asStateFlow()
        private val importedEventChannel = Channel<Unit>(capacity = Channel.BUFFERED)
        val importedEvents: Flow<Unit> = importedEventChannel.receiveAsFlow()
        private var completed = false
        private var claimedImportToken: String? = null

        internal fun claimRequest(
            importToken: String,
            pendingImports: PendingProxyImportStore = PendingProxyImportStore.process,
        ): Boolean {
            val alreadyClaimed = claimedImportToken == importToken && _uiState.value.url.isNotBlank()
            val request = if (alreadyClaimed) null else pendingImports.claimSubscription(importToken)
            return when {
                alreadyClaimed -> {
                    true
                }

                request == null -> {
                    false
                }

                else -> {
                    claimedImportToken = importToken
                    setRequest(request.url, request.name, request.bootstrap)
                    true
                }
            }
        }

        /** Seeds the screen with the deep link's pre-filled fields. */
        fun setRequest(
            url: String,
            name: String,
            bootstrap: Boolean,
        ) {
            completed = false
            _uiState.update {
                it.copy(url = url, name = name, bootstrap = bootstrap, importFailed = false)
            }
        }

        /**
         * Persists the pending subscription into a new [ProxyGroupType.SUBSCRIPTION]
         * group. Bootstrap links are consumed before the success event is emitted, so
         * their single-use payload and consumed timestamp reach durable storage together.
         *
         * The persisted [Subscription.kind] reflects the bootstrap flag: a bootstrap
         * import is stored as [SubscriptionKind.BOOTSTRAP] so the auto-update worker
         * skips it and the UI can mark it as a one-time token; everything else is a
         * refetchable [SubscriptionKind.LONG_LIVED] subscription.
         */
        fun confirm() {
            val state = _uiState.value
            if (state.url.isBlank()) return
            if (state.importing || completed) return
            _uiState.update { it.copy(importing = true, importFailed = false) }
            viewModelScope.launch {
                try {
                    val groups = repository.list()
                    val existing = groups.firstOrNull { group -> group.subscription?.link == state.url }
                    val groupId = existing?.id ?: groupIdFactory()
                    val groupName = state.name.takeIf { it.isNotBlank() } ?: hostOf(state.url)
                    val order = existing?.order ?: groups.size
                    if (state.bootstrap && existing?.subscription?.consumedAt == null) {
                        consumeBootstrap(state, existing, groupId, groupName, order)
                    } else {
                        repository.add(
                            subscriptionGroup(
                                existing = existing,
                                groupId = groupId,
                                groupName = groupName,
                                order = order,
                                subscription =
                                    existing?.subscription
                                        ?: Subscription(link = state.url, kind = SubscriptionKind.LONG_LIVED),
                            ),
                        )
                        completeImport()
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    _uiState.update { it.copy(importFailed = true) }
                } finally {
                    _uiState.update { it.copy(importing = false) }
                }
            }
        }

        private suspend fun consumeBootstrap(
            state: SubscriptionImportConfirmUiState,
            existing: ProxyGroup?,
            groupId: String,
            groupName: String,
            order: Int,
        ) {
            when (val result = bootstrapConsumer.consume(state.url, groupId)) {
                is BootstrapConsumeResult.Consumed -> {
                    saveAwgProfiles(result.amneziaWgProfiles)
                    repository.add(
                        subscriptionGroup(
                            existing = existing,
                            groupId = groupId,
                            groupName = groupName,
                            order = order,
                            subscription =
                                Subscription(
                                    link = state.url,
                                    kind = SubscriptionKind.BOOTSTRAP,
                                    consumedAt = result.consumedAtMillis,
                                    mirrors = result.subscriptionMirrors,
                                ),
                            members = result.profiles,
                            packageRoutingRules = result.packageRoutingRules,
                            cloudflareMemberIds = result.cloudflareMemberIds,
                            isSelector = result.isSelector,
                            failover = result.failover,
                        ),
                    )
                    completeImport()
                }

                is BootstrapConsumeResult.AlreadyConsumed,
                is BootstrapConsumeResult.NetworkError,
                is BootstrapConsumeResult.ParseError,
                -> {
                    _uiState.update { it.copy(importFailed = true) }
                }
            }
        }

        private fun subscriptionGroup(
            existing: ProxyGroup?,
            groupId: String,
            groupName: String,
            order: Int,
            subscription: Subscription,
            members: List<ProxyProfile> = existing?.members.orEmpty(),
            packageRoutingRules: List<PackageRoutingRule> = existing?.packageRoutingRules.orEmpty(),
            cloudflareMemberIds: Set<String> = existing?.cloudflareMemberIds.orEmpty(),
            isSelector: Boolean = existing?.isSelector ?: false,
            failover: SelectorFailover? = existing?.failover,
        ): ProxyGroup =
            ProxyGroup(
                id = groupId,
                name = groupName,
                type = ProxyGroupType.SUBSCRIPTION,
                order = order,
                isSelector = isSelector,
                subscription = subscription,
                members = members,
                failover = failover,
                cloudflareMemberIds = cloudflareMemberIds,
                packageRoutingRules = packageRoutingRules,
            )

        private suspend fun completeImport() {
            completed = true
            importedEventChannel.send(Unit)
        }

        /** Extracts the host from [url] for use as a fallback group name. */
        private fun hostOf(url: String): String {
            val schemeEnd = url.indexOf("://")
            val afterScheme = if (schemeEnd >= 0) url.substring(schemeEnd + "://".length) else url
            return afterScheme
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .takeIf { it.isNotBlank() } ?: url
        }
    }
