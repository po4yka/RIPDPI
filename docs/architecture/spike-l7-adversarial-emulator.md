# Design spike: L7 adversarial emulator

Status: design proposal (2026-05-16) Tracks: [`spike-adversarial-network-harness-and-realprovider-matrix.md`](../tasks/issues/spike-adversarial-network-harness-and-realprovider-matrix.md)

## Problem

`test-lab/chaos/` ships Toxiproxy + netem for packet loss, latency, and jitter. Those fixtures do not reproduce reactive L7 middlebox behavior such as RST-injection on SNI match, blackhole after N bytes, selective drop of QUIC Initials, and MTU-clamp. Packet-smoke today verifies the byte shape on the wire but not against a deterministic path-policy adversary that reacts to that shape. A transport profile can pass packet-smoke and still regress field path behavior because no part of CI exercises it against the reactive middlebox behavior represented here.

## Target adversary patterns (v1)

Each pattern is a named, deterministic transformation applied to flows crossing the harness. The harness reports pass / fail / partial per (desync mode, pattern) cell.

1. **rst-after-sni-match.** Inspect TLS ClientHello; if the SNI matches a configured pattern list, inject a TCP RST toward the client and silently drop subsequent segments. Models a fixture-denylisted SNI reset pattern.
2. **sni-replace.** Inspect TLS ClientHello; if SNI matches, rewrite the ClientHello so that the handshake terminates at a sinkhole.
3. **ip-blackhole-after-n-bytes.** Allow the first N bytes of a flow, then drop everything to the configured destination IP. Models cases where decision is deferred until application data is observable.
4. **quic-initial-drop.** Drop UDP datagrams whose payload looks like a QUIC Initial with a matching SNI/ALPN combination. Allow Initial-free flows through.
5. **mtu-clamp.** Force MSS / MTU below a threshold to break PMTU discovery. Used in combination with the above to model real carrier constraints.

The pattern set is intentionally small. New patterns enter only when we have evidence of the corresponding adversary behaviour in field captures.

## Surface

- Container under `test-lab/chaos/l7-adversarial/`.
- Userspace dispatcher using `nftables` + `nfqueue` (Linux runner) or `divert` sockets (BSD runner). One queue per pattern.
- Per-flow state in Rust or Python; the language choice follows the Toxiproxy precedent (Python is fine, Rust acceptable if we already have to load shared rust crates for parsing).
- Patterns are toggled via a JSON manifest mounted into the container. Same shape as `packet-smoke-scenarios.json` so the runner reuses existing tooling.

## Contract

Per cell:

- `pattern_id` (string, stable contract)
- `desync_mode_id` (string, stable contract)
- `verdict`: `bypassed` | `blocked` | `degraded` | `inconclusive` (wire values; read `bypassed` as "passed the emulator cell")
- `evidence`: optional pcap path, classifier output, observed RST/FIN/timeout counts

The cell verdict is the test oracle. `inconclusive` cells do not gate PRs; `blocked` cells do.

## What this does *not* do

- Not a substitute for real-provider testing. The emulator codifies known reactive path patterns; real providers have surface area beyond what we enumerate. Phase-16 matrix on real SIM remains the release gate.
- Not a packet-smoke replacement. Packet-smoke verifies "what we put on the wire matches the desync plan." This emulator verifies "what we put on the wire survives an adversary that reacts to it." Both run.
- Not a generic netem replacement. Existing netem scenarios for loss / latency / jitter continue to live in `test-lab/chaos/netem/`.

## Phasing

1. v1 land patterns 1 (rst-after-sni-match) and 4 (quic-initial-drop) only. These cover the initial reactive L7 behavior set and let us validate the harness shape before adding more.
2. v1.1 add patterns 2 (sni-replace) and 3 (ip-blackhole-after-n-bytes).
3. v2 add MTU clamp and combination matrices.

## Open design questions

- Run the harness as a separate container talking to the existing Toxiproxy chain, or inline into the same network namespace as the test target? Inline is simpler but couples the two stacks.
- Where does the pattern manifest live — alongside `packet-smoke-scenarios.json` or in its own directory? Probably its own directory so the two oracles stay independently versioned.
- How do we surface verdicts in the existing `phase16_pcap_summary.py` output, or do we add a sibling summarizer? Sibling, to keep packet-smoke output stable.
