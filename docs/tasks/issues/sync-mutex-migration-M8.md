---
title: "M8: sync-only std::sync::Mutex/RwLock migration inventory + lint recommendation"
type: task
status: todo
area: native-rust
priority: low
owner: unassigned
parent: rust-audit-followups
blocks: []
blocked_by: []
created: 2026-06-03
updated: 2026-06-03
---

## Summary

Audit finding M8 set the goal state "`std::sync::Mutex`/`RwLock` disallowed on
async paths" and asked for a migration of *sync-only* lock sites (guard never
held across an `.await`) to `parking_lot`, plus a recommendation on whether to
re-add the `clippy.toml` `disallowed-types` rule for `std::sync::Mutex/RwLock`.

After a workspace-wide inventory, **no crate was migrated in this unit** and the
outcome is `documented`. The reasons are recorded below; the short version is
that a *partial* migration is strictly worse than the status quo and the real
async-deadlock risk is already covered by the active `await_holding_lock =
"deny"` lint.

## Motivating skills / rules

- `rust-async-internals` — the actual hazard is a sync guard held across
  `.await` (blocks a runtime worker / can deadlock a sibling `select!` arm).
- `memory-model` — poisoning semantics differ between `std::sync` and
  `parking_lot`; a blind swap is not always behavior-preserving.
- The **dropped-M8 lesson**: re-adding the `disallowed-types` clippy rule before
  every workspace use is migrated re-breaks CI (it lints the whole workspace,
  not just async paths). This already happened once. Do not repeat it.

## Inventory (as of 2026-06-03, worktree `rust-audit-followups`)

`std::sync::Mutex` / `std::sync::RwLock` appears in production `src/` (excluding
`tests.rs`, `tests/`, `loom.rs`, and the loom-conditional `sync.rs` wrappers) of
**~38 crates**. Approximate per-crate site counts (module `use` + struct field +
`::new` occurrences; not a 1:1 call-site count):

| Crate | sites | Crate | sites |
| --- | --- | --- | --- |
| ripdpi-tunnel-android | 11 | ripdpi-io-uring | 2 |
| local-network-fixture | 11 | ripdpi-diagnostics-runner | 2 |
| ripdpi-monitor-engine | 10 | ripdpi-diagnostics-dns | 2 |
| ripdpi-tunnel-core | 8 | ripdpi-android | 2 |
| ripdpi-dns-resolver | 5 | android-support | 2 |
| ripdpi-runtime-services | 4 | ripdpi-warp-android | 1 |
| ripdpi-android-proxy-adapter | 4 | ripdpi-shared-priors | 1 |
| ripdpi-tls-profiles | 3 | ripdpi-runtime-strategy | 1 |
| ripdpi-runtime-platform | 3 | ripdpi-root-helper | 1 |
| ripdpi-runtime-dns-cache | 3 | ripdpi-relay-android | 1 |
| ripdpi-runtime-api | 3 | ripdpi-proxy-config | 1 |
| ripdpi-relay-mux | 3 | ripdpi-diagnostics-tls | 1 |
| ripdpi-relay-core | 3 | ripdpi-diagnostics-pcap | 1 |
| ripdpi-proxy-runtime | 3 | ripdpi-apps-script-core | 1 |
| ripdpi-flow-app-attribution | 3 | ripdpi-android-telemetry-adapter | 1 |
| ripdpi-android-bridge-support | 3 | native-soak-support | 1 |
| ripdpi-warp-core | 2 | golden-test-support | 1 |
| ripdpi-vless | 2 | ripdpi-proxy-runtime-adapter | 2 |
| ripdpi-telemetry | 2 | ripdpi-native-protect | 2 |
| ripdpi-strategy-lua | 2 | | |

Across the workspace there are **~203** `.lock().unwrap()` / `.lock().expect()`
call sites in non-test files.

Reproduce:

```bash
cd native/rust
rg "std::sync::Mutex|std::sync::RwLock" crates/ --type rust \
  --glob '!**/tests.rs' --glob '!**/tests/**' --glob '!**/loom.rs' --glob '!**/sync.rs'
rg "\.lock\(\)\.(unwrap|expect)" crates/ --type rust   # call-site count
```

### Classification of the sites

1. **Loom-conditional `sync.rs` wrappers — DO NOT TOUCH.**
   `android-support/src/sync.rs`, `ripdpi-runtime-api/src/sync.rs`,
   `ripdpi-proxy-runtime/src/sync.rs` re-export `loom::sync::Mutex` under
   `#[cfg(feature = "loom")]` and `std::sync::Mutex` otherwise. `parking_lot`
   has no loom shim, so swapping these would break the loom interleaving model
   checker (`rust-test-tools`). These are intentional.

2. **Poison-tolerant sites (~71 occurrences of `PoisonError::into_inner` /
   `unwrap_or_else(PoisonError::into_inner)`).** These already treat a poisoned
   lock as "use the inner value". They map cleanly onto `parking_lot` (whose
   guards are never poisoned and whose `lock()` returns the guard directly), but
   only after the `.unwrap_or_else(PoisonError::into_inner)` shim is *deleted*,
   not just the `.unwrap()`.

