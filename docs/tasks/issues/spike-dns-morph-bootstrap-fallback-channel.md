---
id: TRN-1786264762917575
title: "Spike: DNS-Morph bootstrap as fallback bootstrap channel"
kind: feature
status: dropped
area: transport
priority: medium
owner: unassigned
parent: EPC-1786264762917282
blocked_by: []
spec_mode: required
openspec_change: trn-1786264762917575-spike-dns-morph-bootstrap-fallback-channel
created: 2026-05-22
updated: 2026-08-09
source_wiki_pages:
  - dns-morph-bootstrap
linked_task: null
status_detail: externally-gated — requires a separately operated bridge and measured network vantage
closed_at: "2026-08-09T11:12:17Z"
closed_reason: research concluded with a no-go verdict
evidence_summary: DNS payload morphing lacks the required bridge, fixture, and owned field vantage; the repository should not build the proposed capability.
---

## Motivation

DNS-Morph (Ailabouni-Dunkelman-Bitan, CSCML 2021) splits the transport model: the handshake uses DNS port 53 while the data plane uses any underlying transport. This provides a distinct bootstrap surface whose behavior depends on middlebox port-53 handling and active L7 fingerprinting. No mature Android-targeting fork exists yet. The spike validates whether the bootstrap shim is buildable on Android and whether controlled external clients can complete the roughly 80-query type-A handshake on representative resolver paths.

> [!warning] LOW dedup confidence
> Adjacent existing surface: `ripdpi-dns-resolver` crate (resolver, not handshake bootstrap). The old DoH arbitrary-payload tunnel task was dropped as obsolete task-board planning, so this spike should compare only against current resolver and bootstrap code before merging.

## Proposed change

Stand up DNS-Morph bootstrap as a fallback bootstrap channel in RIPDPI Android:

1. New Rust crate `ripdpi-dns-morph` under `native/rust/crates/` implementing the DNS-Morph client: base32-encoded type-A query fragments (20–50 chars), A+CNAME response demux, selective-repeat reliability upstream, stop-and-wait downstream.
2. Bootstrap orchestrator integration: when primary transport bootstrap fails, fall back to DNS-Morph to exchange handshake bytes with the bridge, then switch data plane to VLESS+Reality (or pre-configured transport) on a separate port.
3. JNI/Kotlin layer: expose DNS-Morph status (bootstrap in progress / OK / failed) to the UI diagnostic view.

### Linked deploy task

`linked_task:` points to the sibling deploy task that stands up the DNS-Morph bridge server. Both must ship together — this client task is gated on the bridge being reachable from RU-ASN.

## Acceptance criteria

- [ ] `ripdpi-dns-morph` crate compiles for all 4 Android ABIs.
- [ ] Bootstrap completes against a synthetic DNS-Morph bridge in `test-lab/dns/` scenario (~3–8 s end-to-end per paper).
- [ ] Protocol-behavior validation confirms that querying the bridge with `dig @bridge www.example.com` returns normal DNS responses.
- [ ] Integration test in `core/diagnostics-data/` covers bootstrap → primary-transport handoff.
- [ ] LOW-confidence dedup explicitly resolved in PR description: confirmed NOT a duplicate of `ripdpi-dns-resolver` or any current bootstrap transport code.

## Risks / open questions

- Bootstrap latency (~3–8 s) is acceptable as fallback, painful as primary path.
- Managed resolver policies may redirect outbound port-53 queries — bridge reachability and resolver-routing topology are open questions for the linked deploy task.
- Paper-based reference code targets Tor pluggable transports; re-targeting cost is part of the spike.

## References

- dns-morph-bootstrap — wiki concept page with mechanism + threat-model comparison
- censorship-update-net4people-2026-05-22 — net4people #619 source
- Linked deploy task: `add-dns-morph-bridge-ansible-role`

## Feasibility note — DNS-Morph bootstrap fallback (2026-06-11)

> **Design spike** per `epic-transport-obfuscation-research` — no production code is merged. The acceptance criteria above describe an imagined `ripdpi-dns-morph` crate and are NOT checked off. This note is the deliverable; the verdict is externally-gated (see Go / No-Go).

### (a) Dedup resolution — DNS-Morph is NOT a duplicate of the DNS crates

Resolves the task's self-declared "LOW dedup confidence" as **confirmed distinct** on a precise boundary:

- **`ripdpi-dns-resolver` is a name→IP resolver, full stop.** Its public surface (`native/rust/crates/ripdpi-dns-resolver/src/lib.rs`) re-exports `EncryptedDnsResolver`, `DohResolverPipeline`, `extract_ip_answers`, `OdohEncryptedQuery`, `HttpsRr`/SVCB parsing — every type answers "what IP is `example.com`" over DoH/DoT/DNSCrypt/DoQ/ODoH. It is `#![forbid(unsafe_code)]` and issues *real, well-formed* queries.
- **DNS-Morph is a bootstrap byte channel that uses DNS framing** — base32-encoded opaque *handshake bytes* in the QNAME of type-A queries, A+CNAME response demux, selective-repeat-up / stop-and-wait-down reliability. The QNAME is a transport frame, not a hostname; there is no IP-answer semantics.
- **`ripdpi-ech-dns` / `ripdpi-runtime-dns-cache` / `ripdpi-diagnostics-dns`** are ECH-config retrieval, resolution caching, and DNS diagnostics — all on top of name→IP semantics. A reliability layer over DNS frames has no analogue anywhere.

