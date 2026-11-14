---
task_id: RST-1794677647125288
change: repair-dpi26-contracts
commit_sha: 4b4e6be7703f35b0744570bdc9e19ee9201f9624
local: passed
local_evidence: Final rebased host 439, Linux 8, Clippy and just ci-gates passed. Kotlin 43, staticAnalysis, affected diagnostics quality and fmt passed before the final rebase; their repair inputs did not change. Red regressions were observed before repair.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is outside local repair acceptance; no hosted CI result is claimed.
device: not_applicable
device_evidence: Root, physical and external-network probes are not authorized; privileged transport remains unverified.
artifact: passed
artifact_evidence: Exact rebased SHA 4b4e6be770 passed eight unprivileged Linux IPC/handler/phase tests and locked Cargo metadata; no public API shape changed.
deployment: not_applicable
deployment_evidence: This change does not deploy an application or service.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI26-F1 | RST-1794677761670924 | F1 commit 02ebdea81e / combined 520eab70b3: recovered host 239 and Linux 8 passed; reviewed committed diff matches checkpoint 8b36d23f. Combined host 438, Linux 8, all-targets Clippy, fmt and source gates passed | passed |
| REQ-DPI26-F2 | RST-1794677762354249 | Observed red None versus Some(Seq), then full ripdpi-proxy-config tests passed; independent review found no issue. Rebased full config suite 199 passed; logs preserved under /Users/npochaev/GitHub/RIPDPI-worktrees/dpi26-evidence-20261114/parent-dpi26-f2-*.log | passed |
| REQ-DPI26-F3 | RST-1794677763048279 | Observed red admission test, then 43 StrategyChain Kotlin tests and full staticAnalysis passed; independent review found no issue. Logs preserved as parent-dpi26-f3-{red,green}.log and parent-dpi26-static-analysis.log in the evidence directory | passed |

## Combined candidate

Exact source: 520eab70b3, on origin/main 4c64d9e8c9. Host 438, all-targets Clippy (-D warnings), fmt, just ci-gates and locked metadata passed. Earlier root-admission 43 and full staticAnalysis 827 passed; affected diagnostics quality 448 passed after rebase. Final combined Linux 8 passed after rebuilding into a persistent target; the earlier container startup failure remains in its separate log. Logs/checkpoint: /Users/npochaev/GitHub/RIPDPI-worktrees/dpi26-evidence-20261114. The reviewed and committed F1 patch hash is 8b36d23f6236d20ec2e935b6f17ab604a3c8ef3496ff0c8cff4cc5ceed211bac. Environment failures are retained separately. No root/raw/device/external probe ran. Darwin API checks intentionally skip the Linux-owned runtime-platform snapshot; public API shape is unchanged.

## Final checks and limits

Final rebase source: 4b4e6be7703f35b0744570bdc9e19ee9201f9624 on origin/main 5fb68930e84ace88e8de0052667e39ea719382fa. The repair files match the reviewed source above. New upstream TCP changes required another combined run: all six host packages passed 439 tests, with no failures, ignored or filtered tests; Linux passed eight tests, with no failures or ignored tests and 62/70/126 unrelated cases filtered. All-targets Clippy with -D warnings, just ci-gates, locked offline metadata and diff checks passed. The new upstream adapter test explains the host count increase from 438. Commands and results are in final-rebase-commands.txt and final-rebase-{host,linux,clippy,ci-gates}.log in the same evidence directory. The task board was regenerated after rebase. The archive and closure receipts retain the evidence snapshot from the time of closure.

The earlier combined Rust and source checks apply to exact commit 520eab70b3596a7b9d472689fc1a3cfdd393acd8. Combined host tests covered ripdpi-proxy-config, ripdpi-privileged-ops, ripdpi-proxy-runtime-desync-adapter, ripdpi-root-helper, ripdpi-root-helper-protocol and ripdpi-runtime-platform. Clippy used --all-targets -- -D warnings. Cargo commands used --locked --offline, Rust 1.100.0 and worktree-owned targets. Linux used UID 1000, network none, cap-drop ALL and no-new-privileges with source mounted read-only. Its eight targeted tests filtered 62/70/122 unrelated cases; none failed or were ignored. This is not a full Linux workspace test claim.

The initial host regression showed a missing typed UDP phase; the ID regression showed None instead of Some(Seq); the Kotlin regression showed non-root fragmentation was accepted. Independent review covered the original and recovered patch. Recovered F1 was tested again before integration. A Linux runner image/cache/target is local test tooling, not a production dependency.

Code scope stayed limited to the three confirmed findings. No dependency, schema version, locale set, baseline, golden or compiled native artifact was edited. Privileged wire delivery and Android protected-route acceptance remain unobserved and require separate authorization.
