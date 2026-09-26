---
task_id: RST-1790427044011189
change: prevent-shared-priors-rollback
commit_sha: null
local: blocked
local_evidence: Rust shared-priors, platform adapter, runtime-strategy, and Android JNI tests passed; Android unit tests could not resolve the missing libxray.aar.
remote_ci: blocked
remote_ci_evidence: No push or hosted CI run is authorized for this local change.
device: blocked
device_evidence: Android device verification has not run.
artifact: blocked
artifact_evidence: Rust Android JNI build passed; Android classpath requires native/xray/artifacts/libxray.aar, which is absent locally.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SHARED-PRIORS-ORDER | RST-1790427252517600 | `cargo test -p ripdpi-shared-priors --locked`: older and conflicting signed bundles rejected | passed |
| REQ-SHARED-PRIORS-DURABLE | RST-1790427252517600 | Restart and marker write-failure tests passed; marker is synced before registry publish | passed |
| REQ-SHARED-PRIORS-ANDROID | RST-1790427259666136 | `cargo test -p ripdpi-android --locked` passed; Kotlin unit gate blocked by missing libxray.aar | blocked |
| REQ-SHARED-PRIORS-COMPAT | RST-1790427252517600 | First apply, idempotent apply, and malformed-marker tests passed | passed |
