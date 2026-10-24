---
task_id: PRX-1792849175606125
change: first-response-duplex-continuation
commit_sha: 47a5ee708a497086eb29739ca17f35e164119846
local: passed
local_evidence: Complete proxy-runtime tests passed with 316 tests and 13 existing opt-in ignored tests. Workspace all-target Clippy with warnings denied passed. Android compilation with tests and io-uring enabled passed. The integrated native tree is byte-identical to reviewed commit 35458266a232b4e4d4ae4a31fa3f0459d2539639.
remote_ci: required
remote_ci_evidence: Final published candidate hosted gates remain required. Earlier source 38e64afcf9f097a60c8dd9146ed431c108fe35ef passed 56 checks with 19 planned skips; its Linux io_uring job 120911596401 passed 254 tests with no skips, positive WRITE_FIXED completions and zero fallback.
device: passed
device_evidence: All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Hysteria completed the previously failing 1 MiB upload in 604 ms, a 3997696-byte 60.002-second sleep/wake stream, automatic TCP reserve and explicit UDP recovery.
artifact: passed
artifact_evidence: Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
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

## Final physical candidate 47a5ee70

All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Hysteria completed the previously failing 1 MiB upload in 604 ms, a 3997696-byte 60.002-second sleep/wake stream, automatic TCP reserve and explicit UDP recovery.

Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
