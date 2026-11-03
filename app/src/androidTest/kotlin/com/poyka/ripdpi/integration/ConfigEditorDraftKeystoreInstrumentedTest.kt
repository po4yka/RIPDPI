package com.poyka.ripdpi.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.ui.screens.connection.AndroidKeystoreConfigEditorDraftCipher
import com.poyka.ripdpi.ui.screens.connection.ConnectionDraft
import com.poyka.ripdpi.ui.screens.connection.ConnectionEditorDraftRecordKey
import com.poyka.ripdpi.ui.screens.connection.ConnectionEditorDraftRestoreResult
import com.poyka.ripdpi.ui.screens.connection.ConnectionEditorSession
import com.poyka.ripdpi.ui.screens.connection.EncryptedConfigEditorDraftStore
import com.poyka.ripdpi.ui.screens.connection.newConfigEditorRecoverySessionId
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
    fun productionKeystoreCipherPersistsOpaqueDraftAndRejectsTampering() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences =
                context.getSharedPreferences(
                    "config-editor-keystore-test-${UUID.randomUUID()}",
                    Context.MODE_PRIVATE,
                )
            val recoverySessionId = newConfigEditorRecoverySessionId()
            val baseline = ConnectionDraft()
            val marker = "instrumented-recovery-marker"
            val session =
                ConnectionEditorSession(
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

            val encrypted = requireNotNull(preferences.getString(ConnectionEditorDraftRecordKey, null))
            assertFalse(encrypted.contains(marker))
            assertFalse(encrypted.contains("recoverySessionId"))
            val recreatedStore =
                EncryptedConfigEditorDraftStore(
                    preferences = preferences,
                    cipher = AndroidKeystoreConfigEditorDraftCipher(),
                    currentTimeMillis = System::currentTimeMillis,
                )
            val restored = recreatedStore.restore(recoverySessionId)
            assertTrue(restored is ConnectionEditorDraftRestoreResult.Restored)
            assertEquals(
                marker,
                (restored as ConnectionEditorDraftRestoreResult.Restored).session.draft?.relayMasqueAuthToken,
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
            preferences.edit().putString(ConnectionEditorDraftRecordKey, tampered).commit()

            assertEquals(
                ConnectionEditorDraftRestoreResult.MissingOrInvalid,
                recreatedStore.restore(recoverySessionId),
            )
            assertFalse(preferences.contains(ConnectionEditorDraftRecordKey))
            preferences.edit().clear().commit()
        }
    }
}
