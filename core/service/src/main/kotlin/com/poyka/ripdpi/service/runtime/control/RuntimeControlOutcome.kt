package com.poyka.ripdpi.service.runtime.control

/**
 * The result of executing a [RuntimeControlCommand].
 *
 * A command that fails surfaces its failure as a thrown exception — the control
 * plane never swallows one — so callers keep exactly the error semantics of the
 * coordinator call they replaced. The outcome only distinguishes whether the
 * command was actually serviced.
 */
internal sealed interface RuntimeControlOutcome {
    /** The command was delegated to a runtime operation and ran to completion. */
    data object Completed : RuntimeControlOutcome

    /**
     * No runtime operation serviced the command — [detail] explains why (for
     * example, a session-scoped command reaching a process-level actions layer).
     * This is not a failure: it is the documented incremental state while the
     * seam is being adopted.
     */
    data class Ignored(
        val detail: String,
    ) : RuntimeControlOutcome
}