3. **Poison-dependent sites.** A handful (e.g. `ripdpi-vless/src/scoped_handle.rs`,
   `ripdpi-relay-core/src/telemetry.rs`, the `*-android/src/registry.rs` files)
   reference poisoning explicitly or rely on `.expect("… poisoned")` as a
   correctness assertion. Each needs individual review before a swap is
   behavior-preserving.

4. **Test-only sites** (`tests.rs`, `tests/`, `#[cfg(test)]` modules, e.g. the
   `TEST_MUTEX` in `ripdpi-native-protect` and the `verified_names` mock in
   `ripdpi-tls-profiles/src/ech.rs`) are out of scope — they are not on any
   async path and the std lock is fine.

## Why no crate was migrated in this unit

1. **`parking_lot` is not a workspace dependency.** It exists only transitively
   in `Cargo.lock`. Making it direct requires editing
   `native/rust/Cargo.toml` `[workspace.dependencies]` *and* every migrated
   crate's `Cargo.toml`. A partial migration therefore adds a new dependency
   edge to a handful of crates while ~30 others keep `std::sync` — large mixed
   churn for no enforceable invariant.

2. **A partial migration cannot be lint-enforced and is worse than the status
   quo.** The whole point of the goal state is the `disallowed-types` rule, and
   that rule lints the *entire* workspace — it cannot be scoped to "async paths"
   or "migrated crates only". Re-adding it before all ~38 crates are migrated
   re-breaks CI (the dropped-M8 lesson). Until then, a mix of `std::sync` and
   `parking_lot` is just inconsistency with no guard.

3. **The real risk is already covered.** `await_holding_lock = "deny"` is active
   in `native/rust/Cargo.toml` `[workspace.lints.clippy]` (verified line 451).
   It flags both `std::sync` *and* `parking_lot` guards held across `.await`,
   which is the actual deadlock failure mode. The `std`→`parking_lot` swap does
   **not** change this risk profile — `parking_lot` held across `.await` is
   equally dangerous and equally linted.

4. **Behavior-preservation is per-site, not mechanical.** Poison-dependent sites
   (class 3) change observable behavior under panic-while-locked when swapped.
   Each needs review; this is not a find-and-replace.

5. **The whole-workspace clippy gate is the commit gate.** A migration touching
   one crate must still leave `cargo clippy --workspace -- -D warnings` green;
   the safest way to guarantee that for a sprawling change is to do it crate-by-
   crate in its own PR with the lint added only at the very end, not as a side
   effect of an audit-followup unit.

## Recommendation

**Do NOT re-add the `disallowed-types` rule for `std::sync::Mutex`/`RwLock`** as
things stand. `await_holding_lock = "deny"` already prevents the dangerous
async pattern, which is the only thing that actually motivated the goal state.
A blanket `disallowed-types` ban would:

- Lint ~38 crates and ~200 call sites at once (CI red until all migrate).
- Forbid legitimate *sync-only* `std::sync::Mutex` use (e.g. JNI registries,
  global protect-callback `RwLock`, telemetry snapshot mutexes) where the std
  lock is correct and parking_lot buys nothing on a non-contended path.
- Conflict with the three loom-conditional `sync.rs` wrappers, which *must*
  use `std::sync::Mutex` on the non-loom path.

### If the full migration is still wanted later

Do it as a dedicated multi-PR effort, leaf crates first:

1. Add `parking_lot` to `[workspace.dependencies]` (its own PR; documents the
   new direct dependency for `cargo deny`).
2. Migrate crate-by-crate, deleting `.unwrap()` / `.expect("… poisoned")` /
   `.unwrap_or_else(PoisonError::into_inner)` shims at each `lock()`/`read()`/
   `write()` call site (parking_lot returns the guard directly). One Conventional
   Commit per crate, each gated on the full-workspace clippy run.
3. Leave the three loom `sync.rs` wrappers on `std::sync` (or add a parking_lot
   arm guarded by `#[cfg(not(feature = "loom"))]` only if loom coverage is
   preserved).
4. Review every class-3 poison-dependent site individually.
5. Add the `disallowed-types` rule to `clippy.toml` **only in the final PR**,
   once the full-workspace gate is green with it enabled. Pair it with a
   `disallowed-types` allow-list / `// allow:` escape for the loom wrappers if
   needed.

## Acceptance criteria

- [x] Workspace inventory of production `std::sync::Mutex/RwLock` sites recorded.
- [x] Sites classified (loom wrapper / poison-tolerant / poison-dependent / test).
- [x] Recommendation on the `disallowed-types` lint recorded (do not re-add yet).
- [ ] (Deferred) Full crate-by-crate `parking_lot` migration, leaf-first.
- [ ] (Deferred) `disallowed-types` rule added in the final migration PR only.
