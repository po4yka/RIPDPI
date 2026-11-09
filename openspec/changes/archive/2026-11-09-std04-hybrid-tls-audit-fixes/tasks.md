# TRN-1794199027304873

## Objective

Correct all four confirmed STD-04 findings without changing successful TLS packet shape or REALITY admission policy.

## Ownership

F1 owns the vendor alert caller, vendor notes, and the new pq_kem_alerts integration test. F2 owns profile metadata, existing profile tests, affected Kotlin samples, and WS tunnel status documentation. The documentation worker owns VLESS KEM rustdoc and fake-profile provenance. Parent owns task/spec files, review, combined gates, commits, and main integration. Each writer uses its assigned worktree; no shared dependency, schema, fixture, baseline, or locale updates are in scope.

## Execution

- [x] TRN-1794199173301062 Preserve hybrid TLS peer alerts and verify emitted wire errors #bug @item:TRN-1794199027304873
- [x] TRN-1794199174324327 Correct profile metadata and verify captured ClientHello sizes #bug @item:TRN-1794199027304873
- [x] TRN-1794199175321110 Document the mandatory REALITY KEM prefix #bug @item:TRN-1794199027304873
- [x] TRN-1794199177072195 Correct legacy Kyber capture provenance #bug @item:TRN-1794199027304873

## Verification

Run ripdpi-tls-profiles tests, affected VLESS/packet tests, independent read-only review, just ci-gates, python3 scripts/ci/check_architecture_health.py, and cargo metadata --manifest-path native/rust/Cargo.toml --locked. Use the existing Kotlin test gate if its sample changes. Keep exact results in verification.md.
