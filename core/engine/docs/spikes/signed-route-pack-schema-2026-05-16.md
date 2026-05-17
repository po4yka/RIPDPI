# Spike: Signed route-pack schema for direct-vs-relay policy

**Date:** 2026-05-16 **Author:** spike session **Task:** `spike-signed-route-pack-schema-for-direct-vs-relay-policy` **Status:** decided

---

## TL;DR

**Decision:** introduce a third, separately-signed pack class — the **route-pack** — alongside the existing host-pack and strategy-pack. Extending the host-pack instead of adding a new class was considered and rejected; the operational cost of a third pack is lower than the cost of overloading host-packs with policy semantics they were never designed to carry.

---

## What goes where today

| Pack class | Carries | Signed? | Refresh cadence | Why it can't carry route intent |
|---|---|---|---|---|
| host-pack | Per-host facts: `direct_only`, ECH SVCB hints, observed CDN, MASQUE eligibility, host reputation tags | Yes (app-trusted key) | Daily incremental, weekly full | Schema is host-keyed; encoding per-app policy or per-destination-class lane intent inflates row width and breaks the additive merge model |
| strategy-pack | DPI bypass strategies (fragment, split, dmap, lantern arms), arm gating, weights | Yes (app-trusted key) | Hourly delta; rollback on telemetry | Strategy is about *how* to send bytes; lane choice is about *which lane to send them on* — orthogonal concerns |

Today RIPDPI's runtime makes lane decisions ad-hoc: a flow lands on direct, on relay, or on owned-stack via a tangle of `ConnectionPolicy*` classes reading per-host bits from the host-pack plus user settings. The whitelist-tightening trend (see `whitelist-oriented-censorship-resilience-2026`) demands a structured, signed, auditable carrier for those decisions.

---

## Manifest shape

```yaml
# route-pack v1 manifest (signed envelope)
schema: ripdpi.routepack.v1
sequence: 4217                    # monotonic; anti-rollback enforced
issued_at: "2026-05-16T09:00:00Z"
channel: stable                   # stable | beta | canary
min_app_version: "0.40.0"
max_app_version: null             # null = open-ended
compatibility:
  routepack_schema_min: 1
  routepack_schema_max: 1
hostpack_baseline: "hp-2026-05-16-0900"   # tie to host-pack snapshot
strategypack_baseline: "sp-2026-05-16-0800"

rules:
  # Per-destination-class lane intent
  - id: ru-mailru-direct
    selector:
      domain_suffixes: ["mail.ru", "ok.ru", "vk.com"]
      geo_hint: RU
    lane: domestic-direct
    fallback: relay-warp
    reason_code: WHITELIST_DOMESTIC
  - id: us-cdn-owned-stack
    selector:
      domain_suffixes: ["cloudflare.net", "fastly.net"]
      asn_in: [13335, 54113]
    lane: owned-stack-only
    fallback: none
    reason_code: ECH_REQUIRED_BROWSER_CLASS
  - id: app-bank-direct-only
    selector:
      app_package_prefix: ["ru.sberbank.", "ru.alfabank."]
    lane: domestic-direct
    fallback: none
    reason_code: WHITELIST_DOMESTIC_APP

defaults:
  lane: smart                     # runtime decides
  fallback: relay
```

The signed envelope (Ed25519 over canonical JSON, BLAKE3-hashed payload) sits in the same trust pool as host-pack signatures; key rotation follows the existing host-pack key-rotation runbook.

---

## JSON vs compiled/binary runtime format

Considered three encodings:

| Encoding | Read latency | Update latency | Audit-ability | Cross-locale stability |
|---|---|---|---|---|
| Canonical JSON | High (parse each match) | Tiny update payload | Excellent — human-readable, greppable | Excellent |
| CBOR | Medium | Smaller payload than JSON | Moderate — binary, but tag-named | Good |
| FlatBuffers / compiled .ripb | Low (mmap + offset lookup) | Larger update payload; codegen step | Poor — requires schema + tool to inspect | Brittle to schema-drift |

**Chosen direction:** ship the signed envelope as canonical JSON; keep a compiled side-car cache that the engine builds locally on first read. The wire format stays auditable; the runtime cost stays low. This mirrors how strategy-packs are shipped today.

