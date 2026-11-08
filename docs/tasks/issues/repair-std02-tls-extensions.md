---
id: TRN-1794158969509887
title: Repair TLS extension contracts from STD-02 audit
kind: bug
status: review
area: transport
priority: high
owner: TLS transport maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: std02-tls-extensions
created: 2026-11-08
updated: 2026-11-08
status_detail: All local repair tests and source gates pass; exact-source remote CI remains pending.
---

## Goal

Repair the three confirmed STD-02 TLS extension defects. Keep certificate verification, direct REALITY camouflage, socket protection, and legacy DoT without ALPN intact.

## Acceptance criteria

- F1: DoT offers dot after both builder paths, accepts dot or legacy no-ALPN, and refuses a different selected protocol before DNS bytes.
- F2: MASQUE H2 TCP/UDP and xHTTP use h2 and require h2 before application bytes. Direct REALITY behavior stays intact.
- F3: TLS names are canonical ASCII DNS names or IP identities; invalid input cannot become malformed SNI. URL IPv6 brackets remain in HTTP authority only.
- H1: Validate the diagnostic override hypothesis and fix a confirmed identity substitution separately.
- Profile validation rejects empty or overlong ALPN identifiers and encoded lists that exceed the TLS extension limit.
- Existing and new targeted regressions, independent review, affected package checks and combined offline CI gates pass or report exact blockers.
- Each confirmed problem has a separate fix commit integrated into main and pushed to origin, as authorized by the user.

## Parallel ownership

- DoT worker, repair/std02-dot: dns-resolver/resolver/dot.rs and owning ALPN tests only.
- H2 worker, repair/std02-h2: masque/h2.rs, xhttp/connect.rs, vless/reality.rs and their ALPN tests/exports. No name normalization.
- SNI worker, repair/std02-sni: tls-profiles identity helper; remaining TLS name and URL consumers; separate diagnostic override reproduction/fix. Excludes the ALPN workers' production files.
- Primary, repair/std02-tls-extensions: planning and evidence; serialized name call sites in dot.rs, masque/h2.rs, xhttp/connect.rs and vless/reality.rs after ALPN lane completion; integration, reviews, combined gates, commits and push.
- Primary alone owns Cargo.lock, root manifests, API snapshots, task records, generated board and OpenSpec artifacts. Worker-owned test manifests may use existing workspace test dependencies after coordination; no new production dependency.
- No baseline, golden, locale, schema or dependency-version changes. Each writer uses a dedicated worktree and preserves others' work. Workers wait for validated planning before source edits and independent review before commit; only primary integrates main or pushes.
