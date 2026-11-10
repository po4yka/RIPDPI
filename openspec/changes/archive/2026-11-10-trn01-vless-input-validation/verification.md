---
task_id: TRN-1794335331879799
change: trn01-vless-input-validation
commit_sha: 86d350e0396646e8b9e0b7ca146db59bad91f311
local: passed
local_evidence: On the rebased source commit, VLESS 158, origin 33 and xHTTP 86 tests passed with zero failed, ignored or filtered tests. Consumer Clippy and just ci-gates passed. Independent general and async reviews passed after two rustdoc corrections. Commit hooks remained enabled.
remote_ci: not_applicable
remote_ci_evidence: Local source and host gates define acceptance for this change. Hosted CI was not run or claimed; a main push does not establish hosted acceptance.
device: not_applicable
device_evidence: Parser and origin admission changes use host regressions and loopback. Physical VPN and external-network acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No release artifact is produced or accepted by this task.
deployment: not_applicable
deployment_evidence: No deployment is owned by this task.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ADDONS | TRN-1794335473975866 | de1eea8e35; 35 independent pinned Xray protobuf vectors across request and response APIs, exact Flow values, valid unknown fields and malformed rejection. The rebased full VLESS suite passed 158 tests. | passed |
| REQ-PARTIAL | TRN-1794335473975866 | de1eea8e35; every tested header/addon split, truncated EOF, malformed-after-cancel, maximum block, sticky errors and existing lazy-uplink behavior passed. | passed |
| REQ-ORIGIN-FLOW | TRN-1794335474552676 | 86d350e039; six nonempty wire flows have no authenticated callback, target accept or success response. Empty flow with valid unknown fields relays real loopback bytes. All 33 origin tests passed. | passed |
| REQ-EAGER-CANCEL | TRN-1794335475069126 | de1eea8e35 documents consumed-prefix cancellation and discard/no-retry semantics, and directs resumable callers to ResponseHeaderStream. General and async reviewers accepted the corrected contracts. | passed |

## Observed commands

The following commands ran sequentially under one `build-gate --lane ripdpi` lease at the exact commit above. Cargo used Rust 1.98.1 and the same disposable task target; no dependency, baseline, source or lock changes were made by validation.

```sh
cargo +1.98.1 test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-vless --lib --jobs 4
cargo +1.98.1 test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-cloudflare-origin --jobs 4
cargo +1.98.1 test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-xhttp --lib --jobs 4
cargo +1.98.1 clippy --manifest-path native/rust/Cargo.toml --locked -p ripdpi-relay-core -p ripdpi-xhttp -p local-network-fixture --all-targets --no-deps --jobs 4 -- -D warnings
just ci-gates
```

| Gate | Observed result |
|---|---|
| VLESS library | 158 passed; 0 failed, ignored or filtered |
| Origin binary | 33 passed; 0 failed, ignored or filtered |
| xHTTP library | 86 passed; 0 failed, ignored or filtered |
| Consumer Clippy | Exit 0; all three selected consumers satisfy `-D warnings` |
| CI source gates | Exit 0; architecture, LoC, hotspot, contract, locale/export and host-owned API snapshot checks passed |

The unchanged vendored h3 dependency printed nine existing warnings during consumer compilation. No lint allowance, baseline or dependency was changed. The Linux-owned runtime-platform API snapshot is excluded by the existing Darwin policy; its Linux result is not claimed.

Before implementation, the audit PoC had reproduced both defects. Git comparison confirmed that wire.rs, addons.rs and origin session.rs were unchanged from that audited source to the implementation base. New pre-fix test attempts waited for the shared build lane and were stopped before Cargo; they are not reported as observed red runs. The new regressions were then executed successfully with the fixes.

The separate pinned Xray oracle used upstream commit 12ee51e4bb1d02ece4ef4b7114efa2bcdc130995 and its unchanged protobuf dependencies. It confirmed malformed lengths/tags/varints/UTF-8, valid unknown wire types and groups, wrong-type known fields as unknown, field-number bounds, noncanonical finite varints and last-wins Flow. It is not an external-server interoperability test.

General review approved F1 and F2. Async review found no runtime cancellation defect in the proposed fixes; the author corrected a test's detached-peer cancellation statement and the origin function heading. The eager helper remains intentionally non-resumable after cancellation, with an explicit contract.

The first F1 commit hook failed from ENOSPC after a prior successful Clippy run. Only task-owned disposable build outputs were removed, and the unchanged commit was retried with every hook enabled. Both fix commits then passed their hooks. No quality gate was skipped or weakened.

Temporary observations: `/tmp/trn01-combined-results.json`, `/tmp/trn01-combined-*.log`, `/private/tmp/ripdpi-trn01-decoder-green.log`, and the pinned Go oracle results. The counts and exact commands above preserve the acceptance record after those temporary files expire.
