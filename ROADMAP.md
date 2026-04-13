# RIPDPI Roadmap

The audit roadmap tracked in this repository is complete. The items below are no longer planned work; they are the current architecture and verification baseline.

## Current Status

- All 10 audit phases have been implemented on `main`.
- There are no remaining open items from the original audit backlog.
- New work should be tracked as a separate roadmap or issue list instead of reopening this completed checklist.

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

## Architecture Documentation

- [docs/testing.md](docs/testing.md)
- [docs/native/unsafe-audit.md](docs/native/unsafe-audit.md)
- [docs/service-session-scope.md](docs/service-session-scope.md)
- [docs/native/tcp-concurrency.md](docs/native/tcp-concurrency.md)
- [docs/native/size-monitoring.md](docs/native/size-monitoring.md)

## Next Work

- **[DPI Bypass Technique Expansion](ROADMAP-bypass-techniques.md)** -- 15 techniques from field research (ntc.party, Habr, TechnicalVault) not yet in RIPDPI: circular strategy rotation, conditional execution, TCP flag manipulation, IP ID control, fakedsplit ordering, SYN-Hide, UDP-over-ICMP, PCAP recording, and more. Tiered by priority with implementation approaches and vault references.
- **[UI/UX & Design System Roadmap](ROADMAP-ui-ux.md)** -- 33 audit violations to fix across 6 phases: critical compliance fixes (Phase 0-1), M3 Expressive adoption (Phase 2), navigation and layout improvements (Phase 3), UX enhancements (Phase 4), accessibility and i18n (Phase 5), screenshot test coverage (Phase 6). Derived from full DESIGN.md + M3 audit (April 2026).
