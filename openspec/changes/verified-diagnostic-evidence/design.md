# Design

Reuse the physical network epoch provider and the proxy supervisor's per-run lease. Treat a proxy poll without current origin as diagnostic evidence only. Do not copy physical identifiers into telemetry. Native drains all learning signals on each poll, so each batch covers the interval since the previous poll. A batch can teach policy only when the physical epoch captured before the previous poll (or at run start) is still current at consumption. A callback during the interval, or a missing epoch at its start, makes only that batch diagnostic-only. This keeps buffered data from an unverified interval out of remembered policy without disabling learning for the rest of the run.

Reuse the in-path route lease acquired at scan start. An invalid lease makes the report partial and removes route-dependent advice before persistence. Proxy-mode scans continue to use their own listener boundary.

Keep local SNI observations and drop the unsupported cross-target summary. Give resolver advice and resolver updates different evidence requirements. Reuse native DNS outcome, trust, retry, and bootstrap facts for updates. Home only reports a currently applied network-scoped override. The explicit Save as Setting action remains available.

## Verification

Extend the existing supervisor, learner, scan finalization, DNS workflow, and Rust classification regression tests. Run the combined test targets and the repository source gate.
