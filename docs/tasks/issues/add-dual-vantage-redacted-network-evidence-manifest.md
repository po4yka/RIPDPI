---
title: Add a redacted dual-vantage network evidence manifest
type: task
status: doing
area: testing
priority: high
owner: Codex release evidence completion
parent: null
blocks: []
blocked_by: []
created: 2026-07-16
updated: 2026-07-22
status_detail: One serialized release lane owns all ten Android actions through source-owned selector, receipt, packet-oracle, producer-provenance, and exact-SHA physical validation
---

## Goal

Make DNS, kill-switch, and direct-window release evidence deterministic, machine-readable, privacy-safe, and correlated across both the client/device and an external observer.

## Scope

- Define and validate a versioned JSON manifest with redacted run/build/vantage metadata, observation windows, artifact digests, provenance, and explicit pass/fail/inconclusive results.
- Capture the same correlation id from client/device and external/VPS vantages; fail closed on missing, stale, mismatched, malformed, or digest-tampered evidence.
- Enforce allowlist-based redaction for credentials, keys, auth headers, raw device ids, full client IP/MAC values, and sensitive payloads.
- Feed the manifest into the existing DNS/IPv6/kill-switch and direct-window CI evaluation and artifact flow.

## Ship definition

- Regression fixtures cover schema/version drift, deterministic serialization, redaction leaks, correlation/time-window mismatch, partial evidence, digest tampering, and pass/fail cases.
- CI never treats a single-vantage or malformed capture as release evidence.
- Artifacts contain no secret or direct device/network identifier from the negative leak corpus.

## Work log

- 2026-07-27: Consolidated the remaining source actions into the serialized
  `v0.1.4` release evidence lane. Completion requires all ten selectors and
  receipts, all gate-specific packet semantics, producer-provenance review,
  and one authorized exact-SHA physical dual-vantage run before any action can
  become `productionReady`.
