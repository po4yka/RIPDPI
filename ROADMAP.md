# RIPDPI Roadmap

The audit roadmap tracked in this repository is complete. The items below are no longer planned work; they are the current architecture and verification baseline.

## Current Status

- All 10 audit phases have been implemented on `main`.
- There are no remaining open items from the original audit backlog.
- New work should be tracked as a separate roadmap or issue list instead of reopening this completed checklist.

### Post-Audit Workstream Progress (2026-04-17)

- Architecture refactor Workstream 0 (Guardrails): COMPLETE.
- Architecture refactor Workstream 1 (Config Contract): PARTIAL (slices 1.1-1.5 of 8 done).
- Architecture refactor Workstream 3 (Native Runtime Decomposition): COMPLETE
  (TCP lowering layer, typed capability snapshot, UDP flow split, and platform
  capability / IPv4-id submodules now sit on top of the earlier executor
  extraction work).
- Bypass modernization Workstream 1 (Capability Hygiene): COMPLETE.
- Bypass modernization Workstream 2 (First-Flight IR): PARTIAL (IR types,
  parser normalization, TCP semantic offsets, QUIC planner migration slices,
  terminal fake-step lowering migration, and rewrite goldens landed; broader
  planner migration and QUIC packetizer work remain).

See [`docs/roadmap-execution-queue.md`](docs/roadmap-execution-queue.md) for the
unified slice list, dependency graph, and the next priority entry points
(Phase 3 remainder first; Phases 6 and 7 in parallel; then Phase 1b and the
remaining Phase 4 planner migration before Phase 5).

## Completed Workstreams

1. Baseline and guardrails
   - repeatable runtime, load, JNI-wrapper, and native-size baselines
   - CI artifact collection for baseline snapshots
   - supporting documentation in [docs/testing.md](docs/testing.md)
2. Input-surface hardening
   - `cargo-fuzz` targets across parser-heavy native crates
   - local and CI fuzz smoke workflow
   - parser fixes and regression coverage for malformed inputs
3. Unsafe audit and containment
   - wrapper extraction for repeated fd, socket-option, ancillary-fd, and lifecycle patterns
   - targeted Miri smoke coverage for pure helper logic
   - current checklist in [docs/native/unsafe-audit.md](docs/native/unsafe-audit.md)
4. Runtime contention reduction
   - runtime lock contention benchmarks
   - read-optimized access on hot runtime paths
   - reduced lock scope on retry and strategy-evolver paths
5. Diagnostics boundary cleanup
   - diagnostics contracts live behind neutral interfaces
   - `core:diagnostics` no longer depends on `:core:service`
   - regression guard in `scripts/ci/verify_diagnostics_boundary.py`
6. App-state decomposition
   - `MainViewModel` orchestration split into focused coordinators and resolvers
   - constructor width reduced through grouped dependency carriers
   - focused unit coverage for extracted behavior
7. Session-scoped DI
   - explicit service-session components for VPN/proxy runtime lifetime
   - session-owned coordinators and supervisors removed from manual assembly paths
   - lifetime model documented in [docs/service-session-scope.md](docs/service-session-scope.md)
8. TCP concurrency validation
   - bounded hybrid relay model measured under load
   - per-connection resource budget coverage
   - current model documented in [docs/native/tcp-concurrency.md](docs/native/tcp-concurrency.md)
9. JNI wrapper validation
   - handle lifecycle audit for `poll`, `stop`, `destroy`, and update paths
   - race coverage for stale-handle transitions
   - lifecycle-sensitive JNI calls serialized where needed
10. Relay cleanup and size monitoring
   - mechanical backend-dispatch simplification
   - CI size and bloat attribution reporting
   - size policy documented in [docs/native/size-monitoring.md](docs/native/size-monitoring.md)
