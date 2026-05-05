---
name: Architecture Audit Findings May 2026
description: Layer violations, coupling hotspots, and JNI boundary status from the May 2026 audit run
type: project
---

Key findings from the May 2026 audit:

- JNI boundary is CLEAN: all System.loadLibrary and external fun declarations are in core/engine only
- ripdpi-runtime-adaptive/src/lib.rs line 12 wildcard re-exports ripdpi-runtime-policy internals: `pub use ripdpi_runtime_policy::runtime_policy::*` — this is a surface-area bloat violation
- ripdpi-android-platform-adapter directly depends on ripdpi-runtime-strategy (a runtime-wiring-level crate), bypassing abstraction — new violation
- ripdpi-runtime-learning depends on ripdpi-runtime-adaptive + ripdpi-runtime-policy + ripdpi-runtime-strategy + ripdpi-runtime-dns-cache — acts as a hidden aggregate but is not runtime-services, unclear ownership
- ripdpi-diagnostics-telegram depends on ripdpi-ws-bootstrap and ripdpi-ws-tunnel (Protocol layer) — diagnostics crate pulling protocol transports
- ripdpi-diagnostics-probes is a pure aggregate re-exporter of all diagnostics sub-crates with no logic — unnecessary indirection layer
- High fan-in hotspots (>10): ripdpi-failure-classifier (~12), ripdpi-proxy-config (~13), ripdpi-config (~13), ripdpi-diagnostics-contracts (~12)
- High fan-out hotspots (>8): ripdpi-proxy-runtime (15), ripdpi-monitor-engine (~14), ripdpi-diagnostics-runner (~12), ripdpi-diagnostics-probes (10)
- ripdpi-runtime-adaptive depends on ripdpi-runtime-strategy (concrete strategy crate) — port crate pulling concrete impl
- Kotlin: :core:service testImplementation depends on :core:detection — test-scope layer inversion (L2 test pulling L3 detect)

**Why:** Tracked for cross-audit continuity so new violations can be distinguished from known ones.
**How to apply:** Use this as baseline when a new audit is requested; flag only deltas as "new".
