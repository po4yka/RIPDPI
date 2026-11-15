## Context

Task DGN-1794732423381679 repairs DIA-06 F1-F6. The generic runner is dormant. UDP and TLS loopback tests provide local evidence.

## Goals / Non-Goals

- Goal: correct six library contracts, one implementation commit per finding.
- Non-goal: production scheduling, complete SVCB endpoint selection, new transports, plaintext fallback, Android or external-server acceptance.

## Decisions

- Keep NXDOMAIN distinct. Only clean missing HTTPS data becomes NoEchConfig.
- Extract private DNS code under the driver feature directory. A private resolver-config seam tests the real lookup path on loopback.
- Check mode and mandatory compatibility before ech extraction. Reject no-default-alpn because this driver does not offer ALPN. Unknown optional parameters can be ignored.
- Reject unsupported active-path hints before I/O. Allow network_scope_key as opaque metadata.
- Preserve existing enum variants and add explicit rejection and uncertain-handshake variants. Update exhaustive matches.
- Preserve authenticated retry, certificate checks and peer pinning. Use a private config-builder seam for the test CA, without a public trust bypass.
- Apply one checked DNS deadline, including retries.

## Contracts and ownership

- Production changes affect ripdpi-diagnostics-probes only. No Kotlin, JNI, wire, storage, activation or configuration schema changes.
- Portfolio ownership assigns isolated DNS, TLS and context worktrees. Integration serializes driver and enum merges.
- Integration owns Cargo.toml/Cargo.lock test edges and generated API snapshot. Package versions stay pinned.
- Tests use rustls 0.23.46, Hickory ac6cc706d9641ce8440bd20e2da20d51b522555f, tokio-rustls 0.26.6 and boring 5.5.0. No external probes or real secrets.

## Risks / Trade-offs

- New enum variants break exhaustive matches: compile affected targets and regenerate the API snapshot.
- Deadline tests need a stated scheduler tolerance.
- Retain dormant/unprotected warnings; do not add a production registry entry.

## Migration Plan

Use six fix commits. Run owning tests and all-target Clippy with --locked, independent general and async review, just ci-gates, architecture health and cargo metadata --locked. Device, deployment and packaged Android artifact gates are not applicable to this dormant library repair. Hosted CI is separate evidence after push. Rollback uses new revert commits, not history rewrites.
