package com.poyka.ripdpi.data

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

const val NetworkAutoProfileBindingRetentionLimit = 32

@Serializable
data class NetworkAutoProfileBinding(
    val id: String,
    val fingerprintHash: String,
    val summary: NetworkFingerprintSummary,
    val profileName: String,
    val settingsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

interface NetworkAutoProfileBindingStore {
    fun bindings(): Flow<List<NetworkAutoProfileBinding>>

    suspend fun list(): List<NetworkAutoProfileBinding>

    suspend fun find(fingerprintHash: String): NetworkAutoProfileBinding?

    suspend fun upsert(binding: NetworkAutoProfileBinding)

    suspend fun delete(id: String)

    suspend fun clear()
}

@Singleton
class SharedPreferencesNetworkAutoProfileBindingStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : NetworkAutoProfileBindingStore {
        private val preferences = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        private val json = RipDpiJson
        private val listSerializer = ListSerializer(NetworkAutoProfileBinding.serializer())
        private val state = MutableStateFlow(readBindings())

        override fun bindings(): Flow<List<NetworkAutoProfileBinding>> = state.asStateFlow()

        override suspend fun list(): List<NetworkAutoProfileBinding> = readBindings()

        override suspend fun find(fingerprintHash: String): NetworkAutoProfileBinding? =
            readBindings().firstOrNull { it.fingerprintHash == fingerprintHash }

        override suspend fun upsert(binding: NetworkAutoProfileBinding) {
            val next =
                (
                    readBindings().filterNot { it.id == binding.id || it.fingerprintHash == binding.fingerprintHash } +
                        binding
                ).sortedByDescending(NetworkAutoProfileBinding::updatedAt)
                    .take(NetworkAutoProfileBindingRetentionLimit)
            writeBindings(next)
        }

        override suspend fun delete(id: String) {
            writeBindings(readBindings().filterNot { it.id == id })
        }

        override suspend fun clear() {
            writeBindings(emptyList())
        }

        private fun readBindings(): List<NetworkAutoProfileBinding> {
            val raw = preferences.getString(BindingsKey, null) ?: return emptyList()
            return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
        }

        private fun writeBindings(bindings: List<NetworkAutoProfileBinding>) {
            preferences
                .edit()
                .putString(BindingsKey, json.encodeToString(listSerializer, bindings))
                .apply()
            state.value = bindings
        }

        private companion object {
            const val PrefsName = "network_auto_profile_bindings"
            const val BindingsKey = "bindings"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkAutoProfileBindingStoreModule {
    @Binds
    @Singleton
    abstract fun bindNetworkAutoProfileBindingStore(
        store: SharedPreferencesNetworkAutoProfileBindingStore,
    ): NetworkAutoProfileBindingStore
}
