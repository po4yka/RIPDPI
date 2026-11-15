---
task_id: RLY-1794757174777852
change: correct-aux-02-relay-clock
commit_sha: 00dea9a59d461a2a8c42a8512c3ae4d6c36203a2
local: passed
local_evidence: 155 owning-crate tests, 14 relay runtime Shadowsocks tests, two vendored timestamp tests, four pinned Mieru loopback checks, Android arm64 clock compilation, independent review, and source gates passed.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: "The Android clock path compiles for arm64. Physical suspend, resume, and wall-clock changes remain unverified."
artifact: not_applicable
artifact_evidence: "The change has no APK packaging or release acceptance step."
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

The runtime tests and complete source gates ran on 5f174d76aa7bd0ea23e7154aa8ce0d316f4e8a36.
The later rebase included changes outside the tested crates, their resolved
dependencies, and the native API snapshot inputs. The affected source gates
and task validation ran again on the integration tree.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AUX-02-TRUST | RLY-1794757293376047 | Factory isolation, cancelled or refused Mieru replies, successful SOCKS admission, and request-salt binding tests | Passed |
| REQ-AUX-02-RECOVERY | RLY-1794757293376047 | Fresh factory after shutdown and pinned Mieru SOCKS runtime stop test | Passed |
| REQ-AUX-02-REPLAY | RLY-1794757295604895 | Pinned UDP association timeline through backward correction, two-window retirement, identity, and duplicate tests | Passed |
| REQ-AUX-02-CLOCK | RLY-1794757297715722 | Delayed and equal-tick observations, frozen monotonic read, integer bounds, Android arm64 compile, and pinned Go codec checks | Passed locally |
