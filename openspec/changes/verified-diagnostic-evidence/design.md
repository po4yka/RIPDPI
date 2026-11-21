# Design

Reuse the physical network epoch provider and the proxy supervisor's per-run lease. Treat a proxy poll without current origin as diagnostic evidence only. Do not copy physical identifiers into telemetry. A run started before physical callbacks are ready or invalidated by a callback remains diagnostic-only for learning until the next proxy run. This keeps buffered data from an unverified run out of remembered policy.

Reuse the in-path route lease acquired at scan start. An invalid lease makes the report partial and removes route-dependent advice before persistence. Proxy-mode scans continue to use their own listener boundary.

Keep local SNI observations and drop the unsupported cross-target summary. Give resolver advice and resolver updates different evidence requirements. Reuse native DNS outcome, trust, retry, and bootstrap facts for updates. Home only reports a currently applied network-scoped override. The explicit Save as Setting action remains available.

## Verification

Extend the existing supervisor, learner, scan finalization, DNS workflow, and Rust classification regression tests. Run the combined test targets and the repository source gate.
