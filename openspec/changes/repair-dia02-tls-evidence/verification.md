---
task_id: DGN-1794730805168562
change: repair-dia02-tls-evidence
commit_sha: e56649eecf4a31daa9ac1643b7ad9ff1daa6f362
local: passed
local_evidence: Locked Rust tests, loopback fixtures, JVM policy tests, offline analytics and the combined source gates passed. See results below.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Host regressions cover the repair. Android device and route acceptance were not requested.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence target | Result |
|---|---|---|---|
| REQ-DIA02-F1 | DGN-1794730885862088 | A complete locally written ClientHello before a peer alert or local trust error | Passed. Loopback alert and self-signed certificates retain JA3. Incomplete captures do not emit a hash. |
| REQ-DIA02-F2 | DGN-1794730895791189 | Distinct outcomes at separate loopback peers and fresh ECH retry | Passed. HTTPS report projects distinct peer roles, times and JA3. Web keeps initial failure and retry success separately. |
| REQ-DIA02-F3 | DGN-1794730896305094 | Peer client-auth/protocol-version alerts, local trust error, and quick response | Passed. Loopback distinguishes peer alert, HelloRetryRequest and actual ServerHello. Android policy attributes evidence to the measured profile. |
| REQ-DIA02-F4 | DGN-1794730896780389 | Failed first candidate, known and unknown peer, and authenticated ECH rejection | Passed. Local fallback policy covers known and unknown peers. Loopback authenticated ECH rejection covers a successful retry and failed reconnect. |
| REQ-DIA02-F5 | DGN-1794730897256277 | Completed evidence before cancellation and ECH-only success or trust failure | Passed. Loopback Web scan covers cancellation and deadline after ClientHello. Strategy checks that an interrupted ECH probe preserves a completed clear-TLS comparison. |
| REQ-DIA02-F6 | DGN-1794730897761185 | Known-answer JA3 with GREASE, fragmentation and malformed lengths | Passed. Known-answer JA3 tests cover GREASE, split TLS records and malformed lengths. |
| REQ-DIA02-F7 | DGN-1794730898243492 | Negotiated and absent ALPN in the strategy and web projections | Passed. Loopback negotiates h2, HTTP/1.1 and no ALPN. Web and strategy report by attempt. |
| REQ-DIA02-F8 | DGN-1794730898711547 | Default vs custom trust and rejected retry without authentication | Passed. Loopback compares the same untrusted certificate across three trust policies and prevents authenticated ECH retry under custom trust. |

## Local results

The Rust commands used `cargo test --locked --offline --jobs 4` under `build-gate`.

| Suite | Passed |
|---|---:|
| TLS crate library | 51 |
| Web domain runner | 7 |
| HTTPS strategy | 5 |
| Web partial scan (deadline and manual cancellation) | 1 |
| WebPKI/custom/skip on the same loopback certificate | 1 |
| Typed domain attribution | 3 |
| Typed TLS status | 1 |
| Offline domain classifier | 3 |
| Cancellation during SOCKS negotiation | 1 |

The Gradle tasks `:core:diagnostics:testDebugUnitTest` and `:app:testGithubFullDebugUnitTest` used `--offline --max-workers=4 -Pripdpi.skipNativeBuild=true` under `build-gate`.

| Test class | Passed |
|---|---:|
| `BypassCombinationScorerTest` | 3 |
| `ComparisonScanCoordinatorTest` | 10 |
| `ConnectionStageTlsAttributionTest` | 4 |
| `DirectModePolicyPersistenceTest` | 5 |
| `DirectModePolicySupportTest` | 24 |
| `TransportSpecificRemediationSupportTest` | 10 |

The offline analytics and strategy simulator passed 20 Python tests. Locked Cargo metadata, `just ci-gates`, the task contract and strict OpenSpec validation passed.

Local results cover host loopback and saved report handling. Android device and externally hosted TLS/ECH counterparts were outside this run.
