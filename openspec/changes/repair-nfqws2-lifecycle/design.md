## Context

Task VPN-1794158885036621 repairs five audit findings in the existing root backend. The helper main loop is synchronous and owns its process and firewall state. Kotlin owns session failure reporting. No new runtime or production dependency is needed.

## Goals / Non-Goals

- Goal: Detect missed helper exit and keep cleanup ownership until completion.
- Goal: Limit predecessor and chain cleanup to proven owners and validate upstream Lua file choices.
- Non-goal: Change root opt-in, YAML compatibility, queue bypass, or the non-root packet path.
- Non-goal: Add a strict processing mode or claim device acceptance from host tests.

## Decisions

- Install backend monitoring before checking helper liveness. Deliver an immediate failure through the existing session guard when needed.
- Bound blocking shutdown and probe operations by closing their owned socket at the deadline; retain an unready launcher until its exit is confirmed.
- Stop predecessors through their exact private socket and existing nonce before replacing either file. Wait within a bounded interval. A live or uncertain predecessor blocks startup; no global process-name kill or unverified PID signal is allowed.
- Store stale cleanup debt and the remaining proven cleanup commands in the helper. Keep this ownership proof after a partial deletion removes the legacy OUTPUT owner rule. Retry failed discovery and deletion with bounded work and backoff. New activation and completed shutdown require this debt to be clear.
- Start unsolicited ordinary root recovery only when the canonical nfqws2 asset directory exists and both firewall tools are available. The Android activation caller creates this directory before explicit backend start. Explicit nfqws2 activation must resolve both tools and complete fresh recovery before child spawn. Once cleanup commands are known, a missing tool remains pending rather than dropping that family.
- Reconcile native stop for an ordinary root profile whenever a helper endpoint exists, even without a local backend session. Startup recovery debt must block policy activation until native cleanup completes. The Kotlin writer prepares this caller patch; the integration writer combines it with the Rust recovery change in one F2 commit.
- Encode UID and PID with a delimiter in new firewall chain names. Legacy names require an exact owned OUTPUT owner rule; an incoming pair uses the same proven stem. Leave unproven legacy state unchanged.
- Validate plain and compressed Lua candidates, including dangling symlinks, without changing trusted upstream Lua semantics.
- Keep one commit per finding. Implement Kotlin and Rust independently in isolated worktrees and integrate only after review.

## Contracts and ownership

- Kotlin writer: RootHelperManager, RootHelperIpc in NfqwsBackend.kt, scoped predecessor and deadline helper code, and owning tests in :core:service; F1 and F3 separate commits.
- Rust writer: ripdpi-root-helper nfqws2.rs, nfqws2 children, main.rs startup caller, and relevant native/zapret2/README.md lifecycle text; F4, F5, F2 separate commits.
- Integration writer: portfolio, OpenSpec, combined validation, and integration. No parallel writer edits these files.
- The protocol remains v3. No protobuf, JNI, configuration, storage schema, locale, lockfile, dependency, baseline, generated asset, or upstream source change is planned.

## Risks / Trade-offs

- Unresponsive predecessors block activation rather than risk killing another owner. Preserve retryable ownership and report the cause.
- Unproven legacy chains remain untouched. This is safer than guessing their UID; document this recovery limit.
- Host subprocess and Unix-socket fixtures prove management behavior only. Real Android NFQUEUE, SELinux, revoke, and packet delivery remain a separate acceptance scope.

## Migration Plan

New sessions use delimited chain names. The new reaper recovers only proven owned old names. No user settings migration is required. Rollback requires a clean explicit stop before changing supervisor versions, since old code cannot safely parse new names. Gates: targeted Kotlin tests, cargo test/clippy --locked -p ripdpi-root-helper -p ripdpi-root-helper-protocol, affected service static analysis, just ci-gates, architecture-health check, cargo metadata --locked, and independent read-only review. Root/device activation is not authorized by the commit/push request.
