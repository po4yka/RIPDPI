## Context

Connectivity and strategy plans share a native coordinator. A strategy run owns temporary candidate runtimes. Kotlin owns the service route lease, report recovery, and remembered settings.

## Decisions

- Reuse monotonic deadlines and cancellation. Map local interruption to execution dispositions and keep network failures separate.
- Correlate the first QUIC response inside transport receive. Record the response without treating it as an authenticated transport or application exchange.
- Publish a structured snapshot from completed candidates without preparing an applicable winner.
- Bind a concurrency result to its own controls. Restrict direct concurrency measurement to raw-path plans.
- Select candidate work within each lane cap. Derive completion and progress from that budget. Keep the full candidate inventory in its existing plan snapshot.
- Use measured control, candidate and target coverage for confidence and background eligibility. Keep the normal cleanup and network-scope gates.

## Contracts and ownership

The portfolio record assigns the Rust QUIC, DNS/checkpoint and concurrency files to three separate worktrees. The integration owner serializes Kotlin policy, final score, general plan accounting, fixtures, and the combined tests. Preserve the current JNI wire shape where possible. Add no dependencies or platform requirements.

## Verification and rollback

Run owning locked Rust regressions with loopback fixtures, focused Kotlin tests, and the repository source gates. Capture cancellation, partial recovery, malformed tasks, and the memory gate. A rollback reverts the related behavior commits together. Existing local diagnostic history stays readable.
