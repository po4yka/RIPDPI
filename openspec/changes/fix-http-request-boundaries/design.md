## Context

The current helpers operate on arbitrary slices. Proxy reads and root TUN packets can split messages. The audit supplies independent h11 and hyper-h2 reproductions.

## Goals / Non-Goals

- Goal: Fix F1 through F5 with separate tested commits.
- Non-goal: Implement a general HTTP endpoint, TLS termination, or mutations for all keep-alive requests.

## Decisions

- Scope HTTP mutation to the first complete request head. Do not reinterpret later bytes as a request. Preserve custom Lua execution for other protocol steps.
- Assemble the initial HTTP head independently of destination policy, with bounded best-effort passthrough when inspection is optional. Keep required destination-policy failures explicit.
- Root packet mode must fail safe when a complete first head cannot be established; never mutate a later body-looking packet.
- Independently guard Lua unixeol against incomplete reconstruction.
- Select the earliest supported head terminator, and distinguish authority metadata from the raw Host mutation range.
- Keep aggressive peer-tolerance options, and fix only unintended corruption.

## Contracts and ownership

Ownership is recorded in the portfolio task. No Kotlin schema, JNI, protobuf, storage, or dependency-version change is planned. Packet helper APIs, callers, tests, and relevant snapshots change together. The coordinator serializes shared files and integration.

## Risks / Trade-offs

Optional initial inspection can delay a partial head by its existing bounded inspection timeout. Later HTTP requests pass unchanged. All non-HTTP payloads and failed optional inspection pass unchanged. Never resume HTTP mutation after passthrough begins. Existing main has missing boring-sys build scripts; any local build workaround must be isolated and disclosed, not committed as an HTTP fix.

## Migration Plan

No persisted migration. Revert the individual repair commit to roll back that finding. Run owning crate tests and clippy, independent H1/H2 decoders, loopback captures, architecture checks, cargo metadata --locked, and just ci-gates. Device/root/external tests are not required for these host-observable byte and parser changes, and are not claimed as evidence.
