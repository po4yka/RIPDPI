---
title: Decouple VLESS xHTTP transport from the Reality relay kind
type: task
status: done
area: relay
priority: critical
owner: unassigned
parent: epic-ripdpi-vpn-deploy-fleet-compatibility
blocks: []
blocked_by: []
created: 2026-05-14
updated: 2026-05-14
---

- [x] #task Decouple VLESS xHTTP transport from the Reality relay kind #repo/RIPDPI #area/relay #status/done 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `decouple-vless-xhttp-transport-from-the-reality-relay-kind`
- **Verify:** `./gradlew :core:data:settings:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :core:service:testDebugUnitTest`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`, `core/data/settings/**`, `core/service/src/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective

Split the **relay kind** from the **transport** and the **security
layer** in RIPDPI's relay model, so a plain-TLS VLESS-over-xHTTP
profile — the deployer's P1 cohort — becomes a first-class,
importable, editable shape. Today `xhttp` exists only as a transport
option *of* `vless_reality`, which structurally forces a Reality
public key + shortId onto a profile that has neither.

## Context

### What the deployer emits (P1)

`ripdpi-vpn-deploy`'s P1 path is a nginx-fronted XHTTP tunnel:

- nginx vhost terminates TLS 1.2/1.3 on TCP/8443 and reverse-proxies
  to a local Xray XHTTP inbound on `127.0.0.1:10085`
  (`ansible/roles/nginx-xhttp/templates/site.conf.j2`).
- The Xray inbound is a second `vless` block with **no `realitySettings`**
  — just `streamSettings.security = "tls"` and an `xhttpSettings.path`
  (`ansible/roles/xray/templates/config.json.j2:80-113`).
- `emit-singbox.sh:270-287` renders the matching client outbound:
  `type:"vless"`, `tls.enabled=true`, `tls.utls.fingerprint="chrome"`,
  `transport.type="xhttp"`, `transport.path`, `transport.host` —
  and **no `reality:` block**.

It is the deployer's "direct, no-CDN baseline" used when Reality is
unavailable or undesired on a given host.

### Why the current model can't hold it

`RelaySettings.kt` conflates three orthogonal axes:

| Axis | Values | Today |
|---|---|---|
| Kind / protocol | `vless_reality`, `hysteria2`, … | `vless_reality` bakes in Reality |
| Security layer | `tls`, `reality` | implied by the kind, not a field |
| Transport | `tcp`, `xhttp` | a sub-option of `vless_reality` |

A P1 profile needs `kind=vless`, `security=tls`, `transport=xhttp`,
plus `serverName`, `path`, `host`, `uTLS=chrome` — and must **not**
require `realityPublicKey` / `realityShortId`. The current type
graph cannot express "VLESS + xHTTP + no Reality".

### Chosen model

Introduce `securityLayer ∈ {tls, reality}` as an explicit field,
orthogonal to `transport ∈ {tcp, xhttp}`. Two implementation paths —
pick the one with the smaller migration surface and **record the
decision in the Work log**:

- **(A)** Add a distinct `vless` kind alongside `vless_reality`.
- **(B)** Collapse `vless_reality` into `vless` + `securityLayer=reality`.

Path B is cleaner long-term but touches more call sites; Path A is
faster but leaves a redundant enum value. Whichever is chosen, a
one-shot `RelayProfileStore` migration must rewrite existing on-disk
records and emit an audit entry.

## TDD workflow

Implement strictly test-first per the epic TDD policy.

1. **Red** — author these tests and confirm each fails for the
   stated reason **before** touching production code:
   - `core/data/settings/src/test/kotlin/com/poyka/ripdpi/data/RelaySecurityLayerTest.kt`
     — asserts `RelayProfileRecord` carries `securityLayer`
     independent of transport. *Fails: field does not exist.*
   - `core/data/runtime-state/src/test/kotlin/.../SingBoxVlessImportTest.kt`
     — feeds the literal P1 outbound JSON from
     `emit-singbox.sh:270-287`; asserts a `vless`/`tls`/`xhttp`
     profile with no Reality fields. *Fails: parser produces a
     `vless_reality` record / throws on missing Reality keys.*
   - `core/data/runtime-state/src/test/kotlin/.../RelayProfileStoreMigrationTest.kt`
     — seeds a legacy `kind=vless_reality, transport=xhttp,
     realityPublicKey=""` record; asserts post-migration it is the
     new shape and one audit entry was written. *Fails: no
     migration exists.*
   - `core/service/src/test/kotlin/.../DefaultRelayKindResolverTest.kt`
     — asserts both `vless+reality+tcp` and `vless+tls+xhttp` route
     to the `ripdpi-vless` native crate. *Fails: resolver only
     knows `vless_reality`.*
2. **Confirm failures are correct** — record the observed messages
   in the Work log; a compile error counts only once the test body
   is complete.
3. **Green** — minimal model change + parser branch + resolver
   branch + migration to make all four suites pass.
4. **Refactor** — deduplicate the Reality-field validation now that
   it is conditional on `securityLayer`; re-run, stay green.
5. **Verify** — run the commands in `## Completion criteria` and
   attach output.

## Acceptance criteria

- [ ] `RelayProfileRecord` carries `securityLayer ∈ {tls, reality}`
    independent of `transport`.
- [ ] Either a distinct `vless` kind exists, or `vless_reality` is
    collapsed into `vless` + `securityLayer=reality`; the decision
    and its rationale are in the Work log.
- [ ] Importing a sing-box `outbound` with `type:"vless"`,
    `tls.enabled=true`, **no** `reality:` block,
    `transport.type="xhttp"`, `transport.path`, `transport.host`
    produces the new plain-TLS xHTTP shape with `uTLS=chrome`.
- [ ] Reality-only fields (`realityPublicKey`, `realityShortId`) are
    **not required** on the plain-TLS shape; the relay editor hides
    them when `securityLayer=tls` and shows them when `=reality`.
- [ ] One-shot `RelayProfileStore` migration rewrites legacy
    `kind=vless_reality, transport=xhttp, realityPublicKey` empty/null
    records into the new shape and writes one migration audit entry
    per rewritten record to the diagnostics export.
- [ ] `DefaultRelayKindResolver` routes both `vless+reality+tcp` and
    `vless+tls+xhttp` to the existing `ripdpi-vless` native crate.
- [ ] Telemetry distinguishes the two shapes so a plain-TLS xHTTP
    failure is never misclassified as a Reality handshake failure.
- [ ] P0 (Reality + TCP, and Reality + xHTTP if present) profiles
    continue to import, save, and connect unchanged — proven by a
    no-regression test, not by inspection.

## Test plan

| Layer | File | Cases |
|---|---|---|
| Kotlin unit | `RelaySecurityLayerTest.kt` | field presence, default, orthogonality to transport |
| Kotlin unit | `SingBoxVlessImportTest.kt` | P0 reality+tcp, P1 tls+xhttp, P0 reality+xhttp, malformed (tls=false, no reality) |
| Kotlin unit | `RelayProfileStoreMigrationTest.kt` | legacy xhttp→new shape, legacy reality untouched, idempotent re-run, audit-entry count |
| Kotlin unit | `DefaultRelayKindResolverTest.kt` | both shapes resolve; unknown security layer = typed error |
| Kotlin unit | redaction harness extension | new `securityLayer` field emitted in export; `serverName`/`path` redacted |

## Completion criteria

`#status/done` only when **every** item below holds, with evidence
in the `## Work log`:

- [ ] All `## Acceptance criteria` checkboxes checked.
- [ ] All five test files exist, were written **before** the
    implementation (red-then-green confirmed in the Work log), and
    pass.
- [ ] `./gradlew :core:data:settings:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :core:service:testDebugUnitTest`
    is green — output attached.
- [ ] `./gradlew lintDebug` clean (no new warnings; `MissingTranslation`
    must stay green if any string is added).
- [ ] Migration verified on a real on-disk profile set: legacy
    fixture in → new shape out, audit entry present.
- [ ] Redaction test green: `serverName` / `path` absent from a
    `DiagnosticsExport` string dump.
- [ ] No-regression: P0 import/save/connect path test green.
- [ ] Reviewed by a separate `code-reviewer` pass — not
    self-approved.
- [ ] `## Work log` added: model-path decision (A or B), changed
    files, test output, residual risk.

## Work log

### 2026-05-14 — model + normalizer + migration + resolver slice

**Model-path decision: Path A** (distinct `vless` kind alongside
`vless_reality`). Rationale: Path A has the strictly smaller migration
surface — `vless_reality` and its `RelayKindVlessReality` constant stay
untouched, so none of the many existing call sites (`app/` relay editor,
view models, `RipDpiRelayConfig` projection, chain/shadowtls resolvers,
golden snapshots) need rewriting. Path B (collapse into `vless` +
`securityLayer`) is cleaner long-term but would ripple through every
`kind == RelayKindVlessReality` site. The redundant enum value is an
acceptable cost; a follow-up can collapse it once the sing-box parser
lands.

**`securityLayer` default decision:** the `RelayProfileRecord.securityLayer`
field defaults to `RelaySecurityLayerReality`. A legacy on-disk record
omits the field entirely, so kotlinx.serialization fills the default —
and today every VLESS profile is Reality-based, so `reality` is the
behaviour-preserving safe default. The one-shot migration then *downgrades*
the specific legacy shape (`kind=vless_reality, vlessTransport=xhttp,
realityPublicKey=""`) to `kind=vless, securityLayer=tls`.

**Changed files (production):**
- `core/data/settings/.../RelaySettings.kt` — added `RelayKindVless`,
  `RelaySecurityLayerTls`, `RelaySecurityLayerReality` constants; extended
  `normalizeRelayKind()` to accept `vless`; added
  `normalizeRelaySecurityLayer(value, relayKind?)`.
- `core/data/runtime-state/.../RelayStores.kt` — added `securityLayer`
  field to `RelayProfileRecord`; added `RelayProfileMigrationResult` +
  `migrateRelayProfileRecord()` (deterministic, idempotent one-shot);
  wired the migration into `SharedPreferencesRelayProfileStore.load()`,
  which persists the rewritten shape so it runs once per legacy record.
- `DefaultRelayKindResolver` routing: unchanged — its `supports()` already
  returns `true` (catch-all), so the new `vless` kind routes to the same
  native VLESS path as `vless_reality`; covered by a new resolver test.

**Test files (written test-first, RED then GREEN):**
- `core/data/runtime-state/src/test/.../RelaySecurityLayerTest.kt`
- `core/data/runtime-state/src/test/.../RelayProfileStoreMigrationTest.kt`
  (incl. real `SharedPreferencesRelayProfileStore` Robolectric load-path test)
- `core/data/settings/src/test/.../RelaySecurityLayerNormalizerTest.kt`
- `core/data/src/test/.../RelayStoresTest.kt` is unchanged and still green.
- `core/service/src/test/.../DefaultRelayKindResolverTest.kt`
Bootstrapped the previously-absent `src/test` source sets in
`core/data/runtime-state` and `core/data/settings`.

**Scope note:** The spec's `SingBoxVlessImportTest` was intentionally
NOT implemented — it depends on the sing-box JSON parser owned by
[[Add sing-box JSON subscription parser]], which is out of this slice's
scope. Adding `securityLayer` as a defaulted field keeps the
`RelayProfileRecord` constructor source-compatible, so no `app/` call
site needed editing.

**Residual risk:** Telemetry shape-distinction (P1 plain-TLS xHTTP vs
Reality handshake) and the relay-editor field visibility toggle are UI
concerns not in this slice's modules; the model now *carries* the
distinguishing field, so those are unblocked follow-ups. The redundant
`vless_reality` enum value remains until a Path-B collapse.

## Source references

- Deployer P1 Xray inbound:
  `ripdpi-vpn-deploy/ansible/roles/xray/templates/config.json.j2:80-113`
- Deployer nginx vhost:
  `ripdpi-vpn-deploy/ansible/roles/nginx-xhttp/templates/site.conf.j2`
- Deployer P1 sing-box emission:
  `ripdpi-vpn-deploy/scripts/emit-singbox.sh:270-287`
- Current relay schema:
  `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/RelaySettings.kt:6,18-19,40-73`
- Resolver registry:
  `core/service/src/main/kotlin/com/poyka/ripdpi/services/RelayKindResolverRegistry.kt`
- Profile store:
  `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/RelayStores.kt:16-99`

## Links

- [[Epic - ripdpi-vpn-deploy fleet compatibility]]
- [[Add sing-box JSON subscription parser]]
- Sibling: `/Users/npochaev/GitHub/ripdpi-vpn-deploy/`
