package com.poyka.ripdpi.services

import java.util.UUID

private data class ScopeOwner(
    val uid: Int,
    val attribution: FlowAttribution.Attributed,
) {
    override fun toString(): String = "ScopeOwner([REDACTED])"
}

/** Bounded app scopes and revisions; all state shares the entries monitor. */
internal class FlowAttributionCache(
    private val capacity: Int,
) {
    private var nextSession = 0L
    private var activeSession = 0L
    private var revision = 0L
    private val scopesByApp = mutableMapOf<ScopeOwner, String>()
    private val entries =
        object : LinkedHashMap<String, ScopeOwner>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScopeOwner>?): Boolean {
                if (size <= capacity) return false
                val identity = eldest?.value
                if (identity != null && scopesByApp[identity] == eldest?.key) scopesByApp.remove(identity)
                return true
            }
        }

    val size: Int get() = synchronized(entries) { entries.size }

    fun beginSession(): Long =
        synchronized(entries) {
            activeSession = ++nextSession
            revision++
            entries.clear()
            scopesByApp.clear()
            activeSession
        }

    fun endSession(session: Long) {
        synchronized(entries) {
            if (activeSession == session) {
                activeSession = 0L
                revision++
                entries.clear()
                scopesByApp.clear()
            }
        }
    }

    fun currentSession(): Long = synchronized(entries) { activeSession }

    fun resolutionRevision(session: Long): Long? =
        synchronized(entries) {
            revision.takeIf { session != 0L && activeSession == session }
        }

    fun commitOwner(
        session: Long,
        observedRevision: Long,
        uid: Int,
        attribution: FlowAttribution?,
    ): FlowOwnerResolution =
        synchronized(entries) {
            if (session == 0L || activeSession != session || revision != observedRevision) {
                FlowOwnerResolution(InvalidUid)
            } else {
                val scope =
                    if (uid != InvalidUid && attribution is FlowAttribution.Attributed) {
                        val owner = ScopeOwner(uid, attribution)
                        val key = scopesByApp.getOrPut(owner) { UUID.randomUUID().toString() }
                        entries[key] = owner
                        key
                    } else {
                        null
                    }
                FlowOwnerResolution(uid, scope)
            }
        }

    fun get(scope: String): FlowAttribution? = synchronized(entries) { entries[scope]?.attribution }

    fun removeIf(predicate: (FlowAttribution) -> Boolean) {
        synchronized(entries) {
            revision++
            entries.values.removeIf { predicate(it.attribution) }
            scopesByApp.keys.removeIf { predicate(it.attribution) }
        }
    }

    fun clear() {
        synchronized(entries) {
            revision++
            entries.clear()
            scopesByApp.clear()
        }
    }
}
