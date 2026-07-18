package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.SavedStateHandle
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyConfigEditorViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `new view model restores every dirty field while handle contains only opaque id`() {
        val store = FakeStrategyConfigDraftStore()
        val firstHandle = SavedStateHandle()
        val first = StrategyConfigEditorViewModel(firstHandle, store)
        first.syncBuiltIn("tcp: split")
        first.selectSource(StrategyConfigSource.LuaScript, "tcp: split")
        first.update {
            copy(
                configText = "version: 1",
                luaPath = "custom.lua",
                luaFunction = "route",
            )
        }

        val sessionId = firstHandle.get<String>(StrategyConfigSessionIdSavedStateKey)
        assertNotNull(sessionId)
        assertTrue(isValidStrategyConfigSessionId(requireNotNull(sessionId)))
        assertEquals(setOf(StrategyConfigSessionIdSavedStateKey), firstHandle.keys())
        assertNotEquals("version: 1", sessionId)

        val restoredHandle = SavedStateHandle(mapOf(StrategyConfigSessionIdSavedStateKey to sessionId))
        val restored = StrategyConfigEditorViewModel(restoredHandle, store)
        restored.syncBuiltIn("tcp: settings changed")

        val session = requireNotNull(restored.session)
        assertEquals(StrategyConfigSource.LuaScript, session.draft.source)
        assertEquals("version: 1", session.draft.configText)
        assertEquals("custom.lua", session.draft.luaPath)
        assertEquals("route", session.draft.luaFunction)
        assertEquals("tcp: split", session.baseline.configText)
        assertTrue(session.isDirty)
        assertFalse(session.isSaving)
        assertEquals(setOf(StrategyConfigSessionIdSavedStateKey), restoredHandle.keys())
    }

    @Test
    fun `latest edit made during save survives restore without active save`() {
        val store = FakeStrategyConfigDraftStore()
        val handle = SavedStateHandle()
        val first = StrategyConfigEditorViewModel(handle, store)
        first.syncBuiltIn("tcp: split")
        first.update { copy(configText = "tcp: submitted") }
        requireNotNull(first.beginSave())
        first.update { copy(configText = "tcp: newest") }

        val restored =
            StrategyConfigEditorViewModel(
                SavedStateHandle(mapOf(StrategyConfigSessionIdSavedStateKey to handle.sessionId())),
                store,
            )

        assertEquals("tcp: newest", requireNotNull(restored.session).draft.configText)
        assertFalse(requireNotNull(restored.session).isSaving)
    }

    @Test
    fun `successful clean save and explicit discard delete persistence`() =
        runTest {
            val store = FakeStrategyConfigDraftStore()
            val handle = SavedStateHandle()
            val viewModel = StrategyConfigEditorViewModel(handle, store)
            viewModel.syncBuiltIn("tcp: split")
            viewModel.update { copy(configText = "tcp: saved") }
            val request = requireNotNull(viewModel.beginSave())

            viewModel.completeSave(request, succeeded = true)

            assertNull(store.sessions[handle.sessionId()])
            viewModel.update { copy(configText = "tcp: discarded") }
            assertNotNull(store.sessions[handle.sessionId()])

            viewModel.discard()

            assertNull(store.sessions[handle.sessionId()])
        }

    @Test
    fun `failed and cancelled saves retain the newest dirty draft`() =
        runTest {
            val store = FakeStrategyConfigDraftStore()
            val failedHandle = SavedStateHandle()
            val failed = StrategyConfigEditorViewModel(failedHandle, store)
            failed.syncBuiltIn("tcp: split")
            failed.update { copy(configText = "tcp: failed") }
            val failedRequest = requireNotNull(failed.beginSave())

            failed.completeSave(failedRequest, succeeded = false)

            assertEquals(
                "tcp: failed",
                store.sessions
                    .getValue(failedHandle.sessionId())
                    .draft.configText,
            )
            assertFalse(requireNotNull(failed.session).isSaving)

            val cancelledHandle = SavedStateHandle()
            val cancelled = StrategyConfigEditorViewModel(cancelledHandle, store)
            cancelled.syncBuiltIn("tcp: split")
            cancelled.update { copy(configText = "tcp: submitted") }
            val cancelledRequest = requireNotNull(cancelled.beginSave())
            cancelled.update { copy(configText = "tcp: newest after submit") }

            var wasCancelled = false
            try {
                cancelled.runSave(cancelledRequest) { throw CancellationException("recreated") }
            } catch (_: CancellationException) {
                wasCancelled = true
            }

            assertTrue(wasCancelled)
            assertEquals(
                "tcp: newest after submit",
                store.sessions
                    .getValue(cancelledHandle.sessionId())
                    .draft.configText,
            )
            assertFalse(requireNotNull(cancelled.session).isSaving)
        }

    @Test
    fun `pending settings sync cannot replace a restored dirty session`() {
        val sessionId = newStrategyConfigSessionId()
        val store = PausedRestoreStrategyConfigDraftStore(dirtySession())
        val viewModel =
            StrategyConfigEditorViewModel(
                SavedStateHandle(mapOf(StrategyConfigSessionIdSavedStateKey to sessionId)),
                store,
            )

        viewModel.syncBuiltIn("tcp: late settings")
        assertTrue(viewModel.isHydrating)
        store.finishRestore()

        val session = requireNotNull(viewModel.session)
        assertFalse(viewModel.isHydrating)
        assertEquals("tcp: draft", session.draft.configText)
        assertEquals("tcp: baseline", session.baseline.configText)
        assertTrue(session.isDirty)
    }

    @Test
    fun `successful save waits until clean draft deletion is durable`() =
        runTest {
            val store = ControllableDeleteStrategyConfigDraftStore()
            val handle = SavedStateHandle()
            val viewModel = StrategyConfigEditorViewModel(handle, store)
            viewModel.syncBuiltIn("tcp: split")
            viewModel.update { copy(configText = "tcp: saved") }
            val request = requireNotNull(viewModel.beginSave())
            store.pauseNextDelete = true

            val result =
                async {
                    viewModel.runSave(request) {
                        StrategyConfigBanner(
                            title = "saved",
                            message = "saved",
                            tone = WarningBannerTone.Info,
                            saved = true,
                        )
                    }
                }
            runCurrent()

            assertFalse(result.isCompleted)
            assertNotNull(store.sessions[handle.sessionId()])
            store.finishDelete()

            assertTrue(result.await().saved)
            assertNull(store.sessions[handle.sessionId()])
        }

    @Test
    fun `pending stale persist cannot recreate draft after successful save`() =
        runTest {
            val store = PausedPersistStrategyConfigDraftStore()
            val handle = SavedStateHandle()
            val viewModel = StrategyConfigEditorViewModel(handle, store)
            viewModel.syncBuiltIn("tcp: split")
            viewModel.update { copy(configText = "tcp: first") }
            runCurrent()
            viewModel.update { copy(configText = "tcp: saved") }
            val request = requireNotNull(viewModel.beginSave())

            val completion = async { viewModel.completeSave(request, succeeded = true) }
            runCurrent()
            assertFalse(completion.isCompleted)

            store.finishPersist()
            completion.await()
            runCurrent()

            assertNull(store.sessions[handle.sessionId()])
        }

    @Test
    fun `throwing delete does not acknowledge explicit discard`() =
        runTest {
            val store = ControllableDeleteStrategyConfigDraftStore()
            val handle = SavedStateHandle()
            val viewModel = StrategyConfigEditorViewModel(handle, store)
            viewModel.syncBuiltIn("tcp: split")
            viewModel.update { copy(configText = "tcp: keep") }
            store.failNextDelete = true

            val failure = runCatching { viewModel.discard() }.exceptionOrNull()

            assertNotNull(failure)
            assertNotNull(store.sessions[handle.sessionId()])
            viewModel.update { copy(luaFunction = "still-editable") }
            assertEquals(
                "still-editable",
                store.sessions
                    .getValue(handle.sessionId())
                    .draft.luaFunction,
            )
        }

    @Test
    fun `discard is rejected while save remains active`() =
        runTest {
            val store = FakeStrategyConfigDraftStore()
            val handle = SavedStateHandle()
            val viewModel = StrategyConfigEditorViewModel(handle, store)
            viewModel.syncBuiltIn("tcp: split")
            viewModel.update { copy(configText = "tcp: saving") }
            requireNotNull(viewModel.beginSave())

            val failure = runCatching { viewModel.discard() }.exceptionOrNull()

            assertNotNull(failure)
            assertNotNull(store.sessions[handle.sessionId()])
            assertTrue(requireNotNull(viewModel.session).isSaving)
        }

    private fun SavedStateHandle.sessionId(): String = requireNotNull(get<String>(StrategyConfigSessionIdSavedStateKey))

    private fun dirtySession(): StrategyConfigEditorSession =
        StrategyConfigEditorSession(
            baseline =
                StrategyConfigDraft(
                    source = StrategyConfigSource.BuiltIn,
                    configText = "tcp: baseline",
                    luaPath = "",
                    luaFunction = "",
                ),
            draft =
                StrategyConfigDraft(
                    source = StrategyConfigSource.CustomYaml,
                    configText = "tcp: draft",
                    luaPath = "draft.lua",
                    luaFunction = "route",
                ),
        )
}

