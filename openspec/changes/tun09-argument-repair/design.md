## Context

Task TRN-1794489808420764 follows the TUN-09 report and current main 48b7b184d716abd47cb0f0c9b5859984150c5fd4. See proposal.md. Only F4 needs a new production repair.

## Goals / Non-Goals

- Goal: preserve argument boundaries and reference parser behavior with one semantic validator.
- Non-goal: a new Tor runtime, generic PT relay support, library embedding lifecycle, or external acceptance.

## Decisions

- Extract existing bridge option validation into a crate-private function and use it from both parser entry points.
- Parse escaped key and value fields directly. Keep the first repeated value, matching goptlib 1.6.0 Args.Get, in both entry points.
- Ignore unknown keys after valid grammar parsing. Do not restrict escaping to three characters because goptlib accepts the next byte.
- Extend owning tests, first observe the regression, then run the complete crate suite. Use pinned upstream test vectors as an independent source.

## Contracts and ownership

- Only ripdpi-webtunnel code and existing tests change. The argument worker owns these paths in its isolated worktree.
- The coordinator owns portfolio, specification and evidence files. Review agents do not write.
- No Cargo.lock, dependency, locale, golden, baseline, JNI, protobuf or storage change.

## Risks / Trade-offs

- Duplicate and empty-entry behavior becomes stricter/reference-aligned. Existing valid bridges retain their values.
- A pure parser fix does not prove Android packaging or live Tor compatibility; retain that evidence boundary.
- Required gates: cargo test --locked -p ripdpi-webtunnel --jobs 4; cargo clippy --locked -p ripdpi-webtunnel --all-targets --jobs 4 -- -D warnings; cargo fmt --check -p ripdpi-webtunnel; just ci-gates; taskctl validation. F1 regression gate uses service resolver and app mode-editor tests.

## Migration Plan

No stored data migration. Integrate a reviewed atomic F4 commit after local gates; preserve existing F1-F3 commits. A revert restores the prior parser behavior if compatibility fails. Device and external Tor probes are not part of this repair.