- 2026-07-22: Assigned one serialized wave-two lane as the sole owner of the remaining source actions: `dns-direct-ru-only-for-direct-domains`, `dns-allowlisted-bootstrap-resolution`, `dns-network-switch-behavior`, `dns-core-crash-behavior`, `dns-android-private-dns-conflict`, and `synthetic-authoritative-dns-query-sources`. No parallel writer may modify these action, receipt, or oracle contracts; the lane may not set `productionReady: true` or claim a physical PASS without producer approval and authorized dual-vantage evidence.
- 2026-07-22: Assigned the first DNS source-action wave for `dns-virtual-vpn-resolver`, `dns-proxied-through-tunnelled-resolver`, and `dns-no-isp-fallback-on-encrypted-resolver-outage`. This lane owns correlation-bound Android receipts plus fixture-transcript and gate-role-aware oracle contracts, but may not set `productionReady: true` or claim physical PASS until the exact selectors and producer provenance have been exercised on an authorized device/capture topology. The remaining six reserved DNS actions stay out of scope.
- 2026-07-22: Added the fail-closed ordinary raw-bundle preflight for the exact seven physical actions and eleven gates. The public producer now requires one canonical mode-0600 manifest, a current-source binding, distinct exact app/test APK digests, fresh redacted run/correlation identities, action-bound observation windows and vantages, a private mode-0700 artifact root, and the exact single-link mode-0600 action-receipt/packet-capture/route-snapshot inventory. It rejects output/input inode or artifact-root aliases before writing and derives only redacted provenance plus explicit per-action semantic-blocker failures; caller-authored state, summaries, and JUnit output cannot produce PASS. `SOURCE_OWNED_VERIFIER_AVAILABLE` remains false until the seven packet/route semantic oracles exist.
- 2026-07-22: Assigned the serialized writer lane for the ordinary Android raw-artifact verifier foundation. The lane owns private artifact provenance, exact action/gate inventory, source and APK binding, and fail-closed parsing mechanics; it may not authorize release PASS until source-owned semantic oracles cover all eleven ordinary gates.
- 2026-07-22: Implemented `tun-establish-native-ready-v1`. The source-owned markers conservatively bracket the complete no-ship interval from before VPN start through confirmed native readiness. The private v2 ledger binds the validated Android receipt and fixture identity; the receipt additionally binds the randomized post-ready DNS question by digest. The oracle retains private L3/L4 facts, reassembles segmented/retransmitted TCP marker streams, validates the exact NOERROR fixture answer at both NAT-independent vantages, permits only that receipt-bound DNS exchange inside the window, flags every other non-control packet, and publishes only redacted counters and digests. The action remains `productionReady: false` because no authorized physical capture or producer-provenance approval exists.
- 2026-07-22: Implemented the first fail-closed Android action receipt for `killswitch-tun-establish-native-ready`. The exact startup-window selector binds source/client/test/fixture digests, verifies distinct target/test UIDs and probe processes inside the test, and writes a redacted private atomic PASS receipt only after native readiness, exact fixture DNS, positive tunnel telemetry, `Halted`, and gate cleanup. A source-owned single-selector host runner rejects skipped, ambiguous, stale, non-private, symlinked, pre-existing, duplicate-key, or semantically tampered receipts and verifies the installed app/test APK digests before and after instrumentation. The current Pixel/Mac network state may not be changed or exercised without fresh operator authorization.
- 2026-07-22: Assigned the serialized evidence lane to the source-owned Android action workload and test-produced receipt contract. This lane must reject skipped or zero-body instrumentation, bind the exact selector and correlation markers, and must not autonomously change Wi-Fi, cellular, routes, DNS, Private DNS, VPN, or airplane mode.
- 2026-07-22: Added a source-owned dual-vantage PCAP oracle. It binds distinct raw captures to canonical producer metadata and a strict private ledger, parses Ethernet/raw-IP/SLL/SLL2 plus bounded VLAN stacks and all classic-PCAP byte-order/timestamp encodings, aligns windows by packet marker order without mixing local and remote clocks, derives counters itself, and emits redacted mode-0600 unstamped observations. Malformed, truncated, aliased, copied-single-vantage, and caller-verdict inputs fail closed; current-time freshness remains enforced by the downstream exact-source manifest validator. The generic marker seam is forbidden for every real Android dual-vantage gate, so no producer allowlist or release PASS was opened. Gate-specific Android action semantics, a test-APK provenance chain, and the ordinary raw verifier remain blocking.
- 2026-07-22: Added a GitHub-independent local release-acceptance entrypoint. It derives exact clean `HEAD`, requires local executor provenance, snapshots both inputs into private storage, and validates ordinary plus dual-vantage evidence as one complete gate inventory using the checker extracted from that exact commit. The first real PASS remains blocked by the missing source-owned Android action/oracle and ordinary raw-artifact verifiers, not by hosted-runner availability.
- 2026-07-22: The dual-vantage producer lane added a source-owned SSH/tcpdump private capture utility with peer-and-endpoint-scoped BPF, bounded remote lifetime and size, explicit remote cleanup verification, marker-based path visibility preflight, canonical private metadata, and fail-closed deletion. A live scoped capture on the P2 Tailscale interface observed the injected marker. The proposed Raspberry Pi client-underlay vantage correctly failed the same preflight because the Pixel is attached to a different wireless network that the Pi cannot observe. The utility remains outside the producer allowlist because there is still no truthful ten-scenario Android action driver, packet oracle, ordinary-results producer, or signed release-candidate APK.
- 2026-07-17: Commit `0b1eac50f276405d02f4f4cccff60f3ab54f9cad` made `networkIdSha256` independent from `vantageIdSha256`, domain-separated both hashes, rejected missing/duplicate/cross-type identities, and added executable Draft 2020-12 validation for emitted observations and manifests. The private runner config must now provide four distinct high-entropy identifiers.
- 2026-07-17: Live infrastructure audit found zero registered repository runners and no `/etc/ripdpi/network-evidence-runner.json`. A physical Android device is locally attached, but the active workflow still requires a configured runner with labels `self-hosted, linux, ripdpi-network-evidence, physical-android` plus independent client/observer hooks. No dual-vantage run or PASS artifact is claimed.
- 2026-07-16: Assigned to the serialized evidence/schema lane for the active network-evidence hardening goal.
- 2026-07-16: Added strict canonical observation/manifest validation, runner-stamped collector/vantage/APK provenance, full process-tree cleanup, exact-SHA physical-client install verification, release workflow provenance checks, and fail-closed regression coverage. No physical ADB device was attached during that implementation pass, so real capture evidence remained pending.
