## Context

`SingBoxSubscriptionParser` supports one bundle version through `RIPDPI_SCHEMA_VERSION = 2`, pinned to `x-contract-version` in the vendored `contract/ripdpi-bundle.schema.json` by `RipdpiBundleContractTest`. `PayloadFailoverPolicy` (its own `schema_version == 2`, an unrelated version namespace) bounds payload-qualified selector changes; `PayloadFailoverImport.kt` imports it from the bundle.

## Goals / Non-Goals

- Goal: v3 parse, v2 rejection, health-aware ordering, golden tests.
- Non-goal: sending reports (R3), generating bundles (deploy D4), probing (deploy D3).
- Non-goal: keeping v2 readable.

## Decisions

- Bump `RIPDPI_SCHEMA_VERSION` to 3 and delete v2-only branches; one parser, one version. Rejected: accepting both, which the owner policy forbids.
- Health evaluation is a pure function of (health, now): expired when now >= observed_at + ttl_seconds. Time comes from an injected clock so golden tests are deterministic.
- Ordering rule: down (unexpired) excluded, then neutral (healthy, unknown, expired), degraded last. Where the spec lists healthy as a candidate, healthy and unknown are both neutral per the owner brief ("unknown/expired neutral"); healthy is not promoted above neutral. Assumption: no promotion is intended.
- Assumption: the endpoint id in the bundle is the opaque `server_ref`; D1 defines its field name and R4 follows the vendored schema.
- Assumption: `PayloadFailoverPolicy` remains the bounded controller and health only filters/reorders its candidates; if the implementation shows the policy cannot take an ordering input, add it to `PayloadFailoverImport.kt` rather than a new class.

## Contracts and ownership

- Serialized: vendored contract files and schema-version constants (single writer after R2); `RipdpiBundleContractTest.kt`.
- Docs: `docs/server-integration.md` bundle section updated for v3.

## Risks / Trade-offs

- All endpoints down could strand users on stale health -> ttl expiry returns them to neutral.
- Clock skew between server and client -> ttl is relative to observed_at; skew up to ttl is tolerated, documented.

## Migration Plan

BREAKING: v2 bundles stop importing. Operators must regenerate bundles with deploy D4 first; existing stored profiles are unaffected because they are not re-parsed. Rollback is reverting the change. Gate: golden tests plus vendored fixtures re-vendored after DAT-1793428393229398 lands.
