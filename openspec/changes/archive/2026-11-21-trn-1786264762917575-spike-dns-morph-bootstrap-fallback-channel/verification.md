---
task_id: TRN-1786264762917575
change: trn-1786264762917575-spike-dns-morph-bootstrap-fallback-channel
commit_sha: ca9030dfe9bc7d03dc3c163aef89a1e2b8089c19
local: passed
local_evidence: On the tree of 80df46688 (macOS, Rust 1.100.0) cargo test --locked -p ripdpi-dns-morph passed (5 unit, 13 loopback tests), -p ripdpi-android-platform-adapter passed (20 tests), cargo clippy --locked -D warnings for ripdpi-dns-morph, ripdpi-android-platform-adapter and ripdpi-android passed, cargo deny --locked check passed (advisories, bans, licenses, sources ok); on e1f9b79a7 :core:data:runtime-state, :core:data, :core:engine, :core:service testDebugUnitTest and :app:testGithubDebugUnitTest passed with 0 failures, and DnsMorphBridgeTest and SubscriptionRefreshDnsMorphTest passed again on 80df46688; just ci-gates and the unsafe-boundary guard passed.
remote_ci: passed
remote_ci_evidence: CI run 44882985822 on main ca9030dfe (contains 80df46688) concluded success with 35 passing and 31 skipped jobs, including gradle-static-analysis, kotlin-coverage, rust-native-x86_64, rust-native-packaging for arm64-v8a, armeabi-v7a and x86, and build-android-debug for github, play and fdroid. rust-lint was skipped there by path filter; it passed in CI run 44875595293 on 120ee048a, which also contains 80df46688. The rust-lint failure in run 44819390818 on 80df46688 was the ripdpi-xhttp drop-order guard, outside this change.
device: not_applicable
device_evidence: No DNS-Morph v2 bridge is deployed; a device check needs a bridge built from Responder reachable on UDP/53, a bundle that declares it, and a refresh with the HTTPS endpoints blocked.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow; the bridge side is deploy-repository work.
---

# Verification

Local gates and exact-tree CI passed (see the frontmatter). No live bridge exists, so no device or network run was possible; the loopback bridge speaks the documented wire format through the same Responder a bridge build would use.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1786264762917575-001 | TRN-1786264762919094 | CI run 44882985822: rust-native-x86_64 and rust-native-packaging for arm64-v8a, armeabi-v7a and x86 built libripdpi.so with the crate; jni_facade_exports_stable_native_entrypoints and the jni-symbols.baseline test list the jniFetch export. | passed |
| REQ-TRN-1786264762917575-002 | TRN-1786264762919702 | tests/loopback.rs: delivers_a_sealed_payload_across_chunks (no 16-byte payload run on the wire, socket protected), each_subscriber_receives_only_its_own_payload, an_unknown_token_gets_no_answer, rejects_a_tampered_chunk, rejects_a_payload_signed_by_another_key, rejects_an_oversized_datagram, rejects_an_oversized_envelope_before_reading_it, a_protect_failure_sends_nothing; unit tests cover the freshness window, low-order keys, zone binding and token checks. | passed |
| REQ-TRN-1786264762917575-003 | TRN-1786264762919740 | leaves_ordinary_queries_to_the_forwarder: an A query for another name gets no DNS-Morph answer. | passed |
| REQ-TRN-1786264762917575-004 | TRN-1786264762919131 | SubscriptionRefreshDnsMorphTest: a bundle with ripdpi.dns_morph is imported, the next HTTPS refresh fails and the bridge-delivered bundle is installed; a failed bridge keeps the HTTPS failure and members; an undeclared bridge is never contacted. DnsMorphBridgeTest covers the parser. | passed |
| REQ-TRN-1786264762917575-005 | TRN-1786264762919009 | The task note's dedup section: DNS-Morph is a delivery byte channel; ripdpi-dns-morph shares no API with ripdpi-dns-resolver and uses hickory-proto only for message encoding. | passed |
