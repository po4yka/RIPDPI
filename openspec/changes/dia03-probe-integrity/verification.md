---
task_id: DGN-1794732416004558
change: dia03-probe-integrity
commit_sha: 9d048a8573bcd0bc4c9c96f449e9bb9568e3d4ee
local: blocked
local_evidence: DIA-03 regressions and source gates passed; the broader TLS golden failed on both the repaired branch and pristine upstream d88a1269. No golden was changed; full local acceptance remains blocked.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is reported separately after push and is not a local repair acceptance gate.
device: not_applicable
device_evidence: This change repairs Rust protocol validation and classification; Android or external active acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No distributable artifact or release is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA03-H3 | DGN-1794732507106058 | Trusted loopback rejects connection-specific fields in response headers and trailers, duplicate/late/request pseudo-fields and trailer pseudo-fields. Independent QPACK bytes also prove valid trailers still complete. | passed C/V/S-subset |
| REQ-DIA03-LIMIT | DGN-1794732512662461 | GET rejects a HEADERS length of 65537 before payload/FIN. PMTU rejects oversized SETTINGS for IPv4 and IPv6. A decoded field section of 16075, unknown frame payload of 131072 and DATA of 131072 remain usable. Truncated unknown frames and empty-payload DATA at FIN fail. | passed C/V-local/S-subset |
| REQ-DIA03-CODING | DGN-1794732517555608 | Both generic HTTP and JSON requests negotiate identity. A gzip blockpage returns unsupported_content_encoding through http_unreachable rather than clean success. Empty coding list elements and bodyless metadata remain accepted. Framing, deadlines and 64 KiB body limits remain active. | passed C/V-local/S-subset |
| REQ-DIA03-FINGERPRINT | DGN-1794732524778961 | Short and 12011-byte ordinary court articles do not confirm blocking. Weak generic phrases require the existing bounded explicit-blocking predicate. Strong ISP and header signatures remain usable on large pages. | passed C/V-local |
| REQ-DIA03-TIMEOUT | DGN-1794732531296335 | Own budget reports timeout; an earlier scan budget reports deadline_exceeded; cancellation reports cancelled. Existing partial ACK facts remain preserved. | passed C/V-local |
| Combined checks | DGN-1794732539648594 | Independent pr-reviewer and async-cancel-safety passed. Five-crate clippy, fmt, locked metadata and just ci-gates passed. The broader engine golden failed on both the branch and clean upstream; details are below. | partial; no all-green claim |

## Repairs and scope

- F1/H2: the shared h3 decoder rejects forbidden fields and invalid pseudo-fields before response completion. See native/rust/vendor/h3/src/proto/headers.rs:114 and:224. The request and trailer contexts use their own checks. HeaderTooBig retains its prior H3_REQUEST_CANCELLED behavior.
- F2: request negotiation is in diagnostics-http/src/http/probe_execution.rs:290; unsupported coding is rejected at response_parser.rs:34, before body acceptance. No decompressor or dependency was added.
- F3: response_fingerprint.rs:27 uses confidence and explicit blocking context to reject generic body-only matches. This remains a heuristic content classifier and does not identify an injector.
- F4: pmtu.rs:27 retains the scan deadline separately from the local deadline, then preserves the source when it reports a timeout.
- H1: vendor/h3/src/frame.rs:220 defines a local 64 KiB limit for buffered known non-DATA payloads. Unknown payloads are discarded as chunks arrive. The check precedes payload accumulation. DATA uses a checked platform-size conversion and remains streamed. This is a retained frame-input boundary, not a 64 KiB total heap limit; no RSS/OOM claim is made.
- During H1 reproduction, DATA length 5 followed immediately by FIN caused a panic in poll_next. The same frame-state repair now returns a typed failure. The unbounded WebTransport sentinel remains separate; WebTransport is outside the DIA-03 GET/PMTU callers.

The original audit remains at /Users/npochaev/Downloads/RIPDPI_network_audits/DIA-03.md. It describes the pre-repair checkout and is not overwritten by this change.

## Observed commands and results

All Cargo resolution used --locked and Rust 1.100.0. Heavy commands used build-gate --lane ripdpi, jobs 4, CMAKE_BUILD_PARALLEL_LEVEL=4 and an explicit worktree-local CARGO_TARGET_DIR. A shared-target trial and a wrong-host-toolchain trial were rejected as acceptance evidence.

At ea57619e3cbcadc6fa37747e975ac2b145e8eac4, from native/rust/:

```text
cargo test --locked --jobs 4 -p ripdpi-diagnostics-http -p ripdpi-failure-classifier -p ripdpi-masque -p ripdpi-diagnostics-runner -p ripdpi-monitor-engine --lib -- --test-threads=4
HTTP: 99 passed
failure-classifier: 109 passed
MASQUE: 136 passed
runner: 137 passed, 2 existing tests ignored for fixture timing
monitor-engine: 285 passed, 1 golden mismatch
overall exit: 101
```

