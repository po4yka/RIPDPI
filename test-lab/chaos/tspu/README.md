# TSPU adversarial emulator (v1)

Current Phase-16 wiring is documented in [`docs/testing.md`](../../../docs/testing.md#phase-16-real-world-confidence-status).

> **Why not CensorLab?** See [`CENSORLAB-EVALUATION.md`](CENSORLAB-EVALUATION.md) (2026-06-11): CensorLab (arXiv:2412.16349) was evaluated as an offline censor-replay harness and **rejected as a dependency** — its NFQUEUE plumbing duplicates this harness while adding a Nix/nightly-Rust/GPL-3.0 toolchain. Three CensorLab ideas (DoH/DoQ classifier, stateful reassembly, ML-censor emulation) are recorded there as prioritised fork-in follow-ups.

v1.1 ships five classifier models and a matrix-runner that reports per-cell verdicts (`bypassed` / `blocked` / `degraded` / `inconclusive`) per `(desync_mode_id, pattern_id)` cell. The descriptions below are the modeled censor intent; the current live adapter implements only stateless match-and-drop:

| pattern_id | description |
|---|---|
| `rst-after-sni-match` | Inspect outbound packets for a TLS ClientHello whose SNI matches a blocklist; emit a synthetic RST when matched. |
| `quic-initial-drop` | Inspect synthetic UDP fixture fields for a QUIC Initial with matching SNI/ALPN; live mode cannot decrypt QUIC Initials and therefore matches only directly available fields. |
| `sni-replace` | Inspect outbound TLS ClientHello packets for a SNI on the blocklist; rewrite the ClientHello so the handshake terminates at a sinkhole. |
| `ip-blackhole-after-n-bytes` | Accumulate outbound bytes per flow; once `threshold_bytes` is crossed (filtered by `target_dst_ports`), drop all subsequent packets. |
| `mtu-clamp` | Mark any outbound packet whose payload exceeds `mtu_payload_bytes`; the live adapter can only drop the current matched packet. |

## Layout

```
test-lab/chaos/tspu/
├── README.md           # this file
├── matrix.json         # (desync_mode_id × pattern_id) cells the runner sweeps
├── Dockerfile          # Linux container: nftables + Python runner (live mode)
├── patterns/
│   ├── manifest.json   # pattern registry (id, status, description)
│   ├── rst_after_sni_match.py
│   ├── quic_initial_drop.py
│   ├── sni_replace.py
│   ├── ip_blackhole_after_n_bytes.py
│   └── mtu_clamp.py
├── runner/
│   ├── cli.py             # entry point: `python -m runner.cli ...`
│   ├── classifier.py      # shared verdict logic
│   ├── replay.py          # dry-run: reads JSON packet traces, no kernel I/O
│   ├── pcap_writer.py     # stdlib-only pcap writer for evidence artifacts
│   ├── schema.py          # verdict JSON schema constants
│   ├── packet_parser.py   # shared packet field extraction
│   ├── chlo_builder.py    # synthetic TLS ClientHello builder for fixtures/dry-run
│   ├── live.py            # live mode: NFQUEUE-driven kernel-path classification
│   ├── nfqueue_adapter.py # NFQUEUE binding for live mode
│   ├── ci_smoke_traffic.py# CI smoke traffic generator
│   ├── entrypoint-live.sh # container entry point for live mode
│   └── nft/               # nftables rulesets for live mode (see nft/README.md)
├── fixtures/
│   └── desync_modes/   # synthetic packet traces per desync mode
└── tests/              # pytest suite, stdlib-only, runs anywhere
```

## Two run modes

### Dry-run (any host, including macOS)

Replays packet traces from `fixtures/desync_modes/*.json` against each pattern's classifier without touching the kernel. Emits:

- `verdict-report.json` — per-cell verdict + evidence summary.
- `<cell>.pcap` — synthesized evidence pcap from the replayed trace. It records modeled classification, not a captured injected response. Pcap format is stdlib-only; no scapy / dpkt dependency.

This mode exists so the harness shape is verifiable on every PR without requiring a Linux runner.

```bash
cd test-lab/chaos/tspu
python3 -m runner.cli dry-run \
  --matrix matrix.json \
  --fixtures fixtures \
  --out-dir /tmp/tspu-dryrun
```

### Live (Linux only, requires NET_ADMIN)

Builds the container in `Dockerfile`, attaches nfqueue rules, and dispatches real traffic through the userspace classifier. The current NFQUEUE adapter is deliberately narrow: it makes stateless decisions from directly parsed packet fields and can accept or drop a packet. It does not inject RSTs, rewrite SNI, fragment packets, accumulate per-flow byte counts, or decrypt QUIC Initials for SNI/ALPN. The focused live smoke runs only when its harness paths change in `.github/workflows/l7-adversarial-live.yml`; `scripts/ci/act-local.sh l7-live` is the local equivalent. Phase-16 real-provider carrier lanes remain separate operator-run release evidence.

## Verdict semantics

| verdict | meaning |
|---|---|
| `bypassed` | Pattern's classifier did not match any packet in the trace. The desync mode evades this pattern. |
| `blocked` | Pattern's classifier matched. The live adapter drops the matched packet; dry-run records the matched packet index. This does not prove the fate of the complete flow. |
| `degraded` | Fixture explicitly requests `force_degraded`; live mode currently emits only `blocked` or `bypassed`. |
| `inconclusive` | Trace malformed or missing data the pattern requires. Does not gate PRs. |

Current workflows publish these verdicts as evidence; `blocked` and `inconclusive` do not fail or gate `ripdpi-desync` changes. See `docs/testing.md` for the Phase-16 release-evidence contract.

## Combination matrices

`matrix.json` also defines a `combinations` array. Each entry lists member `patterns` by id; the runner OR-joins their classifications. A combination cell is `blocked` if any member pattern matches, otherwise `bypassed`. The cell records `combination_member_ids` and `matched_pattern_ids` in `evidence` so triage knows which adversary within the combination fired.

Shipped combinations:

| id | semantics |
|---|---|
| `tcp-sni-and-mtu` | TCP-side TSPU running RST-on-SNI + ClientHello-rewrite + MTU-clamp simultaneously. |
| `quic-strict` | QUIC-side pessimistic: Initial drop + IP blackhole after 1000 bytes. |
| `all-tcp-and-blackhole` | Defense-in-depth on the TCP path: every TCP-touching pattern plus the byte-budget blackhole. |
| `all-five` | Worst-case adversary: every v1.1 pattern active simultaneously. |

Combinations expand the matrix to 7 desync modes × (5 patterns + 4 combinations) = 63 cells. The matrix-runner reports per-cell verdicts plus the same totals.

## Not in v1.x

- Real-time nfqueue wiring exercised against synthetic outbound traffic is in the path-triggered live middlebox workflow; real-provider carrier evidence is handled by the opt-in Phase-16 real-provider rows.
- Stateful per-flow tracking across multiple SrcPort/DstPort tuples — the dry-run runner treats each fixture as a single flow.
