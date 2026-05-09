---
title: Implement Lua API surface for strategy scripts
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [add-ripdpi-strategy-lua-crate, expand-l7-protocol-detection]
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Implement Lua API surface for strategy scripts #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Implement the complete Lua API surface exposed to strategy scripts via `mlua::UserData`. This mirrors zapret2's Lua API exactly where compatible: `desync.dis` (dissect tree), `desync.conn` (per-connection state), `desync.caps` (capability flags), and all action functions (`desync.split()`, `desync.fake()`, `desync.oob()`, etc.).

## Context

zapret2 Lua scripts access packet data and issue actions through globals set up by the C runtime before each packet callback. The equivalent in RIPDPI is a `DesyncCtx` Lua userdata object passed as the first argument to each strategy function (or set as a global `desync`). `DesyncCtx` wraps `StrategyContext` and provides getter methods for all dissect fields plus action methods that append to the `DesyncPlan`.

**Complete Lua API to implement:**

```lua
-- Read-only dissect fields:
desync.dis.proto        -- string: "tls" | "http" | "quic" | "wireguard" | "dtls" | "dht" | "mtproto" | "stun" | "unknown"
desync.dis.hostname     -- string or nil: extracted SNI/Host header value
desync.dis.src_port     -- integer
desync.dis.dst_port     -- integer
desync.dis.is_ipv6      -- boolean

-- Position markers (byte offsets into payload):
desync.dis.pos.host     -- integer or nil
desync.dis.pos.endhost
desync.dis.pos.sld
desync.dis.pos.midsld
desync.dis.pos.endsld
desync.dis.pos.sni_ext
desync.dis.pos.ext_len
desync.dis.pos.data

-- Per-connection state (persistent table, Lua-owned):
desync.conn             -- arbitrary Lua table, persists across packets for same FlowId

-- Capabilities:
desync.caps.raw_socket  -- boolean
desync.caps.tcp_repair  -- boolean
desync.caps.vpn_mode    -- boolean

-- Action functions (append to DesyncPlan, return VERDICT_* int):
desync.pass()           -- VERDICT_PASS = 0
desync.drop()           -- VERDICT_DROP = 2
desync.split(pos, disorder, ttl)          -- maps to DesyncAction::Write + disorder flag
desync.fake(ttl, sni_mode, payload_file)  -- maps to DesyncAction::WriteFake
desync.oob(pos, byte)                     -- maps to DesyncAction::WriteUrgent
desync.fake_rst(ttl)                      -- maps to DesyncAction::SendFakeRst
desync.wsize(n)                           -- maps to DesyncAction::SetWindowClamp
desync.udplen(delta)                      -- maps to UDP length strategy
desync.rawsend(pkt_bytes)                 -- escape hatch: raw bytes via VPN-protected raw socket
desync.set_ttl(n)                         -- maps to DesyncAction::SetTtl
desync.detect(proto_str)                  -- returns boolean: is current packet of named proto?
desync.pos(marker_name)                   -- returns integer offset or nil
```

**VERDICT constants** available as globals: `VERDICT_PASS=0`, `VERDICT_MODIFY=1`, `VERDICT_DROP=2`.

## Acceptance criteria

- [ ] All fields and functions in the API surface table above are implemented
- [ ] `desync.conn` persists across multiple calls for the same `FlowId` (test: write `desync.conn.count = 1` in first call, read it in second call)
- [ ] `desync.split()` with `disorder=true` appends the correct `DesyncAction::Write` + disorder flag sequence to `DesyncPlan`
- [ ] `desync.rawsend(bytes)` calls through `VpnProtect` before sending
- [ ] `desync.detect("tls")` returns `true` when `dissect.proto` is `L7Protocol::Tls(_)`
- [ ] Lua type errors (wrong arg type) return `StrategyError::LuaTypeError(msg)`, not Rust panics
- [ ] Load `zapret-antidpi.lua` (bundled); call `multisplit({pos="sni"}, true, 5)` — verify it produces same `DesyncPlan` as the Rust native split strategy for the same input
- [ ] `cargo test -p ripdpi-strategy-lua --features lua-strategies` covers all API surface functions with at least one call each

## Source references

- zapret2 Lua API globals: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — lines 1-50 (function signatures and documented params)
- zapret2 darkmagic verdicts: `/Users/po4yka/GitRep/zapret2/nfq2/darkmagic.h` — VERDICT_* constants
- zapret2 position markers: `/Users/po4yka/GitRep/zapret2/nfq2/protocol.h` — `t_marker` enum, marker names
- mlua UserData trait docs for implementing getters and methods
- RIPDPI DesyncAction enum: `native/rust/crates/ripdpi-desync/src/types.rs` — target actions for Lua→Rust mapping

## TDD workflow

1. **Write tests first** — before implementing any API surface function, write a Lua script test for that function and verify it fails because the function is not yet registered.
2. **Confirm red** — run `cargo test -p ripdpi-strategy-lua --features lua-strategies` and confirm each Lua-side test call returns a "function not found" error.
3. **Implement** — register each API function one at a time; make its test green before moving to the next.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-lua/tests/api_dis_fields.rs` — run Lua `return desync.dis.proto, desync.dis.src_port, desync.dis.is_ipv6` with a mock TLS context; assert returned values match the mock; fails until `dis` UserData is registered
- `native/rust/crates/ripdpi-strategy-lua/tests/api_pos_markers.rs` — run Lua `return desync.dis.pos.sni_ext` with a context that has a known SniExt marker; assert returned integer matches; fails until marker getters are implemented
- `native/rust/crates/ripdpi-strategy-lua/tests/api_split_action.rs` — call `desync.split("sni", true, 5)` from Lua; assert `DesyncPlan` contains a split+disorder action with TTL=5; fails until `split` action function is bridged
- `native/rust/crates/ripdpi-strategy-lua/tests/api_fake_action.rs` — call `desync.fake(5, "rand", nil)` from Lua; assert plan contains `DesyncAction::WriteFake` with TTL=5; fails until `fake` is bridged
- `native/rust/crates/ripdpi-strategy-lua/tests/api_type_error.rs` — call `desync.split(42, true, 5)` (wrong arg type: integer instead of string); assert `StrategyError::LuaTypeError(_)` returned, no panic; fails until type checking is implemented
- `native/rust/crates/ripdpi-strategy-lua/tests/zapret_antidpi_compat.rs` — load the bundled `zapret-antidpi.lua`; call `multisplit({pos="sni"}, true, 5)` with a mock TLS context; assert plan contains a split action; fails until full API surface is implemented (this is the integration acceptance test)

## Definition of done

Load `zapret-antidpi.lua`, call `fake({ttl=5})`, verify `DesyncPlan` contains `DesyncAction::WriteFake` with TTL=5. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.
