# RST-1794677647125288: Repair DPI-26 contracts

## Objective

Preserve safe UDP error recovery, selected ID policy and root admission.

## Ownership

Primary integrator owns proxy-config protocol projection, Kotlin StrategyChainValidation and StrategyChainsIpFragTest, task/spec files and integration. Helper worker owns privileged UDP error wrapping, helper response formatting, platform decoding, UDP adapter fallback and their tests. Review agents are read-only. Shared schemas, dependency locks, locales, baselines and goldens are not changed.

## Execution

- [ ] RST-1794677761670924 Preserve safe UDP fragmentation errors and test failure boundaries #bug !high @item:RST-1794677647125288
- [x] RST-1794677762354249 Preserve UDP IPv4 ID policy and test supported selections #bug !high @item:RST-1794677647125288
- [ ] RST-1794677763048279 Require root for fragmentation drafts and test non-root behavior #bug !high @item:RST-1794677647125288

## Verification

Run affected Rust crate tests with --locked, StrategyChain Kotlin tests, staticAnalysis, locked Cargo metadata, just ci-gates and independent precommit review. Report Linux IPC and Android compilation separately from unexecuted privileged wire/device acceptance.
