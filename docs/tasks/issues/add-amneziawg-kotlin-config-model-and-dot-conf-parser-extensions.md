---
title: Add AmneziaWG Kotlin config model and dot-conf parser extensions
type: task
status: done
area: outbound
priority: high
owner: unassigned
parent: epic-amneziawg-outbound-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [x] #task Add AmneziaWG Kotlin config model and dot-conf parser extensions #repo/RIPDPI #area/outbound #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-amneziawg-kotlin-config-model-and-dot-conf-parser-extensions`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Extend the Kotlin config model with an `AmneziaWGBean` (or extend
`WireGuardBean`) holding all AWG obfuscation fields, and extend the
`.conf` INI parser so both vanilla WireGuard and AmneziaWG files parse
into the correct bean type.

## Context

The `.conf` file format is the WireGuard INI format. AmneziaWG adds
keys on the `[Interface]` block: `Jc`, `Jmin`, `Jmax`, `S1`, `S2`,
`S3`, `S4`, `H1`, `H2`, `H3`, `H4`, `I1`, `I2`, `I3`, `I4`, `I5`. A
file with none of these parses as vanilla WireGuard; a file with any
of them parses as AmneziaWG. Router behavior at the subscription
import layer is covered by the companion task.

## Acceptance criteria

- [ ] `AmneziaWGBean` class with all AWG obfuscation fields, inheriting
    the WireGuard field set (private key, address, DNS, MTU, peers).
- [ ] Field validation: `Jc`, `Jmin`, `Jmax`, `S1`..`S4` are
    non-negative integers; `H1`..`H4` are 4-byte unsigned values
    (stored as UInt or hex string); `I1`..`I5` are hex strings.
- [ ] `.conf` parser detects AWG keys in the `[Interface]` block and
    returns an `AmneziaWGBean`; absence of every AWG key returns a
    `WireGuardBean`.
- [ ] Round-trip: `parse(string) → toConfString()` produces
    byte-equivalent output (modulo key ordering and whitespace).
- [ ] Unit tests: vanilla WG config, AWG config with all fields,
    AWG config with partial fields, AWG config with only `Jc`,
    malformed fields (non-numeric, wrong byte count), unknown keys
    (should be ignored with a warning, not a hard error).
- [ ] Kryo equality on `AmneziaWGBean` is byte-stable for dedup.

## Source references

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — this is the definitive reference; port the logic essentially verbatim:

- `tunnel/src/main/java/org/amnezia/awg/config/Interface.java`
- **Lines 49–64:** all 16 obfuscation field declarations (`junkPacketCount`, `junkPacketMinSize`, `junkPacketMaxSize`, `initPacketJunkSize`, `responsePacketJunkSize`, `cookieReplyPacketJunkSize`, `transportPacketJunkSize`, `initPacketMagicHeader`, `responsePacketMagicHeader`, `underloadPacketMagicHeader`, `transportPacketMagicHeader`, `specialJunkI1`..`specialJunkI5`).
- **Lines 101–184:** the `switch` in `parse(lines)` that recognizes every AWG key (`jc`, `jmin`, `jmax`, `s1`..`s4`, `h1`..`h4`, `i1`..`i5`). **Port this switch verbatim** including the lower-casing of keys and the `Integer.parseUnsignedInt` / hex-string parse rules.
- **Lines 504–519:** `toAwgQuickString()` — emits capitalized keys with spaces (`Jc = 4
`).
- **Lines 534–549:** `toAwgUserspaceString()` — emits lowercase keys without spaces (`jc=4
`).
- `tunnel/src/main/java/org/amnezia/awg/config/Config.java` — the top-level `parse(InputStream)` that dispatches lines to `Interface` or `Peer` by section header. Port the section dispatch.
- `tunnel/src/main/java/org/amnezia/awg/config/Peer.java` — standard WG peer; no AWG extensions. Port verbatim or reuse RIPDPI's existing WireGuard peer model.

**License:** amneziawg-android is Apache 2.0 — compatible with whatever license RIPDPI uses. Include SPDX header per file when porting.

**Adapt:** The 16-field set, the parse switch, both serializer variants. **Skip:** Java Optional-wrapped fields (use Kotlin nullables).

## Links

- [[Epic - AmneziaWG outbound support]]
- [[Add WireGuard INI subscription parser]]
