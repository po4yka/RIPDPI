---
task_id: TRN-1794158969509887
change: std02-tls-extensions
commit_sha: 52d23e3086d49574f1bedbaacd7f75c2326ddc93
local: passed
local_evidence: The combined 16-crate run passed 1515 tests with 17 existing ignored tests and no filtered tests. After the final HTTP authority correction, all 138 tests in the three affected crates and all-target Clippy passed. Workspace check, independent PR and async reviews, final just ci-gates and all five commit hooks passed.
remote_ci: required
remote_ci_evidence: No exact-source remote CI run has been observed.
device: not_applicable
device_evidence: This repair changes native TLS identity and ALPN policy. Loopback peers cover the protocol contract; no Android lifecycle or routing claim is made.
artifact: not_applicable
artifact_evidence: No packaged binary or release artifact is owned by this change. Affected native libraries are compiled by the local test and check gates.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STD02-ALPN-LENGTH | TRN-1794159467177163 | Baseline rejected by two new boundary regressions; final profile tests accept the 255-byte opaque identifier and reject invalid identifier/list lengths | Passed locally |
| REQ-STD02-DOT | TRN-1794159082853154 | Real rustls peers cover TLS 1.2/1.3, default/custom builders, direct/SOCKS, dot/legacy and incompatible ALPN; full resolver suite passes | Passed locally |
| REQ-STD02-H2 | TRN-1794159083450341 | MASQUE TCP/UDP real TLS peers receive no application bytes on failure; authenticated REALITY positive/negative fixtures and relay-core xHTTP cross-stack tests pass | Passed locally |
| REQ-STD02-NAME | TRN-1794159084016494 | Baseline name regressions fail; independent rustls Acceptor decodes final Boring ClientHello; DNS/IP, URL, HTTPS/HTTP distinction, certificate identity and WebTunnel echo regressions pass | Passed locally |
| REQ-STD02-DIAGNOSTIC | TRN-1794159084544353 | Baseline public-helper regression fails; final explicit invalid DNS/IP override returns an error and empty fallback stays intact | Passed locally |

## Observed checks

- Combined affected package tests: 16 crates, 64 suites, 1515 passed, 0 failed, 17 ignored, 0 filtered. The ignored tests need independent pinned peers, owner credentials, explicit soak or a manual benchmark; they were not enabled or used as proof.
- A final review then found that the SNI patch changed HTTP authority spelling for root-dot HTTPS URLs. Fetch, MASQUE and WARP now retain the parsed HTTP host separately from TLS identity. After this correction, all 138 tests in those three crates passed with no failures, ignored tests or filtered tests. This includes a new request-header regression for root-dot and IPv6 authorities with nondefault ports. Their all-target Clippy check also passed.
- Clippy for the 15 production crates, all targets, no dependencies, warnings denied: passed. Workspace cargo check: passed. Clippy for the changed local-network-fixture and relay-core, all targets with warnings denied: passed.
- Cargo fmt check, locked metadata, generated API snapshot check, independent PR review and async cancellation review: passed.
- Final just ci-gates: passed. The API checker selected 13 crates; the canonical Linux-owned runtime-platform snapshot was skipped on Darwin by the existing check policy.
- The first combined compile found an undeclared helper use in relay-core. Typed URL host extraction fixed it without a new dependency. The next combined test run found four old H2 REALITY fixtures with no ALPN. The dedicated xHTTP peer now selects h2; all four tests pass in the final combined run.
- An initial MASQUE negative fixture treated macOS connection reset as a read failure. It now accepts EOF, ConnectionReset or ConnectionAborted only after successful TLS. It still rejects timeout or other read errors and requires an ALPN-specific client failure.

Commands use the pinned toolchain, `--locked`, four Cargo jobs, sccache, and `build-gate --lane ripdpi`. The affected packages are local-network-fixture, android-support, ripdpi-android-fetch-adapter, ripdpi-anytls, ripdpi-diagnostics-tls, ripdpi-dns-resolver, ripdpi-masque, ripdpi-monitor-engine, ripdpi-relay-core, ripdpi-tls-profiles, ripdpi-trojan, ripdpi-vless, ripdpi-warp-android, ripdpi-webtunnel, ripdpi-ws-tunnel and ripdpi-xhttp.

## Limits

Local observations do not establish external interoperability. No physical Android, root, production credentials or external TLS peer was used. Plain xHTTP ALPN policy has source coverage; negotiated real-peer H2 coverage uses authenticated REALITY and MASQUE. Long resource soak, independent upstream interoperability, HRR/resumption and Android IPv6 URL routing remain unverified. GREASE profile ordering and browser templates are unchanged.

## Integration

The user authorized separate fix commits, main integration and origin push. Each substantial Rust change requires independent review. Combined source gates belong to TRN-1794159085048462. External peers, real credentials, root and physical devices remain outside this change.

| Problem | Fix commit |
|---|---|
| F1 DoT transport ALPN | 6eeeeb075ac7d4e8b66f3db176e0b67cb4e8625d |
| F2 negotiated H2 gate and real H2 fixture | a7e67abb8824fa4cbeaa7b7dfa20e85a782862b3 |
| F3 TLS identity and HTTP authority | 7d3af00d1ad73288d227a6fd278d8634861f0af1 |
| H1 explicit diagnostic identity | e1dfae711215ffac8af1cdb6aacd5dae1c2492be |
| ALPN identifier and list limits | 52d23e3086d49574f1bedbaacd7f75c2326ddc93 |

The source verification SHA above includes all five fixes. The task stays in review until exact-source remote CI is resolved. No archive or closure is claimed.
