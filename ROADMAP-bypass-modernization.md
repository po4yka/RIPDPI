# ROADMAP: DPI Bypass Modernization

This document now tracks only the unfinished modernization work.
Workstreams 1-4 and 6-9 are complete in repo-owned scope and have been removed
from the detailed body to keep the roadmap focused on the actual remaining
critical path.

## Execution Status (2026-04-18)

- **Workstream 1 (Capability Hygiene):** COMPLETE.
- **Workstream 2 (First-Flight IR):** COMPLETE.
- **Workstream 3 (QUIC Initial Shaping Subsystem):** COMPLETE.
- **Workstream 4 (DNS Oracle And Resolver-Policy Hardening):** COMPLETE.
- **Workstream 5 (TLS Templates + ECH):** PARTIAL.
- **Workstream 6 (Strategy Evaluation And Learning Redesign):** COMPLETE.
- **Workstream 7 (Root/Non-Root Emitter Rationalization):** COMPLETE.
- **Workstream 8 (Android Networking Hardening):** COMPLETE.
- **Workstream 9 (Measurement, Lab Tooling, And Rollout Gates):** COMPLETE.

## Related Documents

- [ROADMAP.md](/Users/po4yka/GitRep/RIPDPI/ROADMAP.md)
- [docs/roadmap-execution-queue.md](/Users/po4yka/GitRep/RIPDPI/docs/roadmap-execution-queue.md)

## Shipped Workstream 5 Scope

The following Phase 11 foundations are already landed:

- browser-family template metadata in `ripdpi-tls-profiles`
- explicit desktop and ECH profile variants
- ALPN-aware monitor binding from template plans
- Android owned-TLS metadata export and Kotlin propagation
- OkHttp-owned TLS profile-id honoring
- bundled strategy-pack catalog entries for the shipped template families
- shared acceptance fixture for the current template set
- coherent Chrome-family production fake profiles instead of generic fake bytes

## Remaining Workstream 5 Scope

### 11.1 Packet-Level Template Parity

Status: complete.

The shipped browser-family templates now have crate-level packet parity
validation in `ripdpi-tls-profiles`. The tests capture live ClientHello records
 emitted by BoringSSL profile application and validate the expected wire shape
for:

- extension ordering families
- GREASE style
- supported-groups layout
- key-share structure
- ALPN family selection

### 11.2 Record-Size Choreography

Status: complete.

The shipped browser-family template catalog now carries real record-boundary
plans instead of metadata-only labels. `ripdpi-tls-profiles` rewrites captured
ClientHello payloads into profile-specific multi-record layouts, and
`ripdpi-desync` carries golden-covered packet rewrites for the desktop Chrome
and ECH-aware Firefox template paths.

### 11.3 HelloRetryRequest-Oriented Tactics

Status: complete.

The shipped fake TLS path now has a controlled HRR-oriented variant:

- `google_chrome_hrr` keeps Chrome-family `supported_groups`
- strips only the `x25519` `key_share` from the fake ClientHello
- leaves the remaining `secp256r1` share intact so compliant servers can issue
  HelloRetryRequest instead of a generic alert
- is exposed only through the opportunistic `tlsrec_fake_hrr` strategy-probe
  candidate

This keeps the tactic bounded to the fake-packet lane instead of inventing a
new multi-flight runtime engine before the acceptance surface exists.

### 11.4 ECH Planning And Bootstrap

Status: complete.

ECH-aware template planning now carries explicit bootstrap policy, resolver
selection, outer-extension handling, and first-flight plan metadata instead of
treating ECH capability as an isolated flag. The ECH monitor path now resolves
HTTPS records through the template-selected encrypted DNS bootstrap endpoint and
exports the resulting first-flight plan in probe details.

### 11.5 Android ECH Policy And Fallback

Finish Android-specific availability detection, policy wiring, bootstrap flow,
and fallback handling for the ECH-aware template families.

### 11.6 Proxy-Mode Suppression Surfacing

Detect and report cases where proxy mode or browser-native stack behavior
suppresses the intended TLS/ECH template path.

### 11.7 Client-Profile Fake Family Completion

Finish replacing the remaining generic fake packet families with coherent
browser-profile families.

### 11.8 Acceptance Coverage

Build and maintain live acceptance coverage across major CDN and server stacks
for every shipped TLS template family.

### 11.9 Strategy-Pack Rollout Discipline

Keep bundled strategy-pack catalog entries and rollout metadata aligned with the
actual shipped template families and their acceptance evidence.

## Exit Condition

This roadmap is complete when every shipped TLS template family has packet-level
validation, Android runtime policy handling, and live acceptance evidence.
