## Context

Per-task detail lives in the four child changes. This epic only fixes ordering and shared invariants. Contracts are authored in `ripdpi-vpn-deploy` (D1) and vendored here, as `ripdpi-bundle` already is under `core/data/src/test/resources/contract/` with a drift pin in `core/data/src/test/kotlin/com/poyka/ripdpi/data/RipdpiBundleContractTest.kt`.

## Goals / Non-Goals

- Goal: correct, complete, opt-in anonymous analytics of network, blocking, connections, and server health.
- Non-goal: any required backend for app function; the app works fully offline when opt-in is off.
- Non-goal: backward compatibility with bundle v2.

## Decisions

- Order: R1 and R2 first (R1 independent); R4 after R2; R3 after R2 and R4. Rationale: R3 aggregates by the R2 taxonomy and by `server_ref` from R4's bundle.
- Upload only through the active tunnel, so the report cannot reveal the underlying network identity out of band. Rejected: direct upload.
- Assumption: the deploy repository D1 change is not merged yet; local work uses fixtures authored here that D1 must match byte for byte.

## Contracts and ownership

- `client-field-report` v1 and `ripdpi-bundle` v3: owner `ripdpi-vpn-deploy` D1.
- Overlapping RIPDPI work to respect: `svc-1786264762917506-wire-naiveproxy-probe-into-manager-startup` (review), `connection-stage-scale` (review, DGN-1791523992124084), `device-server-migration` (review, TRN-1792673614153980), `correct-dia*` (review), `rst-1786264762917099-triage-undocumented-orphan-diagnostics-crates` (backlog).

## Risks / Trade-offs

- Privacy policy conflict with AGENTS.md "no data leaves the device" -> R3 records the owner opt-in decision and passes `client-legal-safety` and `legal-check`.
- Review-stage overlaps may change shared files -> each child rebases and re-runs affected gates.

## Migration Plan

No user-data migration. Bundle v3 replaces v2 at R4 landing; operators regenerate bundles through deploy D4 first. Rollback is reverting the child change.
