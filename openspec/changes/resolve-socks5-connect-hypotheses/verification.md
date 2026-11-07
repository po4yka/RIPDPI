---
task_id: PRX-1794074402219640
change: resolve-socks5-connect-hypotheses
commit_sha: null
local: required
local_evidence: Local reproduction exists; fixes and combined validation are pending.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not claimed; required local gates run before main integration.
device: not_applicable
device_evidence: Local protocol and socket-lifecycle proof only; no physical device acceptance claim.
artifact: not_applicable
artifact_evidence: No shipped binary or golden artifact is updated.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-S5-STOP | PRX-1794074650961589 | local late-auth and reply cancellation wire probes | pending |
| REQ-S5-TIMEOUT | PRX-1794074651536325 | independent error-to-wire reply test and mapper test | pending |
| REQ-S5-UPSTREAM-REP | PRX-1794074652008503 | upstream REP01..08 loopback wire matrix | pending |
| REQ-S5-ABSOLUTE-NAME | PRX-1794074652460822 | a. and abc.test. through local DoH and TCP origin | pending |

## Hypothesis investigation

Execution step PRX-1794074653029150 records all four audit hypotheses and exact local limitations. H1 upstream auth/reply sends CONNECT after terminal stop; controlled DNS closes before return, and an unprivileged macOS backlog cannot reproduce pending SYN. H2 chained BND is a contract choice, not a confirmed defect. H3 has false TTL classification and independently observed upstream REP loss. H4 absolute FQDN support is broken at two checks; localhost family fallback is not promised and stays unchanged.