private open class FakeStrategyConfigDraftStore : StrategyConfigDraftStore {
    val sessions = mutableMapOf<String, StrategyConfigEditorSession>()

    override suspend fun restore(sessionId: String): StrategyConfigEditorSession? = sessions[sessionId]

    override suspend fun persist(
        sessionId: String,
        session: StrategyConfigEditorSession,
    ) {
        sessions[sessionId] =
            StrategyConfigEditorSession(
                baseline = session.baseline,
                draft = session.draft,
            )
    }

    override suspend fun delete(sessionId: String) {
        sessions.remove(sessionId)
    }
}

private class ControllableDeleteStrategyConfigDraftStore : FakeStrategyConfigDraftStore() {
    var pauseNextDelete = false
    var failNextDelete = false
    private var pendingDelete: Pair<String, kotlin.coroutines.Continuation<Unit>>? = null

    override suspend fun delete(sessionId: String) {
        when {
            failNextDelete -> {
                failNextDelete = false
                error("delete failed")
            }

            pauseNextDelete -> {
                pauseNextDelete = false
                kotlin.coroutines.suspendCoroutine { pendingDelete = sessionId to it }
            }

            else -> {
                super.delete(sessionId)
            }
        }
    }

    fun finishDelete() {
        val (sessionId, continuation) = requireNotNull(pendingDelete)
        sessions.remove(sessionId)
        pendingDelete = null
        continuation.resumeWith(Result.success(Unit))
    }
}

