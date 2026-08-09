---
id: TRN-1786264762917526
title: Investigate operator-specific protocol-class signatures (Dec 2025 shift)
kind: research
status: dropped
area: transport
priority: medium
owner: unassigned
parent: EPC-1786264762917282
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-05-22
updated: 2026-08-09
spec_reason: research-only
source_wiki_pages:
  - operator-protocol-class-blocking-shift-dec-2025
linked_task: null
status_detail: externally-gated — requires sustained measurements across multiple operator-controlled network vantages
closed_at: "2026-08-09T11:12:17Z"
closed_reason: no owned field campaign is available
evidence_summary: The methodology is complete but execution requires three independent vantages and no repository owner or runner exists.
---

## Motivation

Some network operators shifted in Dec 2025 to protocol-class fingerprint blocks (SOCKS5, VLESS, L2TP) without publishing a signature catalogue. The open question is which additional operator-specific protocol-class signatures are observable and whether the listed diagnostic tools detect them. This determines which protocols remain reliable primary transports on each measured network scope.

Child task of `epic-transport-obfuscation-research` (the former `epic-direct-mode-transport-policy-and-verdicts` was removed).

## Proposed change

Diagnostic investigation, not a feature build:

1. Run dpi-checkers + DPI Detector + protocol-block-checker against the full transport catalogue (every protocol crate under `native/rust/crates/ripdpi-<transport>/`).
2. Catalog which protocol fingerprints currently trigger blocks vs pass through, across multiple RU ISP vantages.
3. Update `operator-protocol-class-blocking-shift-dec-2025` wiki page with the empirical fingerprint catalog.
4. Feed results into `ripdpi-runtime-policy` defaults — automatically de-prioritize transports with high block rate.

## Acceptance criteria

- [ ] Empirical block-rate matrix produced for every transport in `native/rust/crates/`.
- [ ] At least 3 RU ISP vantages sampled (e.g., MTS mobile, Rostelecom home, MegaFon).
- [ ] Wiki page updated with `## Field measurement 2026-XX-XX` section.
- [ ] `ripdpi-runtime-policy` defaults adjusted (with explicit reasoning per change).

## Risks / open questions

- "Unannounced signatures" are by definition not catalogued publicly — empirical detection requires sustained testing across many protocols.
- False positives are possible: a transport may fail for reasons unrelated to the measured middlebox policy (server outage, ISP issue, certificate expiry).

## References

- operator-protocol-class-blocking-shift-dec-2025 — wiki concept page
- protocol-block-checker-methodology — diagnostic tool
- Parent epic: `epic-transport-obfuscation-research` (reassigned 2026-06-10; former `epic-direct-mode-transport-policy-and-verdicts` was removed)
- Linked deploy task: `investigate-operator-protocol-class-signatures-deploy`
- Gating dependency: cannot progress without sustained access to ≥3 RU ISP vantages (external, not a repo-side task) — see Risks.

## Design spike — measurement methodology + policy-hook design (2026-06-11)

> **Design spike** per `epic-transport-obfuscation-research`. The FIELD RUN is externally gated (no RU ISP vantages exist repo-side; see Gating dependency). No production code merges. The two repo-side deliverables — measurement methodology and `ripdpi-runtime-policy` hook design — are below; the field execution stays gated.

### (a) Measurement methodology — per-transport block-rate matrix

**Transport enumeration.** "Every transport in `native/rust/crates/`" must be made precise — there are 100+ crates and most are not transports. The probe set is the protocol crates with a wire-distinguishable client fingerprint: `ripdpi-vless`, `ripdpi-trojan`, `ripdpi-shadowsocks`, `ripdpi-shadowtls`, `ripdpi-anytls`, `ripdpi-tuic`, `ripdpi-hysteria2`, `ripdpi-masque`, `ripdpi-mieru`, `ripdpi-naiveproxy`, `ripdpi-ssh`, `ripdpi-tor`, `ripdpi-webtunnel`, `ripdpi-ws-tunnel`, `ripdpi-xhttp`, `ripdpi-warp-core`, plus the relay-layer TLS transports under `ripdpi-relay-tls-transports`. Caveat: `ripdpi-mieru` and `ripdpi-ssh` are stubbed — they enter the matrix as `not-implemented`, not `blocked` (conflating the two is the first false-positive trap).