**Boundary statement (satisfies acceptance criterion "LOW-confidence dedup explicitly resolved"):** DNS-Morph is a handshake-bootstrap *byte channel* that uses DNS message framing as a transport envelope; `ripdpi-dns-resolver` and siblings are *name-resolution* services producing IP/SVCB answers. They share the wire format (UDP/53 + DNS records) but nothing of the semantics, reliability layer, or call site (resolver feeds the connect path; DNS-Morph feeds the bootstrap-orchestrator fallback path). `grep -rliE 'dns.?morph' native/rust/ app/ core/ test-lab/` returns nothing — genuinely greenfield, confirmed not a duplicate. (At graduation, evaluate whether a thin shared DNS message codec is factorable — but the resolver's parsing is answer-oriented, so treat that as a separate refactor decision, not an assumption.)

### (b) Android feasibility

- **4-ABI buildability is the cheapest bar, not the risk.** A pure-Rust client (base32 codec + `UdpSocket` + reliability state machine) is ordinary `tokio`/`std` networking with no native-toolchain blockers.
- **`VpnService.protect()` on the :53 socket is already solved — no new plumbing.** The reusable helper exists: `ripdpi-ws-tunnel/src/protect.rs` exposes `protect_socket<T: AsRawFd>(&socket, path)`, and `ripdpi-tunnel-core/src/session/protect.rs` shows the dual path (JNI callback via `ripdpi-runtime-platform`, or `ripdpi_privileged_ops::protect_socket`). `ripdpi-dns-resolver/src/resolver/doq.rs` already `UdpSocket::bind(...)`s for DoQ, so the protect-before-use pattern for a UDP/53 socket is established in-tree. A DNS-Morph crate must `protect_socket(&udp_sock, …)` before the first `send_to`, fail-closed on error, per `vpnservice-protect-invariant.md` — inventing no mechanism.
- **The load-bearing risk is reachability, not the socket.** Managed resolver paths can transparently redirect outbound :53 to recursive resolvers, which answer *real* hostnames but will not necessarily relay nonstandard QNAME payloads to an arbitrary upstream bridge — they terminate DNS rather than forward opaque UDP/53. If :53 is intercepted or rewritten, the handshake never reaches the bridge regardless of client correctness. **This is not answerable from the client side** — it needs a measurement from a representative external vantage against a live bridge.

### (c) Dependency structure — hard external gate

The task body names a sibling deploy task `add-dns-morph-bridge-ansible-role` that must stand up a bridge reachable from a representative external vantage, and states both must ship together. Acceptance compounds it: the criteria require a synthetic bridge in `test-lab/dns/`, which today holds only CoreDNS artifacts (`Corefile.emulator`, `Corefile.device.template`, `zones/`) — **no morph scenario**. Both the production bridge and the synthetic test bridge are prerequisites that do not exist; the client cannot be validated without the synthetic bridge, nor justified without the real one plus a port-53 reachability measurement.

## Go / No-Go (2026-06-11)

**Verdict: EXTERNALLY-GATED.** The technique is sound, the in-tree cost is modest, and it clears the cheap bars (not a duplicate; trivially buildable; protect invariant already satisfiable). But its claimed advantage depends on resolver-path behavior that the client *cannot verify on its own*, and active L7 fingerprinting can reject high-entropy base32 QNAMEs. Building `ripdpi-dns-morph` now would yield a buildable, protect-correct, but **entirely unverifiable** artifact — the worst kind of speculative surface to carry. Park the note; build nothing.

**What must be true before graduation (in order):**
1. Sibling deploy task `add-dns-morph-bridge-ansible-role` stands up a bridge with normal-query compatibility (`dig @bridge www.example.com` returns normal DNS), reachable from a representative external vantage.
2. A measurement from that vantage proves outbound :53 reaches the bridge with base32 QNAMEs intact under the tested resolver routing. **If :53 is transparently terminated, the verdict flips to NO-GO.**
3. A throwaway synthetic bridge lands in `test-lab/dns/` so the client is CI-exercisable without the production bridge.

**Graduation target.** Re-files under `epic-transport-obfuscation-research`. Minimal first slice once gated conditions clear: a `ripdpi-dns-morph` crate, **upstream-only** (base32 type-A encoder + selective-repeat sender + `protect_socket`-guarded `UdpSocket`) tested against the synthetic bridge for one round-trip; defer A+CNAME downstream demux, orchestrator wiring, and JNI status to later slices. Strictly a *fallback* bootstrap (~3–8 s latency acceptable as fallback, painful as primary), never on the primary connect path.

## Work log

- 2026-06-05: No implementation started — `ripdpi-dns-morph` crate does not exist, no dns-morph references anywhere in native/rust/crates/, app/, or core/; test-lab/dns/ exists but contains no dns-morph scenario; all acceptance criteria unmet.
- 2026-06-11 (design spike, externally-gated): Delivered the feasibility note above. Resolved the LOW-confidence dedup (confirmed distinct from `ripdpi-dns-resolver` and siblings — byte-channel vs name-resolution). Confirmed 4-ABI buildability and that the `protect_socket` helper already covers the :53 socket. Central finding: the technique depends on resolver-path behavior the client cannot measure, plus a non-existent externally reachable bridge and synthetic test bridge. No code merged; status → `blocked` (externally-gated) pending bridge stand-up and a :53 reachability measurement.
