# OUT-1794722656313653: Complete proxy URI import

## Objective

Fix all seven confirmed audit defects and support TUIC URI profiles and advanced Mieru TCP settings through activation and export.

## Ownership

See docs/tasks/issues/complete-proxy-uri-import.md. Shared profile/mapping files pass from codec to Mieru; saved mapper passes from persistence to codec to Mieru. Each writer uses a dedicated worktree.

## Execution

## Verification

Owning runtime-state/core-data/app/service/engine Kotlin tests, locked Mieru/relay Cargo tests and pinned Go loopback, native config contract tests, staticAnalysis, ci-gates, independent pr and async review. Hosted CI is checked after authorized push. No physical/external-server acceptance is claimed.
- [x] OUT-1794723300448448 Fix F1 query security field decoding and verify collisions #bug !high @item:OUT-1794722656313653
- [x] OUT-1794723305001424 Fix F2 atomic durable imports with Room failure and restart tests #bug !high @item:OUT-1794722656313653
- [x] OUT-1794723307980598 Fix F3 saved Mieru option export and round-trip tests #bug !high @item:OUT-1794722656313653
- [x] OUT-1794723312688319 Fix F4 SIP002 SS2022 export with independent expected form #bug !high @item:OUT-1794722656313653
- [x] OUT-1794723319097202 Fix F5 SSH literal plus decoding and credential tests #bug !high @item:OUT-1794722656313653
- [x] OUT-1794723323595628 Fix F6 scanner secret redaction and failure tests #bug !high @item:OUT-1794722656313653
- [x] OUT-1794723325992640 Fix F7 SS scheme case handling and parser tests #bug @item:OUT-1794722656313653
- [x] OUT-1794723329887849 Add typed TUIC URI persistence activation and lossless export #feature !high @item:OUT-1794722656313653
- [x] OUT-1794723333000951 Apply pinned advanced Mieru TCP settings with oracle and loopback tests #feature !high @item:OUT-1794722656313653
- [x] OUT-1794723336494326 Review combined changes run gates integrate main and push #chore !high @item:OUT-1794722656313653
- [x] OUT-1794749897872895 Remove inherited diagnostics lint blockers without behavior changes #chore !high @item:OUT-1794722656313653
- [ ] OUT-1794756875357718 Complete TUIC and advanced Mieru backup field policies #bug !high @item:OUT-1794722656313653