**Block vs false-positive discrimination (the core problem).** An operator-specific transport failure must be separated from server outage, certificate expiry, and access-network transients. The discriminator is **differential, not absolute**: for each `(vantage, transport)` cell, run the transport against (i) a known-good control endpoint and (ii) a clean-baseline reference (the same 5-tuple carrying a benign TLS-to-allowed-SNI flow). A cell counts as a **protocol-class block** only when the control is reachable on the clean baseline from that vantage AND the transport fingerprint fails with a wire-signature already modeled by `ripdpi-failure-classifier::BlockSignal` (`signal_types.rs`: `TlsAlert`, `SilentDrop`, `TcpReset`, `ConnectionFreeze`, `QuicBreakage`, …). Server, certificate, and access-network failures present as baseline-also-fails or non-block signals and are excluded. Reusing the existing classifier makes the matrix reproducible.

**Vantage sampling.** At least three independent access-network vantages are required. Each cell is sampled N≥5 across a time window to defeat transients; the cell verdict is `blocked` only at quorum (for example, ≥4/5 block-signal with baseline-up). The matrix is three-dimensional `(transport, vantage, signal)`, **not** a flat per-transport pass/fail, because deployment differs by access network.

**Reproducibility / idempotency.** Emit a deterministic JSON artifact (sorted transport keys, sorted vantage keys, fixed float formatting) so re-runs diff cleanly — the discipline the autolearn store already uses for its SHA-256 config digest. Key each run by `(run_id, vantage_scope_hash, transport)` so re-ingest is idempotent (mirroring the autolearn 2-hit `host_blocked` confirmation).

**Privacy (non-negotiable, per `network-fingerprint-privacy.md`).** The vantage identity in the persisted matrix MUST be the SHA-256 `network_scope_key` only — never raw BSSID/IMEI/IMSI/SSID/IP, and never `CarrierName` (locale-unstable; the rule mandates numeric `carrierId`+MCC+MNC). The autolearn store already treats `network_scope_key` as an opaque pre-hashed string supplied by Kotlin; the methodology inherits that boundary verbatim. "MTS / Rostelecom / MegaFon" are human-readable design-doc labels only — the artifact keys on scope hashes.

### (b) `ripdpi-runtime-policy` hook design — where a measured matrix feeds defaults

**Structural finding (load-bearing).** The task's "de-prioritize high-block-rate *transports*" does NOT map cleanly onto any existing policy type, because **no policy type is keyed by transport-crate identity today**:

- `TransportPolicy` (`transport_policy.rs`) is per-*host*; its axes are `QuicMode`/`PreferredStack`/`DnsMode`/`TcpFamily`/`PolicyOutcome` — none names VLESS/Hysteria2/Shadowsocks/etc.
- `DirectPathBlockClass` (`direct_path_learning/scoring.rs`) is a *failure-pattern* taxonomy (`QuicBlocked`, `TlsPostClientHello`, `AllIpsFailed`…); its `RankedArm` labels (`"quic"`, `"tcp_plain"`, `"tcp_tls_split"`, `"relay_fallback"`) are direct-path strategies, not relay transports.
- `note_block_signal` (`runtime_policy/autolearn/mod.rs`, signature `(config, host, signal, provider, confirmation_allowed)`) records a per-`host` `BlockSignal` (+ `provider`); it carries **no** `network_scope_key` argument — network-scope keying is applied one level up in the autolearn store (`learned_hosts_by_scope` in `store.rs`), not at this entry point. Either way it records *which host is blocked*, never *which transport class*.
- `TransportProtocol` (in `matching/predicates.rs`) is the L3/L4 IP protocol (Tcp/Udp) for desync-group matching — not a per-crate identity.

The operator-measurement matrix therefore carries a network-scoped transport-class block-rate dimension with **no native home** in the policy crate; that is the design output of this spike.

**Three candidate seams, ranked by cost / merge-now-safety:**

