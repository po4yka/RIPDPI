## Context

Task RLY-1794486295363382 follows TUN-10 at 3e626d3e and the later TUN-07 repairs on main. See proposal.md. The existing Tor backend owns circuits and final destinations; raw PT helpers do not.

## Goals / Non-Goals

- Goal: complete the remaining activation and managed helper contracts with existing owners.
- Non-goal: add a generic TCP protocol to Lyrebird, enable unsupported VPN carriers, alter saved identities, or claim external acceptance.

## Decisions

- Extend the established standalone obfs4 rejection to Snowflake and WebTunnel. Do not alias stored kinds to Tor because that changes captured runtime identity and lacks required bridge identity material.
- Preserve legacy editor recognition and fields while removing new standalone choices and their unused locale keys.
- Keep the PT controller and WebTunnel helper as separate protocol owners. Test real helper stdout and exit for version and environment errors.
- Apply the caller readiness timeout to the existing readiness signal. Preserve its original exceptions and cancellation.
- Split managed PT stdout and stderr only. Keep other helper logging contracts intact and preserve process-generation fencing for both readers.
- Close managed stdin before bounded signal escalation. Do not lose process ownership on timeout or cancellation.
- F3 is a scope limitation of Lyrebird, not a requirement to add application half-close to Tor OR links. Document that boundary after removing unsupported standalone activation.

## Contracts and ownership

The portfolio assigns isolated relay, Rust and lifecycle writers. The relay writer alone owns affected locale files and translation export. The coordinator alone owns task/spec records and integration. Cargo.lock, dependency versions, protobuf/JNI schemas, baselines and goldens remain unchanged.

## Risks / Trade-offs

- Legacy standalone activation becomes a clear rejection; saved data remains readable and Tor stays selectable.
- UI removals may affect screenshots. Verify existing snapshots; do not bless fixtures without explicit authorization.
- Managed stderr separation must not deadlock pipe drains or change NaiveProxy events.
- Local helper tests do not establish Android or external Tor compatibility.

## Migration Plan

No storage migration. Use individual Conventional Commits for each repair and a separate F3 contract record. Run owning JVM/Rust tests, affected formatting and lint, locale lint/export checks, independent pr-reviewer and async review where needed. On the combined tree run staticAnalysis, just ci-gates, architecture health and cargo metadata --locked. Rebase on origin/main, repeat affected checks if inputs change, fast-forward main and push as authorized. Rollback is a normal revert if requested; do not rewrite published history.