11. UI/UX design system compliance and M3 Expressive adoption
   - full DESIGN.md + M3 compliance audit (33 violations identified, all resolved)
   - design token completion: outline, outlineVariant, scrim colors; xs, xlIncreased, xxlIncreased, xxxl shapes; SwitchThumb elevation
   - M3 Expressive: spring-based motion, shape morphing on press, emphasized typography, contrast level infrastructure
   - navigation: deep links (ripdpi://), app shortcuts, adaptive layout helper, nested slide transitions
   - UX: connection quality indicator, settings reset-to-defaults, Quick Settings tile labels
   - accessibility: live region announcements, RTL fix, pluralization for counters
   - screenshot test coverage expanded from 26 to 34 catalog entries (multi-config variants)

## Architecture Documentation

- [docs/testing.md](docs/testing.md)
- [docs/native/unsafe-audit.md](docs/native/unsafe-audit.md)
- [docs/service-session-scope.md](docs/service-session-scope.md)
- [docs/native/tcp-concurrency.md](docs/native/tcp-concurrency.md)
- [docs/native/size-monitoring.md](docs/native/size-monitoring.md)

## Next Work

Four active roadmaps continue the work past the audit baseline. See [Document Map](#document-map) below for how they relate and which owns what.

- **[DPI Bypass Technique Expansion](ROADMAP-bypass-techniques.md)** -- 15 techniques from field research (ntc.party, Habr, TechnicalVault). Tier 1 and Tier 2 shipped on 2026-04-13; Tier 3 (SYN-Hide, UDP-over-ICMP, server-side scoring) remains experimental. Tactical checklist: circular rotation, conditional execution, TCP flags, IP ID, fakedsplit ordering, PCAP, and more.
- **[DPI Bypass Modernization](ROADMAP-bypass-modernization.md)** -- strategic roadmap for the next generation of RIPDPI bypass work: capability hygiene, unified first-flight IR, QUIC Initial shaping subsystem, DNS oracle hardening, TLS/ECH modernization, contextual evaluation, root/non-root emitter rationalization, Android hardening, and rollout gates.
- **[Architecture Refactor And Modularization](ROADMAP-architecture-refactor.md)** -- structural roadmap derived from the architecture audit: config-contract unification, diagnostics bounded-context split, native runtime/desync decomposition, service and relay orchestration cleanup, settings/screen decomposition, ViewModel dependency shaping, and CI complexity guardrails.
- **[Integrations Roadmap](docs/roadmap-integrations.md)** -- ongoing validation and operational refresh for shipped transports: Cloudflare-direct MASQUE, Cloudflare Tunnel publish mode, Finalmask expansion, NaiveProxy, TLS/strategy-pack freshness, relay interoperability.

## Document Map

```
ROADMAP.md  (index, audit complete)
|
+-- ROADMAP-bypass-techniques.md      tactical checklist (what to ship)
|
+-- ROADMAP-bypass-modernization.md   strategic design (how to architect bypass)
|
+-- ROADMAP-architecture-refactor.md  structural cleanup (how code supports it)
|
+-- docs/roadmap-integrations.md      transport/relay operations
```

The three strategic roadmaps interlock and should not be read in isolation:

- **techniques** defines the tactics catalog. It has 12 of 15 items shipped and audited; only Tier 3 experimental items remain open.
- **modernization** defines the planner, emitter, and measurement architecture those tactics must fit into. It supersedes isolated tactic design going forward.
- **architecture-refactor** defines the code seams that let the other two land without regressing diagnostics, service lifecycle, or settings.
- **integrations** is the transport/control-plane layer underneath bypass tactics. It is largely shipped but interacts with bypass-modernization Workstream 5 (TLS catalog freshness feeds template rollout) and architecture-refactor Workstream 4 (relay orchestration cleanup).

### Sequencing Across Roadmaps

1. **architecture-refactor Workstream 0-1** (guardrails, config contract) unblocks everything else -- both bypass work and settings splits depend on the canonical config seam.
2. **bypass-modernization Workstream 1** (capability hygiene) must land before further expansion of bypass-techniques Tier 3, otherwise new tactics cannot be evaluated honestly.
3. **architecture-refactor Workstream 3** (runtime/desync decomposition) and **bypass-modernization Workstream 2** (first-flight IR) touch the same files. Workstream 2 already has partial landed scope, but the remaining planner/lowering migration should still wait for Workstream 3 slices 3.5-3.10 rather than expanding on a half-decomposed runtime.
4. **bypass-modernization Workstream 3** (QUIC subsystem) owns the QUIC evolution. New QUIC probe candidates in bypass-techniques must route through it rather than extending ad hoc packet families.
5. **integrations Finalmask/TLS tracks** should align TLS catalog revisions with bypass-modernization Workstream 5 (browser-family templates and ECH).

### Ownership Collisions Resolved

| Topic | Tactical owner | Architectural owner |
|-------|---------------|---------------------|
| QUIC Initial shaping | bypass-techniques (probe candidates) | bypass-modernization Workstream 3 (subsystem) |
| Root/non-root emitter split | bypass-techniques #6 (SOCKS5 hardening, shipped) | bypass-modernization Workstream 7 (tier definition) |
| DNS hostname recovery | bypass-techniques #10 (LRU cache, shipped) | bypass-modernization Workstream 4 (multi-oracle scoring) |
| Runtime/desync code layout | architecture-refactor Workstream 3 | bypass-modernization Workstream 2 (IR) lowers onto new seams |
| TLS template delivery | integrations (strategy-pack catalogs) | bypass-modernization Workstream 5 (browser-family templates) |

New tactic proposals belong in **bypass-techniques**. New architectural contracts belong in **bypass-modernization** or **architecture-refactor** depending on scope.
