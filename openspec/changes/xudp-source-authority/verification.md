---
task_id: TRN-1792853642811403
change: xudp-source-authority
commit_sha: 38e64afcf9f097a60c8dd9146ed431c108fe35ef
local: passed
local_evidence: All 128 VLESS unit tests passed, with two existing owner-profile integration tests ignored. Workspace all-target Clippy, format, architecture and API checks passed. Independent review approved the final implementation and cancellation boundaries. The integrated native tree matches reviewed author commit 189d39d1470b66d92ed4f40b4ea828357eae2412.
remote_ci: required
remote_ci_evidence: Hosted checks on corrected source remain required.
device: required
device_evidence: Two physical Hysteria runs at 38e64afcf9f097a60c8dd9146ed431c108fe35ef failed during the sleep/wake stream after transport-switch restarts. Both passed initial download and upload and cleanup. Neither reached reserve DNS or explicit UDP recovery. The earlier MissingSourceTarget failure belongs to source 04d842534bdb550a9f5477c3e528b68ee3f6ab3a.
artifact: passed
artifact_evidence: Genuine arm64-v8a preparation passed at clean source 38e64afcf9f097a60c8dd9146ed431c108fe35ef. Independently verified app SHA-256 ec4c295d482e152dbb56044c244e93b1a8ba9d25bff47c02ede6f4c2d9c20079 and test SHA-256 f53747dabdd8da79e5d8783933748fa8641895ad159d2bcaa7957bf13eeee426.
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
| REQ-XUDP-SOURCE-LIFECYCLE | TRN-1792853831345454 | Genuine APK, physical reserve DNS and explicit recovery with hosted native gates | Required |

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
