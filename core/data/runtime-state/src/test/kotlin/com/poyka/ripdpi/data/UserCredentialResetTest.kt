package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.KeystoreAwgCredentialStore
import com.poyka.ripdpi.data.xray.KeystoreXrayProfileSecretStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProviderSelectionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserCredentialResetTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `bulk reset removes orphaned relay awg and xray entries`() =
        runTest {
            val stores =
                listOf(
                    "relay_profile_cache" to "relay-profile:orphan",
                    "relay_credentials_secure" to "relay-credentials:orphan",
                    "warp_profiles" to "profile:orphan",
                    "warp_credentials_secure" to "warp_credentials:orphan",
                    "warp_endpoint_cache" to "endpoint:orphan:network",
                    "awg_credentials_secure" to "awg_credentials:orphan",
                    "xray_profile_metadata" to "xray-profile:orphan",
                    "xray_profile_secrets_secure" to "xray-secrets:orphan",
                    "xray_provider_selection" to "selection",
                    KeystoreWsTunnelWorkerCredentialStore.CredentialsPrefsName to "worker:orphan",
                )
            stores.forEach { (name, key) ->
                context
                    .getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .putString(key, "payload")
                    .commit()
            }

            SharedPreferencesRelayProfileStore(context).clearAll()
            KeystoreRelayCredentialStore(context).clearAll()
            SharedPreferencesWarpProfileStore(context).clearAll()
            KeystoreWarpCredentialStore(context).clearAll()
            SharedPreferencesWarpEndpointStore(context).clearAll()
            KeystoreAwgCredentialStore(context).clearAll()
            SharedPreferencesXrayProfileMetadataStore(context).clearAll()
            KeystoreXrayProfileSecretStore(context).clearAll()
            SharedPreferencesXrayProviderSelectionStore(context).clear()
            KeystoreWsTunnelWorkerCredentialStore(context).clearAll()

            stores.forEach { (name, _) ->
                assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
            }
        }
}
