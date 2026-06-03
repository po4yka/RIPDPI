# RIPDPI Native Rust Modernization — Consolidated Audit Report

**Target:** Rust 1.96.0, edition 2024 · **Scope:** 111 crates · **Auditors:** 14 · **Raw findings:** 161

## Implementation outcome (2026-06-03, branch `worktree-rust-modernization`)

All **feasible** modernizations were implemented, one Conventional Commit per crate/unit, each verified with `cargo check --locked --all-targets` (+ `cargo fmt`, workspace clippy, and tests for the semantic changes):

| Step | Status | Commit scope |
|---|---|---|
| Drop unused `once_cell` (dns-resolver) | ✅ done | `chore(dns-resolver)` |
| `once_cell`→`std` (android adapters, android, android-support, bridge-support, relay-android, warp-android) | ✅ done | 5 commits; **8 crates dropped the `once_cell` dep** |
| `once_cell`→`std` (tunnel-android) | ⚠️ partial | 3 mechanical files migrated; **`runtime.rs` retains `once_cell`** and the dep stays — `OnceLock::get_or_try_init` is **unstable** (`once_cell_try`, rust#109737) on the 1.96 pin; documented in-code |
| `io::Error::other` | ✅ done (rescoped) | **1** genuine `ErrorKind::Other` site (socks5-core). The audit's other 20 "sites" use `InvalidData`/`InvalidInput`; collapsing them would change the `ErrorKind`, so they were **deliberately left** |
| let-chain flatten (diagnostics-probes) | ✅ done | `refactor(diagnostics-probes)` |
| RPITIT — socks5-core `Authentication` | ✅ done | `#[async_trait]`→native async fn (`+ Send` preserved); **`async-trait` dep dropped** from crate + workspace |
| RPITIT — relay `RelaySession`/`RelaySessionFactory` | ✅ done | one atomic commit: trait + 12 impl files + test mock; `BoxFuture` alias removed; relay-core (78) + relay-mux (57) tests pass |
| `assert!(matches!)`→`assert_matches!` (240 sites) | ❌ infeasible | `assert_matches!` is **unstable** (`assert_matches`, rust#82775) on the 1.96 pin; requires nightly `#![feature(...)]` → violates `rust-toolchain.toml`. **Not done.** |

**Two audit self-corrections (verified against source, not assumed):** the audit claimed both `OnceLock::get_or_try_init` and `assert_matches!` were stable in 1.96 — **both are still unstable**. The `io::Error::other` category was over-reported (21 → 1 genuine site). Net dependency reductions delivered: **`once_cell` removed from 8 crates; `async-trait` removed as a direct dependency** (now transitive-only via the arti stack).

## Executive summary

The workspace is already in strong modernization health — 14 auditors found **zero blockers** and confirmed the majority of crates are clean. The 161 raw findings collapse into **five actionable categories** plus a set of "already-modern" confirmations that need no change.

The **single highest-leverage item** is finishing the in-progress `once_cell` -> `std::sync::{LazyLock,OnceLock}` migration: the **32 remaining static sites across 9 Android-tier crates are the only remaining `once_cell` consumers**, and converting them lets **8 crates drop the dependency entirely** (a 9th, `ripdpi-dns-resolver`, already has an *unused* `once_cell` entry that can be dropped immediately). A separate, semantically richer win is migrating `async-trait` / `Box::pin` trait impls to **RPITIT native `async fn`**, removing the `async-trait` dependency from `ripdpi-socks5-core` and eliminating per-call boxed-`Future` allocations on the relay data path. The largest category by raw count — **240 `assert!(matches!)` -> `assert_matches!`** sites — is a test-only diagnostics improvement and is **explicitly the lowest priority**.

Verified during synthesis: `rust-toolchain.toml` is pinned at `1.96.0`; `once_cell = "1"` and `async-trait = "0.1"` are present in the workspace `Cargo.toml`; `ripdpi-relay-mux/src/contracts.rs` exists as the relay trait-definition site.

## Category rollup

| Category | Count | Impact | Recommendation (summary) |
|---|---:|:---:|---|
| **lazylock-oncelock** (`once_cell` -> `std::sync`) | 32 | **high** | Finish first. Convert all 32 sites, then drop `once_cell` from 8 crates (+1 already-unused drop). Finishes a 61-site migration AND removes a dependency. |
| **async-trait-to-rpitit** | 13 | **high** | Migrate socks5-core `Authentication` (drops `async-trait`) + relay `RelaySession`/`RelaySessionFactory` `Box::pin` impls. **Safe only because no trait is used as `dyn`.** |
| **assert-matches** (`assert!(matches!)` -> `assert_matches!`) | 240 | low | **Lowest priority.** Test-only diagnostics. Mechanical sweep, zero behavioral/dep impact. |
| **io-error-other** (`io::Error::new(ErrorKind, msg)` -> `io::Error::other`) | 21 | medium | Mechanical, mostly `local-network-fixture` (13) + `ripdpi-desync-runtime` (~10). Review `WriteZero`/`BrokenPipe`/`AddrNotAvailable` conversions for control-flow reliance. |
| **let-chains / other** | 1 | medium | Single nested-`if let` flatten in `doh_survey.rs`. |
| **already-modern (no change)** | 10 | low | No action — positive confirmations of `is_some_and`/`is_none_or`/`const {}`/`checked_*` usage. |

> The `assert-matches` count of **240** is the aggregate across the workspace (auditors itemized ~75 distinct sites and noted the remainder as "N occurrences in file"). The `async-trait-to-rpitit` count of **13** is the number of representative trait/impl *groups*; the underlying edit volume is ~37 `Box::pin` sites (18 in relay-core, 16 in relay-tls-transports, 3 in relay-mux tests, 4 in socks5-core).

## Dependency drops (the high-leverage payoff)

Each drop is gated on completing that crate's `once_cell` -> `std` conversion (or, for socks5-core, the RPITIT migration). Each touches `Cargo.toml` + `Cargo.lock` — keep these serialized per the RIPDPI high-risk-file ledger.

| Crate | Dependency | Blocking sites | Notes |
|---|---|---:|---|
| `ripdpi-dns-resolver` | `once_cell` | 0 | **Already unused** — drop immediately (step 1). |
| `ripdpi-android-diagnostics-adapter` | `once_cell` | 1 | |
| `ripdpi-android-proxy-adapter` | `once_cell` | 3 | |
| `ripdpi-android` | `once_cell` | 2 | |
| `android-support` | `once_cell` | 4 | Most sites (incl. test). |
| `ripdpi-android-bridge-support` | `once_cell` | 2 | Test-only sites. |
| `ripdpi-relay-android` | `once_cell` | 3 | |
| `ripdpi-warp-android` | `once_cell` | 3 | |
| `ripdpi-tunnel-android` | `once_cell` | 3 | Includes `get_or_try_init` review (semantic). |
| `ripdpi-socks5-core` | `async-trait` | 4 | Via RPITIT migration (step 9). |

**Net: 9 `once_cell` drops + 1 `async-trait` drop.** After these, the workspace-level `once_cell`/`async-trait` entries can themselves be evaluated for removal.

## Concrete findings by category

### lazylock-oncelock (32 sites — top 25 shown by impact)

| Crate | File:line | Change |
|---|---|---|
| ripdpi-android | src/lib.rs:10 | `OnceCell` -> `OnceLock` (JVM) |
| ripdpi-android | src/ffi/lua_bridge.rs:11 | `Lazy` -> `LazyLock` (LUA_ENGINE) |
| ripdpi-android | src/ffi/lua_bridge.rs:13 | `Lazy` -> `LazyLock` (LOADED_SCRIPT_PATHS) |
| android-support | src/logging.rs:24 | `OnceCell` -> `OnceLock` (HOOK) |
| android-support | src/logging.rs:34 | `OnceCell` -> `OnceLock` (INIT) |
| android-support | src/logging.rs:114 | `OnceCell` -> `OnceLock` (LOG_SCOPE_LEVELS) |
| android-support | src/events.rs:200 | `OnceCell` -> `OnceLock` (EVENT_RINGS) |
| ripdpi-android-diagnostics-adapter | src/registry.rs:12 | `Lazy` -> `LazyLock` (DIAGNOSTIC_SESSIONS) |
| ripdpi-android-proxy-adapter | src/registry.rs:13 | `Lazy` -> `LazyLock` (SESSIONS) |
| ripdpi-android-proxy-adapter | src/pcap.rs:18 | `Lazy` -> `LazyLock` (PCAP_SESSION) |
| ripdpi-android-proxy-adapter | src/quality_sink.rs:44 | `Lazy` -> `LazyLock` (QUALITY_WINDOW) |
| ripdpi-relay-android | src/registry.rs:5,9 | import + SESSIONS |
| ripdpi-relay-android | src/telemetry.rs:4,13 | import + QUALITY_WINDOW (completes drop) |
| ripdpi-warp-android | src/registry.rs:5,8,9 | import + NEXT_HANDLE + SESSIONS |
| ripdpi-warp-android | src/telemetry.rs:6,15 | import + QUALITY_WINDOW (completes drop) |
| ripdpi-tunnel-android | src/session/registry.rs:6,14 | import + SESSIONS |
| ripdpi-tunnel-android | src/session/pcap.rs:23,29 | import + REGISTRY |
| **ripdpi-tunnel-android** | **src/session/runtime.rs:4,7** | **`OnceCell`->`OnceLock` + `get_or_try_init` — NEEDS REVIEW (semantic)** |
| ripdpi-android-bridge-support | src/lib.rs:234 | `Lazy` -> `LazyLock` (test JNI_TEST_MUTEX) |
| ripdpi-android-bridge-support | src/lib.rs:240 | `OnceCell` -> `OnceLock` (test TEST_JVM) |
| android-support | src/tests.rs:12 | `OnceCell` -> `OnceLock` (test) |
| android-support | src/tests.rs:13 | `Lazy` -> `LazyLock` (test) |
| android-support | src/tests.rs:14 | `Lazy` -> `LazyLock` (test) |

_All sites are deref-safe statics with identical `.get_or_init()` call patterns. The only non-mechanical site is `ripdpi-tunnel-android/src/session/runtime.rs:7` (`get_or_try_init` on a tokio `Arc<Runtime>` — lifecycle-critical per `android-vpn-lifecycle.md`)._

### async-trait-to-rpitit (13 groups, ~37 edit sites)

| Crate | File:line | Change |
|---|---|---|
| **ripdpi-socks5-core** | src/server/auth.rs:2 | trait `Authentication` -> native `async fn` (drops `async-trait`) |
| ripdpi-socks5-core | src/server/auth.rs:46 (+:73,:86) | remove `#[async_trait]` from 3 impls |
| ripdpi-relay-core | src/protocols/masque.rs:24-29 | `Box::pin` -> `async fn open_stream` |
| ripdpi-relay-core | src/protocols/tuic.rs:24-29 | `Box::pin` -> `async fn open_stream` |
| ripdpi-relay-core | src/protocols/hysteria2.rs:25-30 | `BoxFuture` -> `async fn open_stream` |
| ripdpi-relay-core | src/protocols/chain.rs:49-52 | `BoxFuture` -> `async fn create_session` |
| ripdpi-relay-core | src/protocols/xhttp.rs:26-34 | `Box::pin` -> `async fn open_stream`/`open_datagram` |
| ripdpi-relay-core | src/protocols/vless.rs:23-40 | `Box::pin` -> `async fn open_stream` |
| ripdpi-relay-tls-transports | src/shadowtls.rs:25-50 | `BoxFuture` -> `async fn create_session` |
| ripdpi-relay-tls-transports | src/anytls.rs:31-44 | `Box::pin` -> `async fn open_stream` |
| ripdpi-relay-tls-transports | src/ssh.rs:22-46 | `BoxFuture` -> `async fn open_stream` |
| ripdpi-relay-mux | src/contracts.rs (trait def) | drop `BoxFuture` type alias |
| ripdpi-relay-mux | src/tests.rs (×3) | simplify `Box::pin` test sites |

> **DYN-COMPATIBILITY CAVEAT.** RPITIT (stable since 1.75) makes a trait non-`dyn`-compatible. This migration is safe **only** because every auditor verified these traits are never used as `dyn Trait` (socks5 `config.rs:53` explicitly documents non-dyn use; relay verified via grep). If a future call site needs `dyn` dispatch, retain a `BoxFuture` variant of the method. Verify `Send + Sync` bounds survive (the trait method's returned future may need `+ Send`). `contracts.rs` is a high-risk shared-trait edit — serialize it as its own sub-commit.

### io-error-other (21 sites — all shown; no overflow)

| Crate | File:line | ErrorKind being collapsed |
|---|---|---|
| local-network-fixture | src/trojan.rs:206,294,296,305,314 | InvalidData (safe) |
| local-network-fixture | src/trojan.rs:333 | InvalidInput (safe) |
| local-network-fixture | src/dns.rs:39,181,236,337 | InvalidInput (safe) |
| local-network-fixture | src/dns.rs:395 | **BrokenPipe — review** |
| local-network-fixture | src/dns.rs:664 | **AddrNotAvailable — review** |
| local-network-fixture | src/dns.rs:672 | InvalidData (safe) |
| ripdpi-desync-runtime | tcp_plan/fake_packets.rs:18 | InvalidData (safe) |
| ripdpi-desync-runtime | tcp_plan/execution.rs:116 | InvalidData (safe) |
| ripdpi-desync-runtime | tcp_plan/multi_disorder.rs:57,73 | InvalidData (safe) |
| ripdpi-desync-runtime | tcp_plan/execution/fake_family.rs:12 | InvalidData (safe) |
| ripdpi-desync-runtime | tcp_plan/execution/invalid.rs:19 | InvalidData (safe) |
| ripdpi-desync-runtime | tcp_actions.rs:124 | InvalidInput (safe) |
| ripdpi-desync-runtime | transport_io/socket_options.rs:14 | **WriteZero — review** |
| ripdpi-desync-runtime | transport_io/progress.rs:21 | **WriteZero — review** |
| ripdpi-warp-android | src/provisioning.rs:69 | wrap serde error (safe; fold into step 4) |

> **SEMANTIC CAVEAT.** Only collapse where callers do not branch on the specific `ErrorKind`. The `WriteZero`/`BrokenPipe`/`AddrNotAvailable` sites carry potentially meaningful kinds (partial-write retry, channel-closed, bind-failure) — review those before flattening. `InvalidData`/`InvalidInput` generic-validation sites are safe.

### let-chains / other (1 required + optional notes)

| Crate | File:line | Change |
|---|---|---|
| ripdpi-diagnostics-probes | src/doh_survey.rs:271-278 | nested `if let` -> edition-2024 let-chain |
| _optional_ ripdpi-config | src/model/offset.rs:130 | ternary readability note — not required |
| _optional_ native-soak-support | src/lib.rs:342 | `checked_sub` edge-case handling — needs-review, optional |

### assert-matches (240 sites — itemized sample, NOT exhaustive; lowest priority)

Representative high-density locations (full list in raw data; many files noted as "N occurrences"):

| Crate | Example file:line | Count in file |
|---|---|---|
| ripdpi-shared-priors | src/manifest.rs:261-309, lib.rs:132, uploader.rs:81, coarse_payload.rs:161 | 9 |
| ripdpi-runtime-strategy | src/strategy_evolver/tests.rs:1052-1313 | 8 |
| ripdpi-socks5-core | src/client/outbound.rs:492 (+554,562,570,577,727), lib.rs:399 (+404,413) | 9 |
| ripdpi-proxy-runtime | src/runtime/handshake/ws_tunnel.rs:272,288 (file has 6), routing/connect/socks.rs:71,102 | 13 |
| ripdpi-mieru | src/lib.rs:43,51,59, config.rs:158-181 | 10 |
| ripdpi-runtime-dns-cache | tests/*.rs (5 files) | 5 |
| ripdpi-tunnel-suite (loopback/tun-driver/routing/tunnel-core/dns-resolver/protocol-detect) | various | 40+ |
| ripdpi-ssh / trojan / vless / ws-tunnel / xhttp | various | 18 |
| ripdpi-hysteria2 | config.rs:130,137, udp.rs:288 | 3 |
| ripdpi-diagnostics-* | blockpage_fingerprints.rs:28, cdn_ech/tests.rs, network_environment.rs, domain.rs, contract_fixture.rs | 8 |
| ripdpi-config | model/tcp/payload.rs:98,108 | 2 |
| ripdpi-strategy-lua | src/lib.rs:342 | 1 |

> **Overflow note:** ~75 distinct sites are itemized in the raw data; the 240 aggregate includes auditor-noted multi-occurrence files. Treat as one deferrable sweep, one crate (or bucket) per commit.

## Sequenced, commit-sized plan (mechanical-first, semantic-last)

Ordered by ascending risk and decomposed by crate boundary per RIPDPI commit discipline. `Cargo.toml`/`Cargo.lock` dependency-drop edits are folded into each crate's final conversion commit (serialized lane) and never batched across crates.

1. **[mechanical]** Drop already-unused `once_cell` from `ripdpi-dns-resolver` (`Cargo.toml`+`Cargo.lock`). Verify `cargo check --locked`.
2. **[mechanical]** `once_cell`->std: `ripdpi-android-diagnostics-adapter` (1 site) + `ripdpi-android-proxy-adapter` (3 sites). One commit per crate, each drops `once_cell`.
3. **[mechanical]** `once_cell`->std: `ripdpi-android` (2), `android-support` (4+test), `ripdpi-android-bridge-support` (2 test). One commit per crate, each drops `once_cell`.
4. **[mechanical]** `once_cell`->std: `ripdpi-relay-android` (3) + `ripdpi-warp-android` (3, fold in `provisioning.rs:69` io::Error::other). One commit per crate, each drops `once_cell`.
5. **[needs-review]** `once_cell`->std: `ripdpi-tunnel-android` (6). Review `get_or_try_init` on the tokio `Arc<Runtime>` init (lifecycle-critical). pr-reviewer pass required. Drops `once_cell`.
6. **[needs-review]** io::Error::other sweep: `ripdpi-desync-runtime` (~10). Confirm no `WriteZero` retry reliance before flattening those 2.
7. **[needs-review]** io::Error::other sweep: `local-network-fixture` (13). Review `BrokenPipe`/`AddrNotAvailable`; fixture-only blast radius.
8. **[mechanical]** let-chains flatten: `ripdpi-diagnostics-probes/doh_survey.rs`.
9. **[semantic]** RPITIT: `ripdpi-socks5-core` `Authentication` (trait + 3 impls). Drops `async-trait`. pr-reviewer pass (public-ish trait redesign; verify `Send` bounds).
10. **[semantic]** RPITIT: relay traits. Sub-commit order: (a) `ripdpi-relay-mux/contracts.rs` trait def + drop `BoxFuture` alias [high-risk shared file, isolated]; (b) each `ripdpi-relay-core/protocols/*.rs` file; (c) each `ripdpi-relay-tls-transports/*.rs` file; (d) relay-mux test sites. pr-reviewer pass.
11. **[mechanical, DEFERRABLE]** `assert_matches!` sweep — one crate/bucket per commit, ~240 test-only sites. Schedule **after** all value work lands.

## What we deliberately did NOT flag (clippy / lints already enforce it)

- **Correctly-used modern combinators** — `is_some_and`, `is_none_or` (e.g. `ripdpi-config/model/filters.rs:21`, `ripdpi-desync/offset.rs:141`, `types.rs:57`, `ripdpi-strategy-ipv6/window`). Already idiomatic; `clippy::pedantic` would catch regressions.
- **`const { }` inline-const blocks** — already in use (`ripdpi-desync-runtime/platform/registry.rs:9`). No change.
- **`checked_add` / `checked_shl` / `saturating_add` / `checked_sub`** used for intentional fallible/saturating arithmetic (`ripdpi-strategy-ipv6`, `ripdpi-strategy-window`, `ripdpi-strategy-lua`, `ripdpi-anytls/padding.rs`). These are correct and not `manual_div_ceil`/`unnecessary_checked` candidates.
- **`std::sync::Mutex`-across-`.await`, `unwrap()`/`expect()` outside tests, blanket `impl`s, custom `PartialEq` w/o `Hash`** — covered by the `llm-rust-prompts.md` diff-acceptance gate and the workspace `[workspace.lints]`/`clippy.toml` floor (`rust-lints` skill); not part of this modernization audit's remit.
- **Style-only `assert!(matches!)` message preservation** — `assert_matches!` accepts message args, so no diagnostics are lost; intentionally deprioritized.
- **VpnService.protect() / per-packet logging / privacy-identifier sentinels** — out of scope for an edition/dep modernization pass; governed by their dedicated `.claude/rules/` invariants and audited separately.

---

## Appendix — research basis (modern Rust feature inventory)

Audit checklist derived from these stabilizations, scoped to the `1.96.0` pin + edition 2024:

- **Edition 2024 / 1.85** — let-chains in `if`/`while`; async closures (`async ||`, `AsyncFn*`); `unsafe_op_in_unsafe_fn` warn-by-default; RPIT capture rules. ([Rust 1.85.0 + 2024 edition](https://blog.rust-lang.org/2025/02/20/Rust-1.85.0/))
- **1.90** — `u*::{checked,overflowing,saturating,wrapping}_sub_signed`; `CStr`/`CString`/`Cow<CStr>` comparisons.
- **1.92** — `Rc::new_zeroed`, `Arc::new_zeroed_slice`; never-type fallback lints deny-by-default.
- **1.93** — `<[T]>::as_array` (un-erase slice length to a const-generic array).
- **1.96** — `assert_matches!` / `debug_assert_matches!`; public `Range` fields (`Copy` ranges). ([Rust 1.96.0](https://blog.rust-lang.org/2026/05/28/Rust-1.96.0/))
- Aggregated changelogs: [releases.rs](https://releases.rs/).

RPITIT (native `async fn` in traits) is stable since **1.75** and underpins the `async-trait` removal recommendation.
