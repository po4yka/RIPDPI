package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/** Display metadata only; credentials and transient network observations stay outside the catalog. */
data class ProfileUtilityCatalogEntry(
    val reference: ProfileUtilityReference,
    val label: String,
    val groupLabel: String?,
    val kind: String,
)

data class ProfileUtilityCatalog(
    val generation: Long,
    val entries: List<ProfileUtilityCatalogEntry>,
)

@Singleton
class ProfileUtilityCatalogReader
    @Inject
    constructor(
        private val recovery: ProfileMutationRecoveryAccess,
        private val stores: ProfileMutationStores,
        private val authority: PauseIntentAuthority,
    ) {
        /** Readiness comes from mandatory recovery, and every metadata read is serialized with catalog commits. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<ProfileUtilityCatalog> =
            flow {
                recovery.recover()
                emitAll(
                    authority.states
                        .filterNotNull()
                        .map { it.profileUtility.catalogGeneration }
                        .distinctUntilChanged()
                        .mapLatest {
                            recovery.readRecovered {
                                val utility = checkNotNull(authority.states.value).profileUtility
                                check(utility.catalogReady) { "Profile catalog is not committed" }
                                val native =
                                    stores.relayProfiles.list().map { profile ->
                                        ProfileUtilityCatalogEntry(
                                            ProfileUtilityReference.NativeRelay(profile.id),
                                            profile.operatorName.ifBlank { profile.id },
                                            null,
                                            profile.kind,
                                        )
                                    }
                                val xray =
                                    stores.xrayMetadata.list().map { profile ->
                                        ProfileUtilityCatalogEntry(
                                            ProfileUtilityReference.Xray(profile.profileId),
                                            profile.name.ifBlank { profile.profileId },
                                            null,
                                            "xray",
                                        )
                                    }
                                val groups =
                                    stores.groupBlob
                                        .read()
                                        ?.let { encoded ->
                                            RipDpiJson.decodeFromString(
                                                ListSerializer(ProxyGroup.serializer()),
                                                encoded,
                                            )
                                        }.orEmpty()
                                val members =
                                    groups.flatMap { group ->
                                        group.members.map { member ->
                                            ProfileUtilityCatalogEntry(
                                                ProfileUtilityReference.SelectorMember(group.id, member.id),
                                                member.displayName,
                                                group.name,
                                                "selector",
                                            )
                                        }
                                    }
                                ProfileUtilityCatalog(
                                    utility.catalogGeneration,
                                    (native + xray + members).filter { it.reference in utility.catalog },
                                )
                            }
                        },
                )
            }
    }
