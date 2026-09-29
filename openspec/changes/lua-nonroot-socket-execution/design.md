## Context

The YAML already supplies Lua functions and jailed script paths. TUN currently captures Lua plans and reconstructs raw packets. All real TCP sends share the proxy desync adapter; UDP has one flow planner and executor.

## Goals / Non-Goals

Run supported payload operations without root; retain matching, on_fail, persistent flow state, and fail-safe packet forwarding. Do not emulate privileged TTL, fake packets, sequence overlap, disorder, or packet-header mutation on stream sockets. Do not update bundled Lua assets or introduce a scripting API hierarchy.

## Decisions

- Extract bundled assets from their core/engine owner before the service builds non-root preferences, including imported and restored YAML.
- Add optional YAML and script-jail fields to the existing runtime-context JSON. Non-root settings populate them; root mode uses the existing TUN Lua executor.
- Compile only Lua steps into per-rule socket registries. Preserve parent matcher and failure policy. Validate Lua socket plans before registry success so NextStrategy can try the next rule.
- Give each TCP/UDP session an owned Lua flow handle. Its final owner closes registry state. TCP retains its logical destination across upstream SOCKS relay writes. Use existing socket writers and execution receipts; no new socket or per-packet JNI call.
- Accept ordered rawsend_dissect replacement only after atomic validation of header equality, empty send options, contiguous sequence ranges, and exact original-payload coverage. Other raw actions stay unsupported for this backend.
- Make TUN replacement DROP conditional on successful injection of every action. Pure DROP has no injection requirement.

## Contracts and ownership

The runtime-context JSON gains optional fields with absent defaults on both sides. Native schema version and protobuf remain unchanged. Kotlin settings own the YAML; the installed app-private Lua directory owns scripts. The proxy owns non-root Lua execution and TUN receives an explicit ownership flag. The task issue records separate writer worktrees and sole Cargo.lock ownership.

## Risks / Trade-offs

Socket writes preserve byte order but cannot promise kernel packet boundaries. Unsupported header edits fall back or drop according to policy. Lua uses the existing bounded engine mutex and watchdog. No payload logging is added. A TCP partial write is terminal for that attempt and cannot trigger a full plain replay.

## Migration Plan

No stored-data migration. Absent optional fields preserve prior behavior. Rollback removes the proxy fields and ownership flag together. Verify native Lua/registry/adapter/proxy/TUN tests, Kotlin codec/service tests, architecture health, locked Cargo metadata, and a local non-root Android smoke when available. Record each actual result and any blocked device gate.
