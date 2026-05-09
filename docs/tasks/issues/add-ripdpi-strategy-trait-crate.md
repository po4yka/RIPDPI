---
title: Create ripdpi-strategy-trait crate with DesyncStrategy trait and core types
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Create ripdpi-strategy-trait crate with DesyncStrategy trait and core types #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective / Goal

Create a new `ripdpi-strategy-trait` crate that defines the core `DesyncStrategy` trait, `StrategyContext`, `Dissect` tree, `MarkerName` enum, and `StrategyVerdict`/`StrategyError` types. This is the unifying abstraction that all three backends (Rust-native, config, Lua) implement.

## Context

Currently `plan_tcp()` in `native/rust/crates/ripdpi-desync/src/plan_tcp.rs` is a monolithic function. The new trait creates an extension seam without breaking existing functionality. The `Dissect` tree mirrors zapret2's `struct dissect` (`/Users/po4yka/GitRep/zapret2/nfq2/protocol.h` lines ~1-200) and `t_marker` enum but as idiomatic Rust enums. `StrategyContext` bundles parsed protocol data, per-connection state, runtime capabilities, flow direction, and raw payload — everything a strategy needs to make decisions.

Key types to define:

```rust
pub trait DesyncStrategy: Send + Sync {
    fn id(&self) -> &str;
    fn matches(&self, ctx: &StrategyContext) -> bool;
    fn plan(&self, ctx: &StrategyContext, plan: &mut DesyncPlan) -> Result<(), StrategyError>;
    fn describe(&self) -> StrategyDescriptor;
}

pub enum MarkerName { Host, EndHost, Sld, MidSld, EndSld, SniExt, ExtLen, Data, End }

pub enum L7Protocol { Tls(TlsDissect), Http(HttpDissect), Quic(QuicDissect),
    WireGuard(WireGuardDissect), Dtls(DtlsDissect), Dht(DhtDissect),
    Mtproto(MtprotoDissect), Stun(StunDissect), Unknown }

pub struct Dissect { pub proto: L7Protocol, pub src_port: u16, pub dst_port: u16,
    pub is_ipv6: bool, pub markers: HashMap<MarkerName, usize> }

pub struct StrategyContext<'a> { pub dissect: &'a Dissect, pub conn: &'a ConnectionState,
    pub caps: &'a Capabilities, pub flow_id: FlowId, pub payload: &'a [u8],
    pub direction: FlowDirection }
```

Source references:
- zapret2 type definitions: `/Users/po4yka/GitRep/zapret2/nfq2/protocol.h` — `t_l7proto`, `t_marker`, `struct dissect`
- zapret2 darkmagic types: `/Users/po4yka/GitRep/zapret2/nfq2/darkmagic.h` — verdict constants
- RIPDPI existing desync types: `native/rust/crates/ripdpi-desync/src/types.rs` — `DesyncAction`, `DesyncPlan`, `ActivationContext`
- RIPDPI capabilities: `native/rust/crates/ripdpi-capabilities/` — existing `Capabilities` struct to reuse

## Acceptance criteria

- [ ] `ripdpi-strategy-trait` crate compiles with no dependencies on any other RIPDPI crate (pure trait definitions + std types)
- [ ] `DesyncStrategy` is `Send + Sync` and object-safe (no generic methods)
- [ ] `Dissect` covers all L7 types present in zapret2's `t_l7proto` enum
- [ ] `MarkerName` covers all positions in zapret2's `t_marker` enum
- [ ] `StrategyContext` provides read access to capabilities without requiring root-only fields
- [ ] All public types implement `Debug` and are documented with one-line doc comments
- [ ] Crate is added to `native/rust/Cargo.toml` workspace

## TDD workflow

1. **Write tests first** — before any implementation code, write the test(s) that cover the acceptance criteria above and verify they compile but fail for the logical reason (not a missing symbol).
2. **Confirm red** — run the targeted test command and confirm each new test fails with the expected error, not a compile error or panic unrelated to the feature.
3. **Implement** — write the minimal code to make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-trait/tests/trait_object_safety.rs` — verify `DesyncStrategy` is object-safe: `let _: Box<dyn DesyncStrategy>;` compiles
- `native/rust/crates/ripdpi-strategy-trait/tests/send_sync.rs` — `static_assertions::assert_impl_all!(dyn DesyncStrategy: Send, Sync)` (will fail until trait bounds are correct)
- `native/rust/crates/ripdpi-strategy-trait/tests/dissect_variants.rs` — construct every `L7Protocol` variant and every `MarkerName` variant; verify `Debug` is derived on each
- `native/rust/crates/ripdpi-strategy-trait/tests/marker_roundtrip.rs` — insert markers into `Dissect.markers` HashMap and read them back; fails until `MarkerName` is `Hash + Eq`

## Definition of done

`cargo check -p ripdpi-strategy-trait` passes; trait is imported and used by the registry crate stub.
Tests were written and confirmed red before implementation began; `cargo test -p ripdpi-strategy-trait` is green with no regressions.
