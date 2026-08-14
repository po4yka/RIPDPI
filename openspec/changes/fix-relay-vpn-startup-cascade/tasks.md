# RLY-1786707070050078: Stop cascading relay and VPN startup failures

## Objective

Deliver one evidence-based startup and runtime failover contract that preserves working relay traffic, fails closed on bounded confirmed failure, and exports exact privacy-safe provenance.

## Ownership

The dedicated `codex/fix-relay-vpn-startup-cascade-20260814` worktree owns the relay health additions in `:core:service`, the simple-flavor failover source set, the required native relay/VLESS telemetry paths, and diagnostics persistence/export. Native telemetry schema/API snapshots, diagnostics Room/export schemas, Kotlin/Rust manifests, locale sets, and affected goldens are serialized lanes. Existing uncommitted home/actuator UI and unrelated worktree changes remain externally owned and must be merged semantically after rebase.

## Execution

- [x] RLY-1786707671531053 Add typed relay probe plans, observations, decisions, scopes, and a pure decision engine with RED/GREEN tests for recent positive evidence, target-only failure, repeated relay-stage failure, permanent rejection, and missing UDP targets #feature !high @item:RLY-1786707070050078
- [x] RLY-1786707671565045 Replace the hard-coded runtime probe with the imported profile target and add lifecycle-scoped single-flight/rate-limit integration so concurrent telemetry cannot create a probe storm or quarantine a working relay #feature !high @item:RLY-1786707070050078
- [x] RLY-1786707671608747 Bound initial candidate attempts and recovery generations, add persistent/session cooldown clearing, restore session-local TCP-only preferences, and prove every failed/losing session is fully stopped before its successor starts #feature !high @item:RLY-1786707070050078
- [x] RLY-1786707671642403 Restore and integrate the VPN data-plane status projection so local runtime readiness, checking, validated, inconclusive, and exhausted states remain observably distinct across service telemetry, home UI, notification, and all locales #feature !high @item:RLY-1786707070050078
- [x] RLY-1786707671668415 Restore privacy-safe runtime-scoped VLESS/REALITY attempt stages with one-shot success/failure/cancellation events, mux-reuse correctness, non-blocking bounded delivery, updated API snapshots, and focused Rust cancellation tests #feature !high @item:RLY-1786707070050078
- [x] RLY-1786707671706117 Decode, migrate, persist, and export relay health decisions and native attempt stages with seeded Room migration, schema/manifests, completeness/redaction tests, and reviewed diagnostics goldens #feature !high @item:RLY-1786707070050078
- [ ] RLY-1786707671742424 Rebase the complete slice onto current `origin/main`, run named Kotlin/Rust/architecture/static-analysis/contract gates, then validate the exact signed simple artifact and `dad-phone` matrix on Pixel 7 with restoration and a 10-minute recovery observation #feature !high @item:RLY-1786707070050078

## Verification

Use the exact local, integrated-tree, artifact, and physical-device gates defined in `verification.md`. A blocked gate leaves its execution item open and prevents archive/closure.
