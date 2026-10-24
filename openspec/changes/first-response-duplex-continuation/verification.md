---
task_id: PRX-1792849175606125
change: first-response-duplex-continuation
commit_sha: 04d842534bdb550a9f5477c3e528b68ee3f6ab3a
local: passed
local_evidence: Complete proxy-runtime tests passed with 316 tests and 13 existing opt-in ignored tests. Workspace all-target Clippy with warnings denied passed. Android compilation with tests and io-uring enabled passed. The integrated native tree is byte-identical to reviewed commit 35458266a232b4e4d4ae4a31fa3f0459d2539639.
remote_ci: required
remote_ci_evidence: Exact source 38e64afcf9f097a60c8dd9146ed431c108fe35ef passed rust-workspace-tests in run 40105712887, job 120911596401. Its proxy source is unchanged from the recorded implementation. The Linux io_uring step passed all 254 tests with zero skips, including positive WRITE_FIXED completions and zero fallback. Other final candidate checks remain required.
device: required
device_evidence: Physical USB source 04d842534bdb550a9f5477c3e528b68ee3f6ab3a passed the previously failing 1 MiB upload in 941 ms, a 60-second sleep/wake stream, automatic TCP reserve selection and reserve download. The next reserve DNS check failed on missing XUDP source authority, tracked by TRN-1792853642811403. Cleanup passed; complete Hysteria acceptance remains required.
artifact: passed
artifact_evidence: Genuine arm64-v8a Android preparation passed for clean source 04d842534bdb550a9f5477c3e528b68ee3f6ab3a in 5m 59s. App SHA-256 c17f7b22a4a8c6400903c8fd2f2a67be08d59f1c027c13e917c501f6d655b237; test SHA-256 fc3de0236f504fdf138de832254495d6d6735b24702742998ebd20c98f00db74. Both files were independently hashed after preparation. Device acceptance remains separate.
deployment: not_applicable
deployment_evidence: No provider, host, credential or deployment action is included.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PROXY-DUPLEX | PRX-1792849486028429 | Real proxy split-header POST delivers the exact 1 MiB body and digest; an early response reaches a backpressured uploader | Local and Linux passed; device completion pending |
| REQ-PROXY-NO-CONTINUATION-REPLAY | PRX-1792849486474789 | A classified 451 response records one failure, no alternate connection, no route advance and no success promotion | Passed |
| REQ-PROXY-RESPONSE-OBSERVATION | PRX-1792849486028429 | Partial TLS bytes and the original deadline survive handoff; a real next request observes rotation after response publication | Portable and real Linux fixed-buffer path passed |
| REQ-PROXY-DUPLEX-CLEANUP | PRX-1792849486474789 | Real sockets preserve client half-close and finish owner cancellation before client or origin fixture cleanup | Passed |

Physical acceptance, hosted Linux acceptance and rebuilt artifact evidence map
to `PRX-1792849486914254`. The artifact and Linux runtime gate passed; complete device acceptance and
final candidate hosted checks remain required.

## Local checks

- `build-gate -- cargo test --locked -p ripdpi-proxy-runtime --jobs 2`:
  245 unit and 71 integration tests passed, zero failed. Thirteen existing
  optional interoperability, load and soak tests were ignored.
- `build-gate -- cargo clippy --locked --workspace --all-targets --jobs 2 -- -D warnings`:
  passed.
- Android `cargo check --tests --features io-uring` for
  `aarch64-linux-android`: passed. This is compile evidence only.
- Format, unsafe-boundary and architecture checks passed. The architecture
  check found no new or worsened violation. Task validation passed with
  156 tasks and 482 steps; 25 CI command and native-graph tests passed.
- Independent review approved the final native delta, including cancellation,
  byte ownership, no replay and response publication.

## Defects reproduced and corrected

The baseline real-proxy split-header POST completed with autolearn disabled but
stalled with autolearn enabled after the origin received zero body bytes.
Direct native Hysteria completed the same upload, including a delayed body.
The corrected proxy forwards continuation before waiting for the response.

The early-response regression also found an unfinished handler after explicit
owner stop. On macOS, combined shutdown after peer FIN returned ENOTCONN and
did not release the blocked writer. Independent write-half and read-half
shutdown now release that writer. Client-finished telemetry reaches one before
fixture cleanup. Normal half-close and existing drain deadlines are unchanged.

A deterministic response-publication regression failed with the previous order
and passed after accepted response state was published before the client write.
Success is marked recorded only after the recording operation succeeds.

## Hosted Linux acceptance

Exact source `38e64afcf9f097a60c8dd9146ed431c108fe35ef` passed job
`120911596401` in run `40105712887`. The unchanged proxy implementation passed
the feature-enabled Linux suite: 254 tests, zero failures or skips. The real
`duplex_continuation_preserves_response_and_uses_fixed_buffers` case passed.
It starts a registered io_uring driver, checks exact 1 MiB upload and response
bytes, requires positive WRITE_FIXED completions and rejects copy fallback.
EPERM, ENOSYS and failed registration cannot pass this case.

The same job passed 5,880 workspace tests with 61 profile skips, 46 network E2E
cases with seven profile skips, and the 16 selected deterministic network
cases. These results are source-bound; final service changes require their
own hosted and physical acceptance.
