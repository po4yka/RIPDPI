package com.poyka.ripdpi.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuntimePersistenceMigrationTest {
    @Test
    fun `legacy pause migration preserves ownership and becomes a composite record exactly once`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = pausedState()
        val preferences = context.getSharedPreferences("pause_intent_authority", Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putString("state", legacy(state))
            .commit()
        val persistence = CheckedPauseAuthorityPersistence(context)
        assertEquals(state.copy(command = null), persistence.read())
        val encoded = checkNotNull(preferences.getString("state", null))
        assertFalse(RuntimePersistenceCodec.decode(encoded).second)
        assertEquals(state.copy(command = null), CheckedPauseAuthorityPersistence(context).read())
        assertEquals(encoded, preferences.getString("state", null))
    }

    @Test
    fun `legacy encoder omitted nullable fields without losing a stopped authority`() {
        val migrated = RuntimePersistenceCodec.decode("{\"generation\":42,\"desired\":\"Stopped\"}")
        assertTrue(migrated.second)
        assertEquals(42L, migrated.first.generation)
        assertEquals(DesiredRuntimeState.Stopped, migrated.first.desired)
        assertEquals(null, migrated.first.pause)
        assertEquals(ProfileUtilityState.empty(), migrated.first.profileUtility)
    }

    @Test
    fun `schema two migration retains pause utility history and counters but grants no command capability`() {
        val state =
            pausedState().copy(
                profileUtility =
                    ProfileUtilityState.empty().copy(
                        catalogGeneration = 3,
                        lastSequence = 7,
                    ),
            )
        val schemaTwo = RuntimePersistenceCodec.encode(state).replace("\"schemaVersion\":3", "\"schemaVersion\":2")
        val migrated = RuntimePersistenceCodec.decode(schemaTwo)
        assertTrue(migrated.second)
        assertEquals(state.pause, migrated.first.pause)
        assertEquals(7L, migrated.first.profileUtility.lastSequence)
        assertEquals(null, migrated.first.command)
    }

    @Test
    fun `failed migration commit retains the old payload and refuses initialization`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("pause_intent_authority", Context.MODE_PRIVATE)
        val encoded = legacy(pausedState())
        preferences
            .edit()
            .clear()
            .putString("state", encoded)
            .commit()
        val failing =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): SharedPreferences {
                    val actual = super.getSharedPreferences(name, mode)
                    return object : SharedPreferences by actual {
                        override fun edit(): SharedPreferences.Editor {
                            val editor = actual.edit()
                            return object : SharedPreferences.Editor by editor {
                                override fun putString(
                                    key: String,
                                    value: String?,
                                ): SharedPreferences.Editor {
                                    editor.putString(key, value)
                                    return this
                                }

                                override fun commit() = false
                            }
                        }
                    }
                }
            }
        var initialized: PauseIntentAuthority? = null
        assertTrue(
            runCatching {
                initialized =
                    PauseIntentAuthority(CheckedPauseAuthorityPersistence(failing), clock, RuntimeIntentLinearizer())
                checkNotNull(initialized).initializeAfterMigration()
            }.isFailure,
        )
        assertEquals(null, initialized)
        assertEquals(encoded, preferences.getString("state", null))
    }

    @Test
    fun `corrupt legacy or incomplete composite records cannot manufacture empty utility state`() {
        val legacy = legacy(pausedState())
        val composite = RuntimePersistenceCodec.encode(pausedState())
        val invalid =
            listOf(
                legacy.replace("\"generation\"", "\"unknownGeneration\""),
                legacy.dropLast(1) + ",\"profileUtility\":{}}",
                composite.replace("\"schemaVersion\":3", "\"schemaVersion\":4"),
                composite.replace("\"profileUtility\"", "\"missingUtility\""),
                "{\"schemaVersion\":2}",
            )
        invalid.forEach { assertTrue(runCatching { RuntimePersistenceCodec.decode(it) }.isFailure) }
    }

    private fun pausedState(): PauseAuthorityState {
        val authority = PauseIntentAuthority(Memory(), clock, RuntimeIntentLinearizer())
        authority.initializeAfterMigration()
        authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        return checkNotNull(authority.states.value)
    }

    private fun legacy(state: PauseAuthorityState): String {
        val json = com.poyka.ripdpi.serialization.RipDpiContractJson
        val envelope = json.parseToJsonElement(RuntimePersistenceCodec.encode(state)).jsonObject
        val fields = envelope.getValue("state").jsonObject.filterKeys { it != "profileUtility" && it != "command" }
        return JsonObject(fields).toString()
    }

    private val clock =
        object : PauseClock {
            override fun read() = PauseClockReading(1_800_000_000_000, 10_000, 7)
        }

    private class Memory : PauseAuthorityPersistence {
        var state: PauseAuthorityState? = null

        override fun read() = state

        override fun commit(state: PauseAuthorityState) {
            this.state = state
        }
    }
}