Tradeoffs:
- Slight first-read latency on update; mitigated by background warm during the standard update window.
- Two artifacts to keep coherent: covered by an integrity check at envelope-load time (compiled side-car carries the source manifest's BLAKE3 hash; mismatch forces re-compile).

---

## Refresh cadence, anti-rollback, schema-drift

- **Cadence**: hourly delta, daily full, on the same publish lane as strategy-pack updates. Route-pack updates are smaller than host-pack full snapshots (rules-only) so the hourly cadence is cheap.
- **Anti-rollback**: monotonic `sequence`; the engine refuses any envelope whose `sequence` is `<=` the highest-seen value persisted in `core/data/runtime-state`. Manual rollback requires a signed rollback marker (separate Spike — out of scope here).
- **Schema-drift**: - If `routepack_schema_min > app_supported_max`: engine refuses the pack, surfaces a "client update required" diagnostic, and falls back to last-good-known route-pack. - If `routepack_schema_max < app_supported_min`: engine refuses and surfaces "control-plane rollback detected". - Unknown rule fields inside a supported schema range are ignored with a debug log entry; this preserves forward-compatibility for additive rule keys.

---

## Migration example: whitelist-sensitive destination

Given a Russian whitelist-tightening event where `mail.ru`,`ok.ru`, `vk.com` and the major domestic banks must stay on direct exit while foreign destinations move to relay, the migration is:

1. Author rules above in the route-pack.
2. Publish to `beta` channel; canary clients pull, runtime emits `ROUTE_PACK_APPLIED_BETA` diagnostic for ~12 h.
3. Promote to `stable`; canonical sequence advances by one.
4. Domestic-direct exception path is encoded by `lane: domestic-direct` with explicit `fallback`. When direct lane fails inside the configured budget, the engine consults `fallback`; if the fallback is `none`, the engine surfaces an honest `NO_DIRECT_SOLUTION` verdict instead of silently relay-routing.

---

## What must NOT go into the route-pack

- **Operator-private material**: server credentials, account identifiers, MASQUE auth tokens, Xray client UUIDs, Cloudflare WARP registration secrets. These are per-user and live in encrypted user storage, never on a publish channel.
- **Per-user state**: device IDs, install IDs, attempt history, observed network fingerprints.
- **Free-form strategy bytes**: keep DPI arm tuning in the strategy-pack so the two schemas evolve independently.
- **Raw URLs or query strings**: rules key off domain suffix, app package prefix, ASN, geo hint — never request-path content.
- **Identity-correlatable selectors**: no IMEI, no SSID, no GPS, no precise IP. ASN and `geo_hint: RU/CN/...` are the coarsest acceptable network-class selectors.

---

## Why not extend host-packs

- Host-pack rows are host-keyed; route intent crosses host boundaries (per-app, per-class). Encoding it inside host-packs forces fan-out duplication of the same intent across many rows.
- Host-pack update cadence is driven by reputation/CDN observation speed (daily/weekly). Route intent moves on policy clock (minutes/hours during a censorship event). Coupling these breaks both.
- Host-pack consumers today reason about facts ("this host can do ECH"); route consumers reason about policy ("this destination must stay direct"). Mixing the two in one pack erodes the separation that lets the host-pack stay an additive, append-mostly artifact.

The operational cost of a third signed artifact (key rotation slot, publish lane, anti-rollback ledger) is one-time and amortized; the schema-clarity benefit is permanent.

---

## Open questions for follow-up tasks

- Anti-rollback marker format (out of scope, separate spike).
- Side-car compilation format choice (FlatBuffers vs hand-rolled index-and-offset) — deferred to the implementation task.
- Telemetry shape for `ROUTE_PACK_APPLIED_*` — must respect the coarse-keys-only invariant established in `coarse_payload.rs`.

---

## Links

- `Epic - Control-plane hardening`
- `Sign host-pack manifests with app-trusted keys`
- `Add anti-rollback to strategy-pack updates`
- `sing-box-antizapret-control-plane-2026`
- `whitelist-oriented-censorship-resilience-2026`
