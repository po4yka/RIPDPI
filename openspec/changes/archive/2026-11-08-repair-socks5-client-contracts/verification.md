---
task_id: PRX-1794125331192374
change: repair-socks5-client-contracts
commit_sha: bdb4f8353d59ee46d1115b3f28933188e351a184
local: passed
local_evidence: "1171 Rust tests passed; 18 pre-existing ignores; 2143 Kotlin tests passed; staticAnalysis, Clippy, fmt, locked metadata, ci-gates and task validation passed. Durable logs: /Users/npochaev/Downloads/RIPDPI_network_audits/LOC-09-repair-evidence-20261108-133616"
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not the requested local acceptance gate. Push is a separate action and does not prove CI success.
device: not_applicable
device_evidence: Host protocol checks cover this source repair. Android VPN protection, physical transport, root and external-server acceptance were not run or claimed.
artifact: not_applicable
artifact_evidence: No release package or Android native artifact is published by this source repair.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOC09-HEADER | PRX-1794125599111823 | Rust reply_contract_tests/reply_validation_tests and Kotlin malformed-success loopback cases reject VER/RSV before payload; REP failures remain errors. | passed |
| REQ-LOC09-LIFETIME | PRX-1794125607745222 | session/udp/lifetime_tests plus io_loop/udp_assoc/control_lifetime_tests cover FIN, reset/error terminal handling, queued replies, shared invalidation, idle wakeup and fresh binding. | passed |
| REQ-UDP-CONTROL-LIFETIME | PRX-1794125607745222 | Same TUN lifecycle regressions; existing proxy integration udp_control_lifetime, udp_setup_shutdown and udp_source_rebind pass. | passed |
| REQ-LOC09-DEADLINE | PRX-1794125608259397 | upstream_socks_slow_setup_uses_one_deadline covers slow method/auth/reply; no-timeout split replies and upstream_socks_shutdown forced-drain integration pass. | passed |
| REQ-LOC09-IPV6 | PRX-1794125608703627 | socks5_http_transport_accepts_ipv6_proxy_hosts constructs DNS HTTP clients with raw ::1 host and bracketed authority. | passed |
| REQ-LOC09-DOMAIN | PRX-1794125609123772 | TUN endpoint_tests, diagnostics reply_contract_tests and Kotlin LocalSocksUdpAssociationTest cover scoped domain forms, invalid names, zero port and address families; short domain UDP frames pass codec regressions. | passed |
| REQ-LOC09-CREDENTIALS | PRX-1794125609697153 | Shared sync/async credential_contract_tests and DNS normalize socks5_contract_tests cover empty, oversized and UTF-8 byte limits before auth output. | passed |
| REQ-LOC09-ENDPOINT-AUTH | PRX-1794125610237604 | Shared auth_contract_tests reject NO-AUTH for configured credentials; RuntimeState upstream_socks tests reject unequal optional credential pairs at startup without disclosing values. | passed |
| REQ-LOC09-COMPAT | PRX-1794125610658819 | Complete owning-crate default suites and integration targets, Kotlin module suites, Clippy, formatting, metadata, source gates and independent reviews pass. | passed |

## Exact source and commands

The final Rust and source gates ran at `bdb4f8353d59ee46d1115b3f28933188e351a184`, based on origin/main `c32e2315dfeeb60794d95c4f7d43ddf0e0b7cfae`. All commands below exited 0. Rust used pinned 1.98.1, a task-owned target directory, CARGO_BUILD_JOBS=4, and the RIPDPI build-gate lane. No baseline, dependency, lockfile, golden, or public API snapshot was changed.

- `cargo fmt --all --check` from `/Users/npochaev/GitHub/RIPDPI-worktrees/loc09-socks5-verified/native/rust`; log `/Users/npochaev/Downloads/RIPDPI_network_audits/LOC-09-repair-evidence-20261108-133616/loc09-final-fmt.log`.
- `cargo test --locked --jobs 4 -p ripdpi-socks5-core -p ripdpi-dns-resolver -p ripdpi-diagnostics-transport -p ripdpi-tunnel-core -p ripdpi-proxy-runtime` from `/Users/npochaev/GitHub/RIPDPI-worktrees/loc09-socks5-verified/native/rust`; log `/Users/npochaev/Downloads/RIPDPI_network_audits/LOC-09-repair-evidence-20261108-133616/loc09-final-tests.log`.
- `bash ../../scripts/ci/cargo-guarded.sh cargo clippy --locked --jobs 4 -p ripdpi-socks5-core -p ripdpi-dns-resolver -p ripdpi-diagnostics-transport -p ripdpi-tunnel-core -p ripdpi-proxy-runtime --all-targets --no-deps -- -D warnings` from `/Users/npochaev/GitHub/RIPDPI-worktrees/loc09-socks5-verified/native/rust`; log `/Users/npochaev/Downloads/RIPDPI_network_audits/LOC-09-repair-evidence-20261108-133616/loc09-final-clippy.log`.
- `cargo metadata --locked --format-version 1` from `/Users/npochaev/GitHub/RIPDPI-worktrees/loc09-socks5-verified/native/rust`; log `/Users/npochaev/Downloads/RIPDPI_network_audits/LOC-09-repair-evidence-20261108-133616/loc09-final-metadata.json`.
- `just ci-gates` from `/Users/npochaev/GitHub/RIPDPI-worktrees/loc09-socks5-verified`; log `/Users/npochaev/Downloads/RIPDPI_network_audits/LOC-09-repair-evidence-20261108-133616/loc09-ci-gates.log`.
- `./taskctl validate` from `/Users/npochaev/GitHub/RIPDPI-worktrees/loc09-socks5-verified`; log `/Users/npochaev/Downloads/RIPDPI_network_audits/LOC-09-repair-evidence-20261108-133616/loc09-final-task-contracts.log`.