The ignored runner tests are dns_probe_appends_dns_trigger_fuzzing_details and domain_probe_appends_http_trigger_fuzzing_details. Their annotations were not changed and they are not used as proof of completion.

At 9d048a8573bcd0bc4c9c96f449e9bb9568e3d4ee, the DIA-03 sources are byte-identical to that tested SHA. Incoming changes only add existing boring/rcgen dependency edges in the neighboring ECH probe crate; dependency versions did not change.

```text
cargo fmt --all -- --check                                           exit 0
cargo clippy --locked --jobs 4 -p ripdpi-diagnostics-http -p ripdpi-failure-classifier -p ripdpi-masque -p ripdpi-diagnostics-runner -p ripdpi-monitor-engine --all-targets -- -D warnings  exit 0
cargo metadata --locked --format-version 1                          exit 0
just ci-gates                                                      exit 0
```

just ci-gates observed architecture health, LoC/hotspot limits, cross-language contracts, protocol fixture versions, diagnostics boundaries, route coverage, locale parity, translation export and Rust API snapshots. The platform-owned Linux runtime-platform snapshot is skipped on Darwin by the repository checker; Android compilation is not claimed.

The pre-repair H1 GET tests returned 3 passed/2 failed, including oversized HEADERS TIMEOUT and truncated DATA panic. The PMTU resource regression failed with OBSERVED before the new storage policy aborted the driver. This does not mean its QUIC ACK facts were fabricated. After the repair, all 94 HTTP tests passed before incoming HTTP tests increased the suite to 99.

Standalone cargo test -p h3 --locked --lib --jobs 4 returned 101 because h3 requires dev-dependencies but is not a workspace member. No membership or lock change was made to bypass that restriction. Runtime behavior is covered through the real GET/PMTU fixtures and MASQUE callers.

## Broader golden gate

monitor_json_contracts_match_goldens failed on final_report.json. The generated diff adds TLS evidence fields and contains unsanitized tls13/tls12/tlsEchFailureDurationMs values. The same narrow test on the current rebased branch failed again. A clean detached upstream run at d88a1269b5655c59fd696a74f970959d8c8582e0 reproduced the same final_report.json mismatch: exit 101, 0 passed, 1 failed, 285 filtered out. It used a fresh target, the pinned toolchain and exactly cargo test -p ripdpi-monitor-engine --locked --jobs 4 --lib tests::monitor_json_contracts_match_goldens -- --exact --test-threads=1. Its tracked state remained clean. An independent comparison found the baseline and repaired actual JSON byte-identical, and the expected fixtures byte-identical; no timing or detail-order normalization was needed to establish equality. Hashes and the comparison are in baseline/comparison.json. Golden files and scrubbers are unchanged. The task stays in review until this gate is accepted or repaired under the golden discipline; it is not archived as all-green.

The baseline mismatch is outside the DIA-03 application repairs. Its minimal follow-up is to normalize the new volatile TLS failure-duration detail keys, then review and update the affected monitor-engine golden family under explicit authorization. The acceptance command is the narrow golden test above, followed by the owning crate suite. The rule in .claude/rules/golden-bless-discipline.md requires that the user explicitly authorize blessing and identify the fixture family or path. No approval exception, fixture change or scrubber change was made in this session.

## Evidence boundaries and remaining acceptance

- C: current source, callers and independent reviews support the repairs. RAW_PATH/Direct admission and existing certificate, ALPN, cancellation and budget controls remain required and are exercised by the HTTP suite.
- V: real host TCP/QUIC/TLS loopback runs support the measured HTTP, H3 GET and separate IPv4/IPv6 DPLPMTUD cases. Offline classifier tests do not become live injection measurements. No Android/JNI/UI run was made.
- S: verified subsets use RFC 9114 Sections 4.1.2, 4.2, 7.1, 9 and 10.5; RFC 9204 static/literal field encodings; and RFC 9110 coding/list rules. This is not full protocol conformance certification.
- E: external peer interoperability, physical-device transport, Android protect/fragmentation behavior and public certificate-path coverage remain unverified. No physical/root activation or external diagnostic probe was run. Hosted CI after push is a separate gate.

The pinned source remains h3 0.0.8 at upstream 22c1aa3f44d1463cd7644c8f654fffc9a6da305c. See [RFC 9114](https://www.rfc-editor.org/rfc/rfc9114.html), [RFC 9204](https://www.rfc-editor.org/rfc/rfc9204.html), [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html), and [pinned h3](https://github.com/hyperium/h3/tree/22c1aa3f44d1463cd7644c8f654fffc9a6da305c/h3).

Durable command receipts, complete logs, review notes, the golden diff and the baseline readiness record are in /Users/npochaev/Downloads/RIPDPI_network_audits/DIA-03-repair-evidence-20261115/. Some earlier transient logs became unavailable; observed red output is explicitly identified as a transcription in observed-red.txt.
