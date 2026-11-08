---
task_id: VPN-1794140727483999
change: repair-vpn02-tcp-stack
commit_sha: null
local: required
local_evidence: Pending regression tests, Rust quality checks and source gates.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is required or manually launched by this native repair task.
device: not_applicable
device_evidence: Native host regressions are the acceptance scope. Android transport acceptance remains separate and is not claimed.
artifact: not_applicable
artifact_evidence: No release binary or golden fixture is changed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN02-HALF-CLOSE | VPN-1794140849743430 | F1 commit 28a0de38a (integrated as 2a5fa531d): 380 unit plus full wire suites passed; close regressions include both FIN orders,17/10000-byte tails,6s writer,11s nativeRX,65-session batch and cancellation. Upstream195 TCP tests per family passed. Independent PR and async reviews clean; clippy/format/hooks passed. | passed (native host) |
| REQ-VPN02-IDLE-CLEANUP | VPN-1794140852477934 | F2 commit faaccad6d6 (integrated as a8cf3876a): 383 unit and all wire suites passed. Regressions cover idle expiry after EOF without another app packet, completion wake with a retained TIME-WAIT socket, coalesced wake over 65 entries, native RX after the first batch, and late admission. Independent PR and async reviews found no remaining defect; normal hooks passed. | passed (native host) |
| REQ-VPN02-INTERFACE-ENTROPY | VPN-1794140854875280 | F3 commit c36c240db (integrated as a8c848e16): tun_entropy RED repeated ISN4260165794, GREEN 1 passed; independent review and pre-commit clippy/format/architecture checks passed. OS entropy error is propagated before ready; no injection seam added. | passed (native host) |
| REQ-VPN02-BUFFER-POLICY | VPN-1794140858454513 | F4 commit a00d1d907: tcp_buffers RED65535 vs1, GREEN374 unit +1 wire passed; RX/TX capacity includes1,1024,65536,16777216; independent review and pre-commit hooks passed. | passed (native host) |
| REQ-VPN02-IPV6-MSS | VPN-1794140863320088 | F5 commit 298cd6f4a (integrated as 712298b04): full packet RED IPv6 536 vs1220, GREEN2; upstream TCP suites192 per family; affected core/WARP545 passed; affected clippy, independent review and hooks passed. | passed (native host) |

Combined validation step: VPN-1794140869499117. Independent review must precede each repair commit. The final exact SHA and commands will be recorded after integration validation.