Kotlin command from the coordinator worktree:

```sh
build-gate --lane ripdpi -- ./gradlew :core:engine-api:testDebugUnitTest :core:service:testDebugUnitTest staticAnalysis --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4
```

Result: 73 engine-api and 2070 service tests, zero failures or skips; BUILD SUCCESSFUL in 9m29s, 855 tasks. All four changed Kotlin production/test files have identical SHA-256 values in the tested coordinator tree and final tree. Rebase changed Rust/docs only. This is host JVM evidence, not an Android native build or device run.

## Review and failed-attempt accounting

- Independent pr-reviewer approved all repairs and the rebase interaction with LOC-11 session accounting at the exact final source SHA.
- Independent async-cancel-safety review approved production await/drop behavior. Test helpers with detached JoinHandles explicitly document that they are not cancel-safe until test-runtime teardown.
- An earlier F3 adapter added per-I/O shutdown checks that changed forced-drain receipt behavior. The adapter was corrected to enforce the deadline only; original phase shutdown checks and registered-socket forced drain remain. No assertion was weakened. The final integration upstream_socks_shutdown passes.
- An earlier full run on base 9673571 failed an unrelated direct TCP-reuse provisional EOF test (351 passed, 1 failed). Its exact standalone diagnostic passed. New upstream c32e2315 includes a fixture readiness change; the final full default run passed, with 356 runtime unit tests. The precise cause of the earlier EOF observation is not established. No LOC-09 production fix or weakened assertion was made for it.
- A previously selected shared-target test binary lacked new markers. Its output is not final-source evidence. A separate task-owned Cargo target was prepared, all workspace packages were cleaned only in that target, and final checks used it. The shared cache was not cleaned. The exact shared-cache mismatch cause is not established.

## Atomic source commits

```text
71adcd12d6b6b0bdad9663694012c83f464c31a1 docs(proxy): plan SOCKS5 client contract repairs
754b8dc1c2ece3222eee38caffa7afe6f3cc3d09 fix(socks5): reject malformed proxy reply headers
534fdd1ab36604f8bbc4c4bebe4f4cfcd6864f3a fix(tunnel): invalidate UDP sessions when control closes
03c897f4c1748e29f3a7a3cfb6bd9fb22177226b fix(proxy): bound upstream SOCKS setup with one deadline
90245b9a7308f427d30f271670937a81a4595f25 fix(dns): format IPv6 SOCKS proxy authorities
2a5d9681873fe455d160ab0914b60d19d9016aa1 fix(socks5): handle scoped domain UDP relay endpoints
135e832340c6b72a4562edb7faaac78218ad4a51 fix(socks5): reject invalid credential byte lengths
c07d8f6fae45db3eed9efe19f3abd0da35ce4000 fix(socks5): require the configured authentication method
b9bd56d5d5ced20d7d989811b2f49504702d09c1 fix(proxy): reject conflicting upstream endpoint credentials
bdb4f8353d59ee46d1115b3f28933188e351a184 fix(tunnel): accept short domain UDP datagrams
```

## Evidence limits and retained scope

C: current callers and ownership were reviewed. V: host loopback, parser, state, integration, JVM and source gates were observed. S: RFC 1928/1929 requirements and pinned upstream comparison are recorded in the original LOC-09 audit; new wire regressions use independent literal bytes. E: no concrete external SOCKS implementation/version was exercised. Local success does not establish E, physical-device acceptance, hosted CI or deployment.

TUN and Kotlin support IP, localhost/localhost. (case-insensitive), and numeric-IP domain relay fields. Arbitrary relay names fail closed because these callers have no relay-name resolver policy. Diagnostics retains its bounded resolver. The unused generic associate helper stays numeric-only. BIND, GSSAPI, remote-domain runtime CONNECT, and DoQ-over-SOCKS remain outside the declared caller scope. Initial OS connect with timeout=None retains its existing cancellation boundary; finite setup uses one total deadline and active registered sockets retain forced shutdown drain.

Pre-existing ignored load/external/doc tests were not enabled. Linux-only TUN/so_bindtodevice targets have zero tests on this macOS host; the runtime-platform public API snapshot is Linux-owned and was skipped by the documented source gate. No real credentials or external-server probes were used. Next acceptance, if authorized: Android VPN/TUN FIN/RST and handover soak; IPv4/IPv6 authenticated CONNECT and UDP lifetime against an identified external SOCKS version; Linux-owned API/TUN gates; hosted CI for the pushed SHA.
