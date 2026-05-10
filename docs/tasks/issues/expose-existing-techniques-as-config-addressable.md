---
title: Expose existing desync techniques as config-addressable registry entries
type: task
status: review
area: rust-native
priority: high
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [add-ripdpi-strategy-config-yaml-loader, refactor-plan-tcp-to-desynpstrategy-trait]
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Expose existing desync techniques as config-addressable registry entries #repo/RIPDPI #area/rust-native #status/review 🔼

## Objective

Register all existing RIPDPI desync techniques (split, disorder, fake, OOB, FakeRst, SeqOverlap, IpFrag, MultiDisorder) as individually addressable strategies in `StrategyRegistry` so the config loader can compose them by `type` name. Each technique becomes a standalone `DesyncStrategy` wrapper around the existing `plan_tcp()` step logic.

## Context

Currently `plan_tcp()` receives a `Vec<TcpChainStep>` and processes the entire chain. After this task, each `TcpChainStep` variant (Split, Disorder, Fake, OOB, FakeRst, SeqOverlap, IpFrag2, MultiDisorder) is also individually accessible as a named strategy so the config YAML can compose them freely. The existing monolithic chain path is preserved as the default — this task adds the decomposed individual wrappers on top.

Techniques to decompose (reference their implementations):

- `split` → `native/rust/crates/ripdpi-desync/src/plan_tcp.rs` split step handler
- `disorder` → same file, disorder step
- `fake` → `native/rust/crates/ripdpi-desync/src/fake.rs` — `build_fake_packet()`
- `tls_rec` / `tls_rand_rec` → `native/rust/crates/ripdpi-desync/src/tls_prelude.rs`
- `oob` → plan_tcp.rs OOB step
- `fake_rst` → plan_tcp.rs FakeRst step
- `seq_overlap` → plan_tcp.rs SeqOverlap step
- `ip_frag` → `native/rust/crates/ripdpi-privileged-ops/src/linux/fragmentation.rs`
- `multi_disorder` → fragmentation.rs multi_disorder
- Zapret2 reference for technique names: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` lines 1-100 (function names are the canonical IDs)

## Acceptance criteria

- [ ] Each technique is registered with an `id` matching the YAML `type` field (e.g., `"split"`, `"fake"`, `"oob"`)
- [ ] `StrategyRegistry::list()` returns all registered technique descriptors with their required capability tier
- [ ] Config YAML `type: split` correctly resolves to the split technique without any code changes to the YAML loader
- [ ] `cargo test -p ripdpi-strategy-registry` covers: all technique IDs resolve, unknown type returns `Err`
- [ ] `describe()` on each technique returns the correct `required_capabilities` tier (Tier 0/1/2/3)
- [ ] Techniques that require `TCP_REPAIR` (disorder, multi_disorder, seq_overlap) report Tier 2 and are skipped gracefully when the capability is absent

## Source references

- `native/rust/crates/ripdpi-desync/src/plan_tcp.rs` — split, disorder, oob, fake_rst, seq_overlap step handlers
- `native/rust/crates/ripdpi-desync/src/fake.rs` — `build_fake_packet()`
- `native/rust/crates/ripdpi-desync/src/tls_prelude.rs` — tls_rec, tls_rand_rec
- `native/rust/crates/ripdpi-privileged-ops/src/linux/fragmentation.rs` — ip_frag, multi_disorder
- `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` lines 1-100 — canonical technique names from zapret2

## TDD workflow

1. **Write tests first** — before any implementation code, write the test(s) that cover the acceptance criteria above and verify they compile but fail for the logical reason (not a missing symbol).
2. **Confirm red** — run `cargo test -p ripdpi-strategy-registry` and confirm each new test fails logically.
3. **Implement** — register each technique and make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-registry/tests/technique_ids.rs` — assert `registry.get("split")`, `registry.get("fake")`, `registry.get("oob")`, `registry.get("fake_rst")`, `registry.get("seq_overlap")`, `registry.get("ip_frag")`, `registry.get("multi_disorder")`, `registry.get("tls_rec")`, `registry.get("tls_rand_rec")` all return `Some`; each fails until the corresponding technique is registered
- `native/rust/crates/ripdpi-strategy-registry/tests/unknown_type_error.rs` — assert `registry.get("nonexistent_type")` returns `None`; this is the baseline (should pass immediately, guards against silently accepting unknown IDs)
- `native/rust/crates/ripdpi-strategy-registry/tests/capability_tiers.rs` — for each technique, assert `describe().required_capabilities` returns the correct tier: `disorder` and `multi_disorder` require `TCP_REPAIR` (Tier 2); `split` and `fake` require nothing (Tier 0)
- `native/rust/crates/ripdpi-strategy-registry/tests/yaml_type_resolution.rs` — load a config YAML with `type: fake`, assert it resolves to the `fake` technique without code changes to the loader

## Definition of done

Running RIPDPI on a device and loading a YAML with `type: fake` produces a fake packet injection without modifying any Rust strategy logic. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Work log

- Added the built-in technique catalog to `ripdpi-strategy-registry` with stable IDs, descriptors, capability tiers, and single-technique registration errors for unknown IDs.
- Added `StepType::registry_id()` so parsed YAML step types resolve to registry technique IDs without changing the YAML schema.
- Added registry tests for all built-in IDs, unknown types, capability tiers, YAML type resolution, and graceful tier skipping.
- Verification: `CARGO_TARGET_DIR=target/codex-builtins cargo test -p ripdpi-strategy-registry -p ripdpi-strategy-config --locked`; `CARGO_TARGET_DIR=target/codex-builtins cargo clippy -p ripdpi-strategy-registry -p ripdpi-strategy-config --all-targets --locked -- -D warnings`.
- Added attached-emulator JNI validation that `validateStrategyConfigText()` accepts YAML containing `type: fake`, `type: udplen`, and `type: ipv6Ext`, and rejects malformed YAML.
- Verification: clean detached worktree `ANDROID_HOME=$HOME/Library/Android/sdk ANDROID_SDK_ROOT=$HOME/Library/Android/sdk ./gradlew :app:ktlintCheck :app:assembleDebugAndroidTest -Pripdpi.skipNativeBuild=true`; then direct install of `app-debug-androidTest.apk` and `$HOME/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am instrument -w -r -e class com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner` — passed, `OK (4 tests)`.
- Wired VPN-mode TUN egress YAML `type: fake` to inject a low-TTL TCP copy through the existing raw packet injector while allowing the original packet to continue through normal TUN forwarding.
- Verification: `cargo fmt --manifest-path native/rust/Cargo.toml --all --check`; `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-tunnel-egress-fake cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-core tun_egress_interceptor --locked` — passed, including `fake_rule_injects_low_ttl_tcp_copy_and_forwards_original`.
- Remaining review evidence: attached-device runtime validation proving a loaded YAML `type: fake` reaches Android raw packet injection with VPN socket protection active.