private class PausedRestoreStrategyConfigDraftStore(
    private val restored: StrategyConfigEditorSession,
) : StrategyConfigDraftStore {
    private var continuation: kotlin.coroutines.Continuation<StrategyConfigEditorSession?>? = null

    override suspend fun restore(sessionId: String): StrategyConfigEditorSession? =
        kotlin.coroutines.suspendCoroutine { continuation = it }

    fun finishRestore() {
        requireNotNull(continuation).resumeWith(Result.success(restored))
    }

    override suspend fun persist(
        sessionId: String,
        session: StrategyConfigEditorSession,
    ) = Unit

    override suspend fun delete(sessionId: String) = Unit
}

private class PausedPersistStrategyConfigDraftStore : FakeStrategyConfigDraftStore() {
    private var pendingPersist:
        Triple<String, StrategyConfigEditorSession, kotlin.coroutines.Continuation<Unit>>? = null

    override suspend fun persist(
        sessionId: String,
        session: StrategyConfigEditorSession,
    ) {
        kotlin.coroutines.suspendCoroutine { continuation ->
            pendingPersist = Triple(sessionId, session, continuation)
        }
    }

    fun finishPersist() {
        val (sessionId, session, continuation) = requireNotNull(pendingPersist)
        sessions[sessionId] =
            StrategyConfigEditorSession(
                baseline = session.baseline,
                draft = session.draft,
            )
        pendingPersist = null
        continuation.resumeWith(Result.success(Unit))
    }
}
