## Context

Task TRN-1794158969509887 repairs STD-02 F1-F3 and checks H1. Base source is 145387cae23bd1e8d39ac35eda3f9f016d0ebee8. The common browser builder is shared by HTTP and non-HTTP clients. Boring 5.3.0 accepts names that cannot form RFC 6066 host_name values. Profile catalog changes are not needed.

## Goals / Non-Goals

- Goal: use each existing transport's ALPN and a validated TLS identity before application data.
- Goal: keep independent real-TLS regression evidence and separate fix commits.
- Non-goal: change direct raw REALITY camouflage, add Unicode override support, change browser fingerprints or ECH policy, or use external peers and credentials.

## Decisions

- DoT sets dot after either builder factory; selected dot and legacy None are accepted.
- MASQUE H2 and plain xHTTP offer only h2 and check the result before Hyper. A dedicated REALITY H2 entry uses the same authenticated inner routine with h2 policy; existing direct entries stay intact.
- A small normalize_server_name helper in tls-profiles uses current dependencies. It strips one DNS root dot, validates ASCII LDH label and total lengths, canonicalizes bare IP and unwraps valid bracketed IPv6. It rejects raw Unicode overrides. URL parser IDNA remains available for URL domains.
- TLS identity and HTTP authority remain separate. Existing formatting helpers preserve IPv6 brackets. Fake-SNI callers keep certificate identity separate.
- HTTPS root-dot normalization applies to TLS names only. Fetch, WARP and MASQUE keep the parsed URL host spelling for HTTP Host or authority; WebTunnel already keeps those fields separate.
- Diagnostic fallback is used only for empty input. A nonempty invalid override is an error. The owning public helper regression must prove the previous substitution first.
- Profile validation checks each ALPN identifier and the encoded list size before the builder encodes their lengths.
- No production dependency or test-only production trust seam is added. Test peers use synthetic credentials and local trust anchors.
- The dedicated local-network-fixture xHTTP REALITY peer selects h2. The generic REALITY peer keeps its existing behavior.

## Contracts and ownership

- Kotlin and JNI payload shapes do not change; native final validation protects all current ingress routes.
- Rust crates: android-support, android-fetch-adapter, tls-profiles, dns-resolver, masque, xhttp, vless, anytls, trojan, webtunnel, ws-tunnel, warp-android, monitor-engine, relay-core and diagnostics-tls. Other common-builder callers were checked for missed names.
- Ownership and serialized file lanes are recorded in the portfolio task. Primary owns Cargo.lock, root manifests, API snapshots, OpenSpec and task state; workers own the named source slices in separate worktrees. SNI changes in ALPN-owned production files follow ALPN lane completion.
- The new Rust helper and InvalidServerName error variant require a generated tls-profiles API snapshot. The variant adds an exhaustive-match case to the internal workspace API. No protobuf, storage, locale, golden or dependency-version migration.

## Risks / Trade-offs

- Invalid names may have appeared to work against permissive peers. Refuse them before malformed SNI and retain valid A-label/IP behavior.
- Legacy DoT may omit ALPN; preserve it explicitly. A mismatched selected protocol never authorizes DNS.
- REALITY policy can affect peer compatibility; only xHTTP uses the H2 entry. Preserve direct behavior with an authenticated fixture.
- Loopback evidence does not prove Android routing or external compatibility. Keep these claims separate.

## Migration Plan

No persisted data migration. Review each atomic source change before commit, run affected crate tests and clippy, then run just ci-gates and cargo metadata --locked on the combined tree. Before integration fetch and rebase origin/main; recheck affected inputs if upstream changed. Fast-forward main and push origin/main only after gates. Rollback is a revert of the affected fix commit, preserving unrelated work.
