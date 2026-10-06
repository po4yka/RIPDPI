package com.poyka.ripdpi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Profile namespaces remain distinct even when their opaque IDs happen to match. */
@Serializable
sealed interface ProfileUtilityReference {
    @Serializable
    @SerialName("native_relay")
    data class NativeRelay(
        val profileId: String,
    ) : ProfileUtilityReference {
        init {
            require(profileId.isNotBlank())
        }
    }

    @Serializable
    @SerialName("xray")
    data class Xray(
        val profileId: String,
    ) : ProfileUtilityReference {
        init {
            require(profileId.isNotBlank())
        }
    }

    @Serializable
    @SerialName("selector_member")
    data class SelectorMember(
        val groupId: String,
        val memberId: String,
    ) : ProfileUtilityReference {
        init {
            require(groupId.isNotBlank() && memberId.isNotBlank())
        }
    }
}

@Serializable
data class RuntimeAppliedUseIdentity(
    val runtimeId: String,
    val revision: Long,
    val mode: String,
) {
    init {
        require(runtimeId.isNotBlank() && revision > 0 && Mode.fromString(mode).preferenceValue == mode)
    }
}

/** Only profile references and accepted-runtime metadata enter this record; network scope stays transient. */
@Serializable
data class RuntimeAppliedUseReceipt(
    val identity: RuntimeAppliedUseIdentity,
    val references: List<ProfileUtilityReference>,
    val appliedAtMillis: Long,
    val catalogGeneration: Long,
    val recordsUse: Boolean,
    val payloadFingerprint: String,
) {
    init {
        require(appliedAtMillis > 0 && catalogGeneration >= 0)
        require(
            payloadFingerprint.length == AppliedPayloadFingerprintHexLength &&
                payloadFingerprint.all { it in '0'..'9' || it in 'a'..'f' },
        )
        require(references.distinct().size == references.size)
    }
}

@Serializable
data class RecentProfileUse(
    val reference: ProfileUtilityReference,
    val sequence: Long,
    val appliedAtMillis: Long,
    val identity: RuntimeAppliedUseIdentity,
)

@Serializable
data class ProfileUtilityState(
    val catalogGeneration: Long,
    val catalogReady: Boolean,
    val catalog: Set<ProfileUtilityReference>,
    val favorites: Set<ProfileUtilityReference>,
    val recents: List<RecentProfileUse>,
    val lastSequence: Long,
    val acknowledged: List<RuntimeAppliedUseReceipt>,
) {
    init {
        require(catalogGeneration >= 0 && lastSequence >= 0)
        require(favorites.all(catalog::contains) && favorites.size <= MaxFavoriteProfiles)
        require(recents.size <= MaxRecentProfiles && recents.map { it.reference }.distinct().size == recents.size)
        require(recents.all { it.reference in catalog && it.sequence > 0 && it.sequence <= lastSequence })
        require(acknowledged.size <= MaxAppliedAcknowledgments)
        require(acknowledged.map { it.identity }.distinct().size == acknowledged.size)
    }

    companion object {
        fun empty() = ProfileUtilityState(0, true, emptySet(), emptySet(), emptyList(), 0, emptyList())
    }
}

@Serializable
data class RuntimeAppliedPayload(
    val requested: RuntimeConfigurationSelection,
    val effective: RuntimeConfigurationSelection,
    val dns: RuntimeConfigurationDns,
    val strategy: RuntimeConfigurationStrategy,
    val reason: String,
)

private const val AppliedPayloadFingerprintHexLength = 64
private const val MaxFavoriteProfiles = 64