1. **Config-data default ordering (no Rust change — preferred, graduates first).** Relay/transport selection order is already config-driven (autolearn seeds *group* preferences via `seed_from_strategy_results`, keyed by `(domain, group_index)`). Ship the measured matrix as **static default de-prioritization weights in config data** so operator-affected transports receive a lower default rank for the relevant network class. This needs no new type and follows the existing data-only provider pattern.
2. **A network-scoped transport-block table parallel to `learned_hosts_by_scope` (new code — defer).** The cleanest native shape: `transport_block_rate_by_scope: BTreeMap<ScopeKey, BTreeMap<TransportClass, BlockRate>>` fed by a new `note_transport_class_block(scope, class, signal)` confirming with the same 2-hit window. Right long-term, but speculative code — out of scope for this spike; re-files as an implementation task.
3. **Extend `DirectPathBlockClass` — REJECTED.** Adding transport-class variants conflates failure-pattern with transport-identity and pollutes the `RankedArm` dispatcher; per the ProxyProfile-subtype-blast-radius lesson, enum widening here forces exhaustive-`when`/`match` churn for no benefit.

**Expressed without merging speculative code now:** the spike deliverable is the *mapping table* — cell `(transport=VLESS, scope=mts-mobile-class, verdict=blocked, quorum≥4/5)` ⇒ lower default relay-group rank for that transport on that scope class. When vantages exist, the field run produces the matrix, which lands as config default data (seam 1); seam 2 is filed only if per-network *learned* (not just static-default) de-prioritization is wanted.

## Go / No-Go (2026-06-11)

**Verdict: CONDITIONAL-GO on the methodology** (the field run stays externally-gated on ≥3 RU ISP vantages, regardless). The discrimination design (differential baseline + reuse of `BlockSignal`), the 3-D vantage sampling, the idempotent SHA-256-keyed artifact, and the privacy boundary are all sound and ground out on existing repo machinery. **The condition:** the matrix schema must carry `(transport-class, scope-hash)` keying *from the start* — because the policy layer has no native transport-identity dimension, a matrix recording only per-host or per-failure-pattern data would be unusable, forcing the expensive gated field run to be redone. Lock the schema now (cheap, repo-side); then the run is execute-ready the moment vantages exist. Not a drop — payoff to transport selection is high; not a full go — that would over-claim a readiness the gating does not support.

**Graduation target.** Re-files under `epic-transport-obfuscation-research`. Minimal first slice (repo-side, executable now without vantages): (1) freeze the matrix JSON schema keyed by `(transport_class, network_scope_hash, BlockSignal, quorum)` with deterministic ordering; (2) author the per-transport differential probe harness as a diagnostics-style task reusing `ripdpi-failure-classifier::BlockSignal`, runnable against any vantage (including non-RU dev networks) to validate the harness end-to-end. The policy-hook implementation (seam 1 config-default weights) graduates as a separate `ripdpi-runtime-policy` + config-data task that lands ONLY after a real matrix exists; the native `transport_block_rate_by_scope` table (seam 2) is a deferred follow-up. **Field execution stays externally-gated on ≥3 RU ISP vantages.**

## Work log

- 2026-06-05: No acceptance criteria met — no empirical block-rate matrix, no wiki field-measurement section, and no runtime-policy defaults adjusted from operator-specific fingerprint research. Parent epic `epic-direct-mode-transport-policy-and-verdicts` is dangling (nulled). Task remains open as a research/diagnostic investigation requiring external access-network vantage testing.
- 2026-06-11 (design spike, conditional-go on methodology; field run gated): Delivered the measurement methodology (differential block discrimination reusing `ripdpi-failure-classifier::BlockSignal`, 3-D vantage sampling at quorum, idempotent SHA-256-scope-keyed JSON artifact, `network-fingerprint-privacy` boundary) and the `ripdpi-runtime-policy` hook design. Load-bearing finding: no policy type is keyed by transport-crate identity today (`TransportPolicy` per-host axes; `DirectPathBlockClass` failure-pattern; `TransportProtocol` L3/L4) — so the matrix schema must carry `(transport-class, scope-hash)` keying from the start, and the landing seam is config-default ordering (no Rust change), not a `DirectPathBlockClass` widening. No code merged; status → `blocked` (field execution externally gated on ≥3 RU ISP vantages).
