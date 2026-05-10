---
title: Add ripdpi-strategy-config YAML loader crate
type: task
status: review
area: rust-native
priority: high
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [add-ripdpi-strategy-registry-crate]
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Add ripdpi-strategy-config YAML loader crate #repo/RIPDPI #area/rust-native #status/review 🔼

## Objective

Create `ripdpi-strategy-config` crate that reads a YAML (primary) or TOML config file and instantiates a `Vec<Box<dyn DesyncStrategy>>` for registration into `StrategyRegistry`. This is the primary user-facing interface for strategy customization without writing code.

## Context

zapret2 users configure strategies via shell variables like `NFQWS2_OPT="--dpi-desync=fake,split --dpi-desync-split-pos=sni"` (see `/Users/po4yka/GitRep/zapret2/config.default`). RIPDPI replaces this with a structured YAML schema that is validated, versioned, and composable. The loader compiles YAML into a `CompositeStrategy` (chain of native Rust strategies with parameters) registered as a single `DesyncStrategy` in the registry. The protobuf `DesyncGroup` (used in profile data) is extended with a `strategy_chain` field that serializes the loaded config for persistence.

YAML schema design:

```yaml
version: 1
strategies:
  - id: tls_split_disorder
    match:
      proto: [tls]
      port: [443, 8443]
      hosts: "@hostlist.txt"   # file reference or inline list
    steps:
      - type: split
        pos: sni               # host | sni | sld | midsld | endsld
        disorder: true
        ttl: 5
      - type: fake
        ttl: 5
        sni_mode: rand         # rand | rndsni | dupsid | padencap | fixed
    on_fail: next_strategy     # next_strategy | fallback_plain | drop
  - id: quic_udplen
    match:
      proto: [quic]
    steps:
      - type: udplen
        delta: 4
```

## Acceptance criteria

- [ ] `ripdpi-strategy-config` parses the YAML schema above without panicking on unknown fields (strict: return parse error)
- [ ] All `type` values map to existing `DesyncAction` variants in RIPDPI (split, disorder, fake, oob, fakeRst, seqOverlap, ipFrag, multiDisorder, udplen, httpDomcase, httpHostcase, wsize, wssize)
- [ ] `match.hosts` accepts both inline list and `@path` file reference (read at load time)
- [ ] `match.proto` accepts: tls, http, quic, wireguard, dtls, dht, mtproto, stun, any
- [ ] `on_fail` defaults to `next_strategy` when omitted
- [ ] Config reload at runtime without restart (watch file modification time; re-register on change)
- [ ] `cargo test -p ripdpi-strategy-config` covers: valid YAML roundtrip, unknown field error, bad proto enum, file-reference resolution
- [ ] Protobuf `DesyncGroup` gains a `strategy_chain_yaml: string` field (stored verbatim, re-parsed at load)

## Source references

- zapret2 config format: `/Users/po4yka/GitRep/zapret2/config.default` — existing strategy chain syntax
- zapret2 lua strategies: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — all available technique names and parameter vocabulary
- RIPDPI existing DesyncGroup: `native/rust/crates/ripdpi-config/src/` — protobuf definitions to extend
- RIPDPI desync types: `native/rust/crates/ripdpi-desync/src/types.rs` — `DesyncAction`, `TcpChainStep` for parameter mapping

## TDD workflow

1. **Write tests first** — before any implementation code, write the test(s) that cover the acceptance criteria above and verify they compile but fail for the logical reason (not a missing symbol).
2. **Confirm red** — run `cargo test -p ripdpi-strategy-config` and confirm each new test fails logically, not with a missing-symbol compile error.
3. **Implement** — write the minimal parsing code to make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-config/tests/valid_yaml_roundtrip.rs` — parse the canonical two-strategy YAML from the acceptance criteria; assert both strategy IDs and step counts are correct; fails until parser exists
- `native/rust/crates/ripdpi-strategy-config/tests/unknown_field_error.rs` — parse YAML with an unrecognised top-level field; assert `Err` is returned (strict mode); fails until validation is implemented
- `native/rust/crates/ripdpi-strategy-config/tests/bad_proto_enum.rs` — parse YAML with `proto: [badproto]`; assert `Err` with descriptive message; fails until proto enum validation exists
- `native/rust/crates/ripdpi-strategy-config/tests/file_reference_resolution.rs` — parse YAML with `hosts: "@/tmp/testlist.txt"`; write the file, assert hostlist entries are loaded; fails until `@path` expansion is implemented
- `native/rust/crates/ripdpi-strategy-config/tests/on_fail_default.rs` — parse YAML with `on_fail` field omitted; assert default is `NextStrategy`; fails until default handling exists
- `native/rust/crates/ripdpi-strategy-config/tests/config_reload.rs` — write a YAML file, load it, modify the file, call reload; assert updated strategy count; fails until reload is implemented

## Definition of done

`cargo test -p ripdpi-strategy-config` green; example YAML from acceptance criteria loads and produces a registered strategy chain. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Work log

- Added `ripdpi-strategy-config` parser/reloader crate with strict YAML parsing, protocol/step enums, inline and `@file` host lists, default `on_fail`, and mtime-based reload.
- Added `strategy_chain_yaml = 214` to `AppSettings` proto for verbatim YAML persistence.
- Verification: `CARGO_TARGET_DIR=target/codex-strategy-config cargo test -p ripdpi-strategy-config --locked`; `CARGO_TARGET_DIR=target/codex-strategy-config cargo clippy -p ripdpi-strategy-config --all-targets --locked -- -D warnings`.
- Remaining review evidence: instantiate parsed YAML into concrete registry strategies.
