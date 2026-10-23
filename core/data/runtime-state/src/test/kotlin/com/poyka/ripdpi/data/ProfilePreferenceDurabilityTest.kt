package com.poyka.ripdpi.data

import android.content.SharedPreferences
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePreferenceDurabilityTest {
    @Test
    fun `failed migration is unpublished until the exact cached generation is confirmed`() =
        runTest {
            val preferences = DivergentProfilePreferences(legacyGroupPreferences())
            val originalDisk = preferences.diskSnapshot()
            val first = ProxyGroupCatalog(DurablePreferenceGroupBlob(preferences))
            val second = ProxyGroupCatalog(DurablePreferenceGroupBlob(preferences))
            val firstObserved = mutableListOf<List<ProxyGroup>>()
            val secondObserved = mutableListOf<List<ProxyGroup>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                first.groups().collect { firstObserved += it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                second.groups().collect { secondObserved += it }
            }
            preferences.acceptCommits = false

            assertTrue(runCatching { first.load() }.exceptionOrNull() is IllegalStateException)
            val cachedGroups = decodePreferenceGroups(preferences.getString(GroupPayloadKey, null)!!)
            val cachedGeneration = cachedGroups.single().ownershipGeneration
            assertTrue(cachedGeneration.isNotBlank())
            assertEquals(ProxyGroupSchema.SCHEMA_VERSION, preferences.getInt(GroupVersionKey, 1))
            assertEquals(originalDisk, preferences.diskSnapshot())
            assertTrue(firstObserved.isEmpty())

            // Both an existing owner and a newly constructed store share the uncertainty boundary.
            assertTrue(runCatching { first.load() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { second.load() }.exceptionOrNull() is IllegalStateException)
            assertEquals(cachedGroups, decodePreferenceGroups(preferences.getString(GroupPayloadKey, null)!!))
            assertEquals(originalDisk, preferences.diskSnapshot())
            assertTrue(firstObserved.isEmpty())
            assertTrue(secondObserved.isEmpty())
            assertEquals(3, preferences.commitAttempts.size)
            assertEquals(3, preferences.commitAttempts.toSet().size)

            preferences.acceptCommits = true
            assertEquals(cachedGroups, second.load())
            assertEquals(listOf(cachedGroups), secondObserved)
            assertEquals(cachedGroups, first.load())
            assertEquals(listOf(cachedGroups), firstObserved)
            assertEquals(preferences.all, preferences.diskSnapshot())
            assertEquals(4, preferences.commitAttempts.size)

            val cold = preferences.restartFromDisk()
            assertEquals(cachedGroups, ProxyGroupCatalog(DurablePreferenceGroupBlob(cold)).load())
            assertEquals(
                cachedGeneration,
                decodePreferenceGroups(cold.getString(GroupPayloadKey, null)!!).single().ownershipGeneration,
            )
            assertTrue(cold.commitAttempts.isEmpty())
        }

    @Test
    fun `process death before confirmation does not make an uncommitted generation durable`() =
        runTest {
            val preferences = DivergentProfilePreferences(legacyGroupPreferences())
            preferences.acceptCommits = false
            val catalog = ProxyGroupCatalog(DurablePreferenceGroupBlob(preferences))

            assertTrue(runCatching { catalog.load() }.exceptionOrNull() is IllegalStateException)
            val abandoned = decodePreferenceGroups(preferences.getString(GroupPayloadKey, null)!!).single()
            val cold = preferences.restartFromDisk()
            assertEquals(1, cold.getInt(GroupVersionKey, 1))
            assertEquals(
                "",
                decodePreferenceGroups(cold.getString(GroupPayloadKey, null)!!).single().ownershipGeneration,
            )

            val accepted = ProxyGroupCatalog(DurablePreferenceGroupBlob(cold)).load().single()
            assertNotEquals(abandoned.ownershipGeneration, accepted.ownershipGeneration)
            assertEquals(abandoned.copy(ownershipGeneration = accepted.ownershipGeneration), accepted)
            assertEquals(cold.all, cold.diskSnapshot())
            assertEquals(1, cold.commitAttempts.size)
            assertEquals(
                accepted,
                ProxyGroupCatalog(DurablePreferenceGroupBlob(cold.restartFromDisk())).load().single(),
            )
        }

    @Test
    fun `unconfirmed preferences block a later mutation before its body can run`() {
        val preferences = DivergentProfilePreferences(mapOf(JournalKey to "original-intent"))
        val owner = ProfilePreferenceDurability.forPreferences(preferences)
        preferences.acceptCommits = false
        assertThrows(IllegalStateException::class.java) {
            owner.access(preferences) { owner.commit(preferences.edit().putString(JournalKey, "replacement-intent")) }
        }
        val newOwner = ProfilePreferenceDurability.forPreferences(preferences)
        assertSame(owner, newOwner)
        var mutationEntered = false

        assertThrows(IllegalStateException::class.java) {
            newOwner.access(preferences) {
                mutationEntered = true
                newOwner.commit(preferences.edit().remove(JournalKey))
            }
        }

        assertFalse(mutationEntered)
        assertEquals("replacement-intent", preferences.getString(JournalKey, null))
        assertEquals("original-intent", preferences.diskSnapshot()[JournalKey])
        preferences.acceptCommits = true
        assertEquals("replacement-intent", newOwner.access(preferences) { preferences.getString(JournalKey, null) })
        assertEquals("replacement-intent", preferences.restartFromDisk().getString(JournalKey, null))
    }

    @Test
    fun `journal replacement completion and clear remain unreadable until confirmed`() {
        for (mutation in JournalPreferenceMutation.entries) {
            val initial = mapOf(JournalKey to "prepared-intent", "other-state" to "retained")
            val preferences = DivergentProfilePreferences(initial)
            val owner = ProfilePreferenceDurability.forPreferences(preferences)
            preferences.acceptCommits = false
            assertThrows(IllegalStateException::class.java) {
                owner.access(preferences) { owner.commit(mutation.edit(preferences)) }
            }
            val cachedJournal = preferences.getString(JournalKey, null)
            assertEquals(mutation.expectedJournal, cachedJournal)
            assertEquals(initial, preferences.diskSnapshot())
            val newOwner = ProfilePreferenceDurability.forPreferences(preferences)
            var readEntered = false

            assertThrows(IllegalStateException::class.java) {
                newOwner.access(preferences) {
                    readEntered = true
                    preferences.getString(JournalKey, null)
                }
            }

            assertFalse(readEntered)
            assertEquals(initial, preferences.diskSnapshot())
            preferences.acceptCommits = true
            assertEquals(cachedJournal, newOwner.access(preferences) { preferences.getString(JournalKey, null) })
            assertEquals(preferences.all, preferences.diskSnapshot())
            val cold = preferences.restartFromDisk()
            assertEquals(
                cachedJournal,
                ProfilePreferenceDurability.forPreferences(cold).access(cold) { cold.getString(JournalKey, null) },
            )
            if (mutation == JournalPreferenceMutation.CLEAR) {
                assertNull(cold.getString("other-state", null))
            } else {
                assertEquals("retained", cold.getString("other-state", null))
            }
            assertTrue(cold.commitAttempts.isEmpty())
            assertEquals(3, preferences.commitAttempts.size)
            assertEquals(3, preferences.commitAttempts.toSet().size)
        }
    }
}

private enum class JournalPreferenceMutation(
    val expectedJournal: String?,
) {
    REPLACE("replacement-intent"),
    COMPLETE(null),
    CLEAR(null),
    ;

    fun edit(preferences: SharedPreferences): SharedPreferences.Editor =
        when (this) {
            REPLACE -> preferences.edit().putString(JournalKey, expectedJournal)
            COMPLETE -> preferences.edit().remove(JournalKey)
            CLEAR -> preferences.edit().clear()
        }
}

// Exercises production schema and durability boundaries without claiming AndroidKeyStore encryption coverage.
private class DurablePreferenceGroupBlob(
    private val preferences: SharedPreferences,
) : ProxyGroupBlobStore {
    private val durability = ProfilePreferenceDurability.forPreferences(preferences)

    override fun clear() = durability.access(preferences) { durability.commit(preferences.edit().clear()) }

    override fun read(): String? =
        durability.access(preferences) {
            preferences.getString(GroupPayloadKey, null)?.let { encoded ->
                ProxyGroupSchema.read(encoded, preferences.getInt(GroupVersionKey, 1), ::write)
            }
        }

    override fun write(json: String) =
        durability.access(preferences) {
            durability.commit(
                preferences
                    .edit()
                    .putString(GroupPayloadKey, json)
                    .putInt(GroupVersionKey, ProxyGroupSchema.SCHEMA_VERSION),
            )
        }
}

/** Android publishes an editor to its process cache even when the subsequent disk commit fails. */
private class DivergentProfilePreferences(
    initialDisk: Map<String, Any?>,
) : SharedPreferences {
    private val memory = initialDisk.toMutableMap()
    private val disk = initialDisk.toMutableMap()
    var acceptCommits = true
    val commitAttempts = mutableListOf<Map<String, Any?>>()

    fun diskSnapshot(): Map<String, Any?> = disk.toMap()

    fun restartFromDisk() = DivergentProfilePreferences(diskSnapshot())

    override fun getAll(): Map<String, *> = memory.toMap()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = memory[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = (memory[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = memory[key] as? Int ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = memory[key] as? Long ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = memory[key] as? Float ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = memory[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = memory.containsKey(key)

    override fun edit(): SharedPreferences.Editor = DivergentEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class DivergentEditor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clearRequested = false

        private fun put(
            key: String?,
            value: Any?,
        ): SharedPreferences.Editor =
            apply {
                changes[requireNotNull(key)] = value
            }

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = put(key, value)

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = put(key, values?.toSet())

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = put(key, value)

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = put(key, value)

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = put(key, null)

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            if (clearRequested) memory.clear()
            changes.forEach { (key, value) ->
                if (value == null) memory.remove(key) else memory[key] = value
            }
            commitAttempts += memory.toMap()
            if (acceptCommits) {
                disk.clear()
                disk.putAll(memory)
            }
            return acceptCommits
        }

        override fun apply() {
            error("The durability boundary must use checked synchronous commits")
        }
    }
}

private const val GroupPayloadKey = "groups"
private const val GroupVersionKey = "group-schema"
private const val JournalKey = "pending-mutation"

private fun legacyGroupPreferences(): Map<String, Any?> =
    mapOf(
        GroupPayloadKey to
            """[{"id":"subscription","name":"Saved","type":"SUBSCRIPTION","order":0,"isSelector":true}]""",
        GroupVersionKey to 1,
    )

private fun decodePreferenceGroups(encoded: String): List<ProxyGroup> =
    RipDpiJson.decodeFromString(ListSerializer(ProxyGroup.serializer()), encoded)
