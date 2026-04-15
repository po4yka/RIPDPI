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

- **[DPI Bypass Technique Expansion](ROADMAP-bypass-techniques.md)** -- 15 techniques from field research (ntc.party, Habr, TechnicalVault) not yet in RIPDPI: circular strategy rotation, conditional execution, TCP flag manipulation, IP ID control, fakedsplit ordering, SYN-Hide, UDP-over-ICMP, PCAP recording, and more. Tiered by priority with implementation approaches and vault references.
- **[DPI Bypass Modernization](ROADMAP-bypass-modernization.md)** -- active roadmap for the next generation of RIPDPI bypass work: capability hygiene, unified first-flight IR, QUIC Initial shaping, DNS oracle hardening, TLS/ECH modernization, contextual evaluation, root/non-root emitter rationalization, Android hardening, and rollout gates.
- **[Architecture Refactor And Modularization](ROADMAP-architecture-refactor.md)** -- active roadmap derived from the architecture audit: config-contract unification, diagnostics bounded-context split, native runtime/desync decomposition, service and relay orchestration cleanup, settings/screen decomposition, ViewModel dependency shaping, and CI complexity guardrails.
