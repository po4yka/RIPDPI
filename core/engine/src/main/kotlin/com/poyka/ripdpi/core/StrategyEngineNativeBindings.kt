package com.poyka.ripdpi.core

import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class StrategyProbeResultDto(
    @SerialName("strategy_id")
    val strategyId: String,
    val domain: String,
    val success: Boolean,
    @SerialName("latency_ms")
    val latencyMs: Long,
)

interface StrategyEngineBindings {
    /**
     * Loads the Lua script at [path], confined to the absolute `<filesDir>/lua`
     * [baseDir] jail (seeded first-wins from [baseDir] on the first load — no
     * trust-on-first-use). A [path] that escapes the jail is rejected. Returns
     * `null` on success or an error string.
     */
    fun luaLoadScript(
        baseDir: String,
        path: String,
    ): String?

    fun luaReloadConfig(): String?

    // Nullable: a contained native panic marshals to null. Consumers coalesce so
    // a non-null Kotlin array is never assumed at the JNI boundary (see Gap 2).
    fun luaListStrategies(): Array<String>?

    fun luaLoadedScriptPaths(): Array<String>?

    fun luaValidateScript(path: String): String?

    fun validateStrategyConfigText(configText: String): String?

    fun injectProbeResults(results: Array<StrategyProbeResultDto>): String?
}

/**
 * Raw JNI symbol holder for the Lua strategy engine. The native side keeps a
 * process-global Lua engine and loaded-script registry, so this class has no
 * opaque lifecycle handle like [RipDpiProxyBindings] or [Tun2SocksBindings].
 * Production callers must use [ProcessGlobalStrategyEngineBindings], which is
 * the Kotlin ownership contract for this handle-less native API.
 */
class StrategyEngineNativeBindings internal constructor() : StrategyEngineBindings {
    init {
        RipDpiNativeLoader.ensureLoaded()
    }

    external override fun luaLoadScript(
        baseDir: String,
        path: String,
    ): String?

    external override fun luaReloadConfig(): String?

    external override fun luaListStrategies(): Array<String>?

    external override fun luaLoadedScriptPaths(): Array<String>?

    external override fun luaValidateScript(path: String): String?

    external override fun validateStrategyConfigText(configText: String): String?

    override fun injectProbeResults(results: Array<StrategyProbeResultDto>): String? =
        injectProbeResultsJson(StrategyProbeResultJson.encodeToString(results.toList()))

    private external fun injectProbeResultsJson(resultsJson: String): String?
}

/**
 * Process-global owner for the handle-less Lua strategy JNI bindings.
 *
 * The Rust Lua engine is process-global, and changing it to a per-session
 * handle would require native signature changes. This wrapper therefore
 * serializes mutating calls through a process-global [ReentrantLock]:
 * [luaLoadScript], [luaReloadConfig], and [injectProbeResults] can never enter
 * native mutation concurrently, even when callers use separate wrapper
 * instances. Read-only listing and validation calls are forwarded without
 * taking the mutation lock.
 *
 * The lock is a plain JVM lock rather than a coroutine `Mutex` so these
 * non-suspend binding methods acquire it directly, without a `runBlocking`
 * that would park an IO-dispatcher thread for the lock duration.
 */
class ProcessGlobalStrategyEngineBindings(
    private val delegate: StrategyEngineBindings = StrategyEngineNativeBindings(),
) : StrategyEngineBindings {
    override fun luaLoadScript(
        baseDir: String,
        path: String,
    ): String? =
        withProcessGlobalLuaMutationLock {
            delegate.luaLoadScript(baseDir, path)
        }

    override fun luaReloadConfig(): String? =
        withProcessGlobalLuaMutationLock {
            delegate.luaReloadConfig()
        }

    override fun luaListStrategies(): Array<String>? = delegate.luaListStrategies()

    override fun luaLoadedScriptPaths(): Array<String>? = delegate.luaLoadedScriptPaths()

    override fun luaValidateScript(path: String): String? = delegate.luaValidateScript(path)

    override fun validateStrategyConfigText(configText: String): String? =
        delegate.validateStrategyConfigText(configText)

    override fun injectProbeResults(results: Array<StrategyProbeResultDto>): String? =
        withProcessGlobalLuaMutationLock {
            delegate.injectProbeResults(results)
        }

    private fun <T> withProcessGlobalLuaMutationLock(block: () -> T): T =
        strategyEngineProcessGlobalMutationLock.withLock {
            block()
        }
}

// Process-global serializer for native Lua mutations. NOTE: a ReentrantLock is
// reentrant, so it will NOT trip on accidental re-entry the way the previous
// coroutine Mutex did. Do not nest mutation calls on one thread (e.g. a mutating
// binding method calling another through this wrapper) — the lock would silently
// permit re-entry into the native engine, defeating the serialization guarantee.
private val strategyEngineProcessGlobalMutationLock = ReentrantLock()

private val StrategyProbeResultJson =
    RipDpiEncodeDefaultsJson
