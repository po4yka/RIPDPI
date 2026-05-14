---
title: Add AmneziaWG profile editor screen with obfuscation fields
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-amneziawg-outbound-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add AmneziaWG profile editor screen with obfuscation fields #repo/RIPDPI #area/outbound #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-amneziawg-profile-editor-screen-with-obfuscation-fields`
- **Verify:** `just test-screenshots`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add an `AmneziaWGProfileScreen` Compose editor that reuses the existing
WireGuard profile layout and adds inline fields for every AWG
obfuscation parameter.

## Context

Follow the reference client's UX: obfuscation fields are **inline in
the main editor**, not hidden behind an "Advanced" toggle, because
these values are server-coordinated and the user is expected to paste
them verbatim from their provider. Group the AWG fields into one
labeled section beneath the standard Interface/Peer fields.

## Acceptance criteria

- [ ] New Compose screen `AmneziaWGProfileScreen` in the app module's
    profile-editor navigation.
- [ ] All standard WireGuard fields (private key, address, DNS, MTU,
    peer public key, peer endpoint, allowed IPs, preshared key,
    persistent keepalive) surface and behave identically to the
    existing WireGuard editor.
- [ ] New "Obfuscation" section with one `OutlinedTextField` per AWG
    parameter: Jc, Jmin, Jmax, S1, S2, S3, S4, H1, H2, H3, H4, I1,
    I2, I3, I4, I5.
- [ ] Per-field validation mirrors the parser: integer ranges for
    Jc/Jmin/Jmax/S1–S4; 4-byte unsigned for H1–H4; hex strings for
    I1–I5.
- [ ] Paste-from-clipboard button on the section header: if the
    clipboard contains a full AWG `.conf`, parse it and populate
    all fields.
- [ ] Private key + preshared key fields use the existing biometric-
    gated reveal pattern from other profile editors.
- [ ] Screen layout works in RTL locales; Roborazzi screenshot test
    covers en / ar / fa / zh-CN.
- [ ] No secret material renders in logs during editing; standard
    redaction applies to all diagnostic surfaces.

## Source references

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — port UX + field ordering:

- `ui/src/main/java/org/amnezia/awg/viewmodel/InterfaceProxy.kt:57-159` — all 16 `@Bindable` obfuscation properties. Port the property set and observable-binding approach (RIPDPI's Compose equivalent is `mutableStateOf` + `ViewModel`).
- `ui/src/main/java/org/amnezia/awg/fragment/TunnelEditorFragment.kt` — editor host. Biometric-gated private-key reveal pattern (lines around `BiometricAuthenticator` invocation). **Adopt this pattern** for RIPDPI's private-key reveal.
- `ui/src/main/res/layout/tunnel_editor_fragment.xml:244-594` — the XML layout for all AWG obfuscation fields. This is the definitive ordering and field-grouping reference. Translate to Compose but preserve the section order: standard WG fields → `DNS` → `MTU` → obfuscation fields inline.
- `ui/src/main/res/values/strings.xml` — AWG field labels (`junk_packet_count`, `init_packet_magic_header`, etc.) in English. Use as baseline for RIPDPI string resource keys.

**License:** Apache 2.0 — compatible.

**Adapt:** Field set, field order (inline, not hidden), biometric gate, `inputType="number"` vs `textNoSuggestions` per field type. **Skip:** XML layout and Data Binding (Compose).

## Links

- [[Epic - AmneziaWG outbound support]]
- [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]
