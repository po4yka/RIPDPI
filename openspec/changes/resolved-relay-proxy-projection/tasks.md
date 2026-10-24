# SVC-1792830780372912

## Objective

Project the resolved running relay into the proxy envelope before startup.

## Ownership

Own core/service startup supervisors, shared stack, their tests, and docs/architecture/CONFIG_CONTRACTS.md. Android harness/device and integration work remain with their assigned owners. The security owner edits imported-profile mapping, the shared model predicate, RelayKindDescriptor, engine relay preference normalization, the two app failover predicate consumers, and their tests in a separate worktree. Native code, storage contracts, and dependencies are excluded.

## Execution

- [x] SVC-1792830889982340 Capture resolved ready relay identity and endpoint for normal proxy startup #bug !high @item:SVC-1792830780372912
- [x] SVC-1792830891083169 Verify normal and race projection admission and cleanup regressions #bug !high @item:SVC-1792830780372912
- [x] SVC-1792830892443639 Complete independent review and record integration acceptance evidence #bug !high @item:SVC-1792830780372912

## Verification

Use the observed owned physical startup failure on fea412b9ef4165f3d4a97651ce7c0e9033de1d25 as the pre-fix regression evidence. The separately queued JVM RED run was cancelled before Gradle started because the ordinary build slots were occupied. Run the complete core/service JVM suite, core/service ktlint and detekt, architecture health, and task validation. Use build-gate for Gradle with at most four workers. Independent review precedes commit; hosted and physical Hysteria acceptance follow integration.
- [x] SVC-1792831043407677 Preserve supported imported Reality UDP policy with a shared sub-mode predicate #bug !high @item:SVC-1792830780372912
- [x] SVC-1792834775339498 Preserve supported resolved UDP through engine preference normalization #bug !high @item:SVC-1792830780372912
