---
task_id: TRN-1792853642811403
change: xudp-source-authority
commit_sha: b3bdf4263f934c6f8f899e3ca6967010c872f516
local: passed
local_evidence: All 128 VLESS unit tests passed, with two existing owner-profile integration tests ignored. Workspace all-target Clippy, format, architecture and API checks passed. Independent review approved the final implementation and cancellation boundaries. The integrated native tree matches reviewed author commit 189d39d1470b66d92ed4f40b4ea828357eae2412.
remote_ci: passed
remote_ci_evidence: Landing commit b3bdf4263f934c6f8f899e3ca6967010c872f516, whose message records published source gates at 90ae34b62 and physical candidate 47a5ee708 passing all four owned USB scenarios, passed full push CI run 40122030612: ci-required succeeded with 48 jobs passed and 16 route skips, including rust-workspace-tests, rust-lint, gradle-static-analysis, architecture-health, native packaging for three Android ABIs, six release-verification variants and instrumented tests on API 27, 33, 35, 36 and 37. Current main 32a9b2c8aa0a52945cb9f6c5e38c43f8194a30c3 also passed full push CI run 41170842447 (47 jobs passed, ci-required succeeded).
device: passed
device_evidence: All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. After automatic TCP reserve selection, DNS completed with a new owned peer receipt, a separate-UID 1 MiB download passed, and explicit UDP recovery passed.
artifact: passed
artifact_evidence: Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
deployment: not_applicable
deployment_evidence: No provider, host, credential or deployment action is included.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-XUDP-INITIAL-SOURCE | TRN-1792853830474026 | Immutable initial authority is published with accepted NEW-frame admission | Passed |
| REQ-XUDP-SOURCE-ISOLATION | TRN-1792853830914890 | Initial A, later uplink B, explicit response C and omitted response A real-stream regression | Passed |
| REQ-XUDP-SOURCE-LIFECYCLE | TRN-1792853830914890 | Queue cancellation, immediate reply, decode-time publication and fresh-carrier isolation regressions | Passed |
| REQ-XUDP-SOURCE-LIFECYCLE | TRN-1792853831345454 | Genuine APK, physical reserve DNS and explicit recovery with hosted native gates | Passed |

The earlier baseline at 04d842534bdb550a9f5477c3e528b68ee3f6ab3a retained two DNS receipts and four completed transfers.
The client log reports an omitted source target immediately after its reserve
XUDP handshake. No fixture rule, assertion or timeout was changed.

## Local regression evidence

Four of the first five new regressions failed before the correction. The
failures reported missing authority or incorrectly reused an explicit reply
source. Six final tests cover real stream replies, immutable authority,
invalid requests, cancellation before and after admission, and fresh carriers.
The decode-time test publishes authority after the reader starts waiting.

`build-gate -- cargo test --locked -p ripdpi-vless --jobs 2 --quiet`
passed with 128 unit tests and no failed or ignored unit tests. Two existing
integration tests require owner profiles and remain ignored. Full workspace
Clippy passed with warnings denied. The normal commit hooks passed.

## Final physical candidate 47a5ee70

All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. After automatic TCP reserve selection, DNS completed with a new owned peer receipt, a separate-UID 1 MiB download passed, and explicit UDP recovery passed.

Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.

## Hosted acceptance of the landing commit

Landing commit b3bdf4263f934c6f8f899e3ca6967010c872f516, whose message records published source gates at 90ae34b62 and physical candidate 47a5ee708 passing all four owned USB scenarios, passed full push CI run 40122030612: ci-required succeeded with 48 jobs passed and 16 route skips, including rust-workspace-tests, rust-lint, gradle-static-analysis, architecture-health, native packaging for three Android ABIs, six release-verification variants and instrumented tests on API 27, 33, 35, 36 and 37. Current main 32a9b2c8aa0a52945cb9f6c5e38c43f8194a30c3 also passed full push CI run 41170842447 (47 jobs passed, ci-required succeeded).
