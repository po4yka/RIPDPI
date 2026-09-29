## Context

TCP_INFO already exposes snd_mss. Lua currently hard-codes 1460.

## Goals / Non-Goals

- Goal: pass current effective send MSS into socket Lua.
- Non-goal: infer peer receive MSS from TUN SYN packets, modify root nfqws2 or change packet APIs.

## Decisions

Reuse tcp_segment_hint and carry Option<u16> in Dissect. Read snd_mss only, at the central TCP send entry, on every Lua call. Reject non-positive and out-of-range measurements. SocketLuaFlow copies it only for TCP. Lua retains 1460 when unavailable. Do not cache it or substitute advmss or a PMTU estimate.

## Contracts and ownership

Primary owns strategy-trait, strategy-lua, proxy-runtime-desync-adapter, proxy-runtime UDP call site and TUN constructor. Agents only test or review. The primary writer owns Cargo.lock for the existing socket2 test-only dependency edge; versions do not change. No Kotlin, JNI, persisted schema, locale or baseline changes. Existing socket2 can support test-only socket negotiation; no production dependency is needed.

## Risks / Trade-offs

Unsupported platforms and short TCP_INFO structures use the existing fallback. TCP writes can be coalesced by the kernel; tests prove the Lua plan and received bytes, not an exact on-wire packet boundary.

## Migration Plan

No persisted migration. Revert the scoped commit to roll back. Gates: affected full package tests, fmt, Clippy, locked workspace metadata, architecture health/contracts, actual ARM64 Android socket test and independent review. Hosted CI remains a separate required category.
