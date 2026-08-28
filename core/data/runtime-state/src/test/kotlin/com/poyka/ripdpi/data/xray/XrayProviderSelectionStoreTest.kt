package com.poyka.ripdpi.data.xray

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XrayProviderSelectionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences =
        context
            .getSharedPreferences("xray_provider_selection", Context.MODE_PRIVATE)
            .also { check(it.edit().clear().commit()) }

    @Test
    fun `metadata reads and enumeration reject records under a different key`() =
        runTest {
            val metadata = context.getSharedPreferences("xray_profile_metadata", Context.MODE_PRIVATE)
            val record = XrayProfileMetadataRecord(profileId = "other", revision = "revision-a")
            check(
                metadata
                    .edit()
                    .clear()
                    .putString(
                        "xray-profile:default",
                        RipDpiJson.encodeToString(XrayProfileMetadataRecord.serializer(), record),
                    ).commit(),
            )
            val store = SharedPreferencesXrayProfileMetadataStore(context)
            assertNull(store.load("default"))
            assertTrue(store.list().isEmpty())
        }

    @Test
    fun `corrupt stored selection cannot silently choose Native`() {
        check(preferences.edit().putString("selection", "not-json").commit())
        val store = SharedPreferencesXrayProviderSelectionStore(context)
        assertTrue(runCatching { store.current() }.isFailure)
    }

    @Test
    fun `unknown stored provider cannot silently choose Native`() {
        check(preferences.edit().putString("selection", """{"providerKind":"unsupported"}""").commit())
        val store = SharedPreferencesXrayProviderSelectionStore(context)
        assertTrue(runCatching { store.current().kind }.isFailure)
    }

    @Test
    fun `absent selection is Native and a valid Xray selection survives recreation`() {
        val store = SharedPreferencesXrayProviderSelectionStore(context)
        assertEquals(VpnProviderKind.Native, store.current().kind)
        val expected = XrayProviderSelectionRecord(XrayProviderSelectionRecord.ProviderKindXray, "owned-profile")
        store.update(expected)
        assertEquals(expected, SharedPreferencesXrayProviderSelectionStore(context).current())
    }

    @Test
    fun `failed selection disk commit is reported before confirmation succeeds`() {
        val failingPreferences =
            object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(
                            key: String?,
                            value: String?,
                        ): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }

                        override fun commit(): Boolean = false
                    }
                }
            }
        val failingContext =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(
                    name: String?,
                    mode: Int,
                ): SharedPreferences = failingPreferences
            }
        val store = SharedPreferencesXrayProviderSelectionStore(failingContext)
        assertTrue(
            runCatching {
                store.update(XrayProviderSelectionRecord(XrayProviderSelectionRecord.ProviderKindXray, "owned-profile"))
            }.isFailure,
        )
    }
}
