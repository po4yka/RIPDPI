package com.poyka.ripdpi.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.activities.AndroidKeystoreConfigEditorDraftCipher
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigEditorDraftRecordKey
import com.poyka.ripdpi.activities.ConfigEditorDraftRestoreResult
import com.poyka.ripdpi.activities.ConfigEditorSession
import com.poyka.ripdpi.activities.EncryptedConfigEditorDraftStore
import com.poyka.ripdpi.activities.newConfigEditorRecoverySessionId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConfigEditorDraftKeystoreInstrumentedTest {
    @Test
    fun productionKeystoreCipherPersistsOpaqueDraftAndRejectsTampering() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences =
                context.getSharedPreferences(
                    "config-editor-keystore-test-${UUID.randomUUID()}",
                    Context.MODE_PRIVATE,
                )
            val recoverySessionId = newConfigEditorRecoverySessionId()
            val baseline = ConfigDraft()
            val marker = "instrumented-recovery-marker"
            val session =
                ConfigEditorSession(
                    presetId = "custom",
                    baselineDraft = baseline,
                    draft = baseline.copy(relayMasqueAuthToken = marker),
                    draftRevision = 1L,
                )
            val firstStore =
                EncryptedConfigEditorDraftStore(
                    preferences = preferences,
                    cipher = AndroidKeystoreConfigEditorDraftCipher(),
                    currentTimeMillis = System::currentTimeMillis,
                )

            firstStore.persist(recoverySessionId, session)

            val encrypted = requireNotNull(preferences.getString(ConfigEditorDraftRecordKey, null))
            assertFalse(encrypted.contains(marker))
            assertFalse(encrypted.contains("recoverySessionId"))
            val recreatedStore =
                EncryptedConfigEditorDraftStore(
                    preferences = preferences,
                    cipher = AndroidKeystoreConfigEditorDraftCipher(),
                    currentTimeMillis = System::currentTimeMillis,
                )
            val restored = recreatedStore.restore(recoverySessionId)
            assertTrue(restored is ConfigEditorDraftRestoreResult.Restored)
            assertEquals(
                marker,
                (restored as ConfigEditorDraftRestoreResult.Restored).session.draft?.relayMasqueAuthToken,
            )

            val tampered =
                encrypted.replaceRange(
                    encrypted.lastIndex,
                    encrypted.length,
                    if (encrypted.last() ==
                        'A'
                    ) {
                        "B"
                    } else {
                        "A"
                    },
                )
            preferences.edit().putString(ConfigEditorDraftRecordKey, tampered).commit()

            assertEquals(
                ConfigEditorDraftRestoreResult.MissingOrInvalid,
                recreatedStore.restore(recoverySessionId),
            )
            assertFalse(preferences.contains(ConfigEditorDraftRecordKey))
            preferences.edit().clear().commit()
        }
}
