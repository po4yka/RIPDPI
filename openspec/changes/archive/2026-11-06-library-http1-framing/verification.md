---
task_id: RST-1793903447995667
change: library-http1-framing
commit_sha: 4a346b9e50e53b7311ef1071d97b976ae4ad889c
local: passed
local_evidence: Rust 1.98.1 affected suites passed 531 tests with 2 existing ignored tests; clippy, source gates, cargo deny, and Miri passed. Independent code and async reviews passed.
remote_ci: passed
remote_ci_evidence: CI run https://github.com/po4yka/RIPDPI/actions/runs/42181223749 passed on the exact commit, including Rust lint, fuzz, Miri, workspace tests, native ABI builds, and Android release builds. CodeQL, harness, secret scan, L7, and fleet workflows also passed.
device: not_applicable
device_evidence: This host protocol change preserves Android connectors; no device acceptance claim is made.
artifact: not_applicable
artifact_evidence: No distributed artifact is released by this change.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HTTP1-001 | RST-1793903630514906 | Apps Script HTTP regression suite | Passed |
| REQ-HTTP1-001 | RST-1793903651480026 | Diagnostic framing and transfer regression suites | Passed |
| REQ-HTTP1-001 | RST-1793903651893093 | Protected DoH regression suite | Passed |
| REQ-HTTP1-002 | RST-1793903651480026 | Raw request and partial measurement tests | Passed |
| REQ-HTTP1-002 | RST-1793903630514906 | Cancellation and bounded relay exchange tests | Passed |
| REQ-HTTP1-003 | RST-1793903651893093 | WebTunnel sync and async upgrade tests | Passed |
| REQ-HTTP1-001 | RST-1793903652299388 | Combined source gates and independent code/async reviews | Passed |

## Local checks

Run Cargo commands from `native/rust/` to select Rust 1.98.1.

- `build-gate -- cargo test --locked -p ripdpi-apps-script-core -p ripdpi-diagnostics-http -p ripdpi-diagnostics-fat-header -p ripdpi-diagnostics-runner -p ripdpi-diagnostics-telegram -p ripdpi-dns-resolver -p ripdpi-webtunnel`: exit 0, 531 passed, 2 existing ignored tests.
- The same package set with `cargo clippy --locked --all-targets -- -D warnings`, through `build-gate`: exit 0.
- `build-gate -- just ci-gates` from the checkout root: exit 0. Rust API snapshots match; no baseline was changed.
- `cargo deny --locked --manifest-path native/rust/Cargo.toml check` from the checkout root: exit 0; advisories, bans, licenses, and sources passed.
- `cargo metadata --locked --no-deps --format-version 1`: exit 0. No dependency package or version was added; only existing dependency edges changed.
- `build-gate -- cargo +nightly miri test --locked -p ripdpi-diagnostics-http response_decoder_`: exit 0, 3 decoder tests passed on the final bounded-channel implementation.
- Final async review covered DoH, Apps Script, diagnostics, and WebTunnel. It found no detached connection driver or retained socket after cancellation.
- Independent code review found four defects: unsupported transfer coding on relay client and ingress, interim HEAD handling, HEAD framing policy, and canonical reason text. All were corrected and the second review found no remaining defects.

The reason-phrase regression passed again after the final lint-only correction.

The new regression tests first failed against the affected implementation. They now cover a delayed close-delimited body, malformed chunks and trailers, conflicting lengths, unsupported transfer coding, interim responses, body limits, request boundaries, and tunnel bytes. Ingress tests also cover `100 Continue` and a TE+CL request followed by a second request that must not run.

## Library-controlled behavior

- Hyper owns chunk syntax, extension limits, trailers, and HTTP body completion. Its default chunk-extension and trailer byte limits are 16 KiB. Application body caps remain 16 MiB for ingress, 32 MiB for relay replies and decoded gzip, and 65,535 bytes for DoH.
- Apps Script uses a 1 MiB header buffer. Ingress header reads and individual body-frame waits have a 60-second limit. The upstream relay keeps its existing whole-exchange and per-attempt deadlines.
- Diagnostic responses use a bounded input buffer and at most 128 header fields. They preserve raw outbound request bytes and distinguish received payload from completed framing. Up to four interim heads can precede the final head.
- A chunk length alone does not allocate a body of that size. Body limits apply to received data; a declared Content-Length over the cap fails before body collection.
- Hyper normalizes ingress TE+CL and closes after one request. Clients reject TE+CL. Unsupported transfer coding fails on both paths.
- Typed response serialization can change header name casing and reason text. The diagnostics path preserves the received reason phrase, including an explicit empty phrase.

No live Google endpoint, Android device, release artifact, or production deployment was used as acceptance evidence.

## Hosted gate corrections

The first push, `067ba0978d2fc0c166d12401be3438b75f156a0e`, failed the fuzz lock preflight and the shared-state source guard. These are not accepted as passing evidence.

- The fuzz workspace has its own lockfile. Regeneration added the three DoH HTTP dependency edges without changing package versions. Locked metadata now resolves.
- The diagnostic adapter now transfers owned byte buffers through a bounded channel. It no longer shares the input buffer through `Rc<RefCell>`. No scanner exception or baseline was added.
- After the corrections, the affected diagnostic suites passed 256 tests with 2 existing ignored tests. Clippy passed. Miri passed 3 decoder tests. Both code and async reviews found no defects in the channel change.
- The exact fuzz preflight, `build-gate -- cargo +nightly test --manifest-path fuzz/Cargo.toml --locked --test tls_record_inputs`, passed 5 tests. The soundness scanner suite passed 28 tests.
- After rebase onto the concurrent protobuf and IP/CIDR changes, all 531 affected tests passed with 2 existing ignored tests. The combined source gates passed again.
- CI run [42181223749](https://github.com/po4yka/RIPDPI/actions/runs/42181223749) passed on `4a346b9e50e53b7311ef1071d97b976ae4ad889c`. The six workflows on this commit completed successfully. This is the hosted acceptance evidence; the earlier failed run remains recorded above.
