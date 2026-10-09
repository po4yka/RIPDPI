---
task_id: TST-1791477382746187
change: local-vpn-acceptance-lab
commit_sha: 6bdecf45b824af35a6f771a2b71ee4ba6f3c3234
local: blocked
local_evidence: 79 Python tests and focused source gates passed; full native matrix and engine packet runtime are not complete. See the evidence below.
remote_ci: required
remote_ci_evidence: Pending PR checks.
device: blocked
device_evidence: Real libXray AAR is absent. Its build exhausted host APFS and caused guest I/O errors. The VM recovered but available space remains below the 10 GiB preparation threshold.
artifact: passed
artifact_evidence: The combined CLI passed nine routed scenarios and revalidated the saved report and artifact hashes. Private local report is combined-routed-final/report.json in the task VM evidence directory.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LAB-CATALOG | TST-1791478009222457 | Generated catalog validates 42 scenarios and six profiles; drift check passes | passed |
| REQ-LAB-EVIDENCE | TST-1791478009222457 | 38 contract/runner tests; real report hash verification; timeout stays failed | passed |
| REQ-LAB-ROUTER | TST-1791478014844231 | Nine real ARM64 Linux scenarios through the combined runner | passed |
| REQ-LAB-ANDROID | TST-1791478024148220 | Kotlin source compile/KtLint and eight adapter tests pass; both real invocations report blocked without libXray | blocked |
| REQ-LAB-PEERS | TST-1791478027241747 | 30 executable adapters; 12 regression tests; Hysteria independent payload/auth/TLS and three selected native cases pass; full native matrix pending | blocked |
| REQ-LAB-ISOLATION | TST-1791478014844231 | 21 VM tests; observed cancellation cleanup; 8/8 real VM forwarding controls | passed |
| REQ-LAB-AUTOMATION | TST-1791478029994870 | Local actionlint/catalog checks pass; remote CI pending | blocked |

## Observed local checks

- Python: 38 coordinator, 21 VM, 12 peer, and 8 Android tests passed.
- `python3 test-lab/acceptance/generate_manifest.py --check` passed.
- `python3 test-lab/acceptance/lab.py validate` passed: 42 scenarios, six profiles.
- `ruff check test-lab/acceptance scripts/ci/run-android-local-acceptance.py scripts/tests/test_android_local_acceptance.py` passed.
- `actionlint .github/workflows/local-acceptance.yml .github/workflows/ci.yml` passed.
- `go test -mod=readonly ./...` in the Xray fixture passed on the combined tree.
- `./gradlew :app:compileGithubFullDebugAndroidTestKotlin :app:ktlintAndroidTestSourceSetCheck -Pripdpi.skipNativeBuild=true` passed on the combined tree. This proves source validity only.
- Workspace `cargo clippy --locked --workspace --no-deps --all-targets -- -D warnings` passed in the peer worktree before integration. The new Rust test is unchanged.
- Combined architecture health: zero new or worsened indicators. Locked Cargo metadata, task contracts, strict OpenSpec validation, and commit hooks passed.
- Independent code review has no required fixes. Structured branch review reported no blocking findings. Neither review is runtime evidence.

## Runtime evidence and limits

The final routed run uses the code commit above with a clean, unchanged checkout.
All nine selected scenarios passed: baseline/drop/recovery, UDP block, established
TCP blackhole, delay, loss, reorder, MTU blackhole, IPv6 block, and topology packet
fidelity. Report revalidation passed. This does not prove engine packet mutations.

A three-second timeout experiment reported failure and confirmed remote cleanup;
no owned namespaces remained. An earlier timeout could not confirm cancellation
before the remote runner completed; it remained failed and later cleanup was
observed. Cancellation diagnostics are now retained in a private log.

Independent Hysteria TCP/UDP, bad-auth rejection, and untrusted-issuer rejection
passed in the peer worktree. Native Hysteria, TUIC TCP, and Naive TCP cases also
passed. These are selected cases, not the full native profile. Payload/auth cases
use explicit local insecure TLS; the separate TLS case tests issuer rejection.

Both combined Android scenarios reported blocked with zero passing tests because
the real libXray artifact was absent. The Docker AAR build and Linux packet-engine
build hit host ENOSPC and guest I/O errors. Only task-owned disposable outputs
were removed. The VM was recovered. Do not close device or packet-engine gates
from Kotlin compilation, profile import, mock fixtures, or emulator NAT captures.

The task remains in review and the PR remains draft. Required remaining evidence:
real Android TUN/Xray execution, the full native profile, Linux engine packet smoke,
and remote CI. External-provider and physical-device release gates are unchanged.
