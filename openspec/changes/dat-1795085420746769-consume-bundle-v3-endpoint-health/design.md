## Context

`SingBoxSubscriptionParser` supports one bundle version through `RIPDPI_SCHEMA_VERSION = 2`, pinned to `x-contract-version` in the vendored `contract/ripdpi-bundle.schema.json` by `RipdpiBundleContractTest`. `PayloadFailoverPolicy` (its own `schema_version == 2`, an unrelated version namespace) bounds payload-qualified selector changes; `PayloadFailoverImport.kt` imports it from the bundle.

## Goals / Non-Goals

- Goal: v3 parse, v2 rejection, health-aware ordering, golden tests.
- Non-goal: sending reports (R3), generating bundles (deploy D4), probing (deploy D3).
- Non-goal: keeping v2 readable.

## Decisions

- Bump `RIPDPI_SCHEMA_VERSION` to 3 and delete v2-only branches; one parser, one version. Rejected: accepting both, which the owner policy forbids.
- Contract shape (deploy D1, deployer commit `e917cf5b`): health lives in the top-level map `ripdpi.endpoints`, keyed by `server_ref` (an outbound or AWG tag of the same bundle); each entry is `{health: {status healthy|degraded|down|unknown, observed_at, ttl_seconds 60..86400}}`. `ripdpi.field_report` is `{endpoint, cohort_token, max_reports_per_day 1..24}`. The nested `payload_failover` object keeps its own `schema_version` 2.
- Code location: the health and field-report types, their parsing, and the version/field-report refusal live in `subscription/RipdpiEndpointHealth.kt`, which keeps `SingBoxSubscriptionParser.kt` inside the 700-line Kotlin source budget.
- Parse result: `SingBoxParseResult.Success.endpointHealth: Map<String, EndpointHealth>` (key = `server_ref`) and `fieldReport: RipdpiFieldReport?`. `SingBoxParseResult.Error.code` is the typed `SingBoxBundleErrorCode` (`UNSUPPORTED_SCHEMA_VERSION`, `INVALID_FIELD_REPORT`); the field-report message names the failing field.
- Lenient health, strict field report: a health entry with an invalid key, status, instant, or ttl is dropped and that endpoint stays neutral, matching the lenient-client contract of `contract/negative/README.md`. An invalid `field_report` rejects the bundle, because it controls where data is sent. The client stays lenient on unknown `ripdpi` fields; the strict top level is the server-side schema rule.
- Health evaluation is a pure function of (health, now): expired when now >= observed_at + ttl_seconds (`EndpointHealth.statusAt`). Time comes from an injected clock (`SelectorUrltestGroupImport.import(..., nowEpochMillis)`, default the system clock) so golden tests are deterministic.
- Evaluation point: health is applied when the selector group is imported (`orderByEndpointHealth` in `PayloadFailoverImport.kt` reorders and filters the selector members before the group is stored). `PayloadFailoverPolicy` itself is unchanged; it bounds the selector changes among the members it receives. Every subscription refresh re-evaluates health. Trade-off: an endpoint excluded at import stays out until the next refresh even if its ttl expires earlier.
- Ordering rule: down (unexpired) excluded, then neutral (healthy, unknown, expired), degraded last. Where the spec lists healthy as a candidate, healthy and unknown are both neutral per the owner brief ("unknown/expired neutral"); healthy is not promoted above neutral. Assumption: no promotion is intended.
- `PayloadFailoverPolicy` remains the bounded controller and health only filters and reorders its candidates. The policy has no ordering input, so the ordering function lives in `PayloadFailoverImport.kt` rather than a new class.
- All candidates down: `orderByEndpointHealth` returns `EndpointHealthOrder.NoUsableEndpoint` and the import fails with "every selector candidate is down".
- Fixture provenance: `deployment-generated/*.json` and `fleet-fixtures/*/bundle.json` are inputs whose `ripdpi.schema_version` is bumped to 3 in place; their expected outputs do not change. `deployment-generated/metadata.json` still records the pre-v3 payload hash until the deploy D4 generator re-emits them.

## Contracts and ownership

- Serialized: vendored contract files and schema-version constants (single writer after R2); `RipdpiBundleContractTest.kt`.
- Docs: `docs/server-integration.md` bundle section updated for v3.

## Risks / Trade-offs

- All endpoints down could strand users on stale health -> ttl expiry returns them to neutral.
- Clock skew between server and client -> ttl is relative to observed_at; skew up to ttl is tolerated, documented.

## Migration Plan

BREAKING: v2 bundles stop importing. Operators must regenerate bundles with deploy D4 first; existing stored profiles are unaffected because they are not re-parsed. Rollback is reverting the change. Gate: golden tests plus vendored fixtures re-vendored after DAT-1793428393229398 lands.
