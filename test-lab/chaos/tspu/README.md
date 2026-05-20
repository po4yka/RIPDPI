# TSPU adversarial emulator (v1)

Tracked by [`docs/architecture/spike-tspu-adversarial-emulator.md`](../../../docs/architecture/spike-tspu-adversarial-emulator.md).

v1.1 ships five patterns and a matrix-runner that reports per-cell verdicts (`bypassed` / `blocked` / `degraded` / `inconclusive`) per `(desync_mode_id, pattern_id)` cell:

| pattern_id | description |
|---|---|
| `rst-after-sni-match` | Inspect outbound packets for a TLS ClientHello whose SNI matches a blocklist; emit a synthetic RST when matched. |
| `quic-initial-drop` | Inspect outbound UDP datagrams for QUIC Initial long headers with matching SNI/ALPN; drop the matched datagram. |
| `sni-replace` | Inspect outbound TLS ClientHello packets for a SNI on the blocklist; rewrite the ClientHello so the handshake terminates at a sinkhole. |
| `ip-blackhole-after-n-bytes` | Accumulate outbound bytes per flow; once `threshold_bytes` is crossed (filtered by `target_dst_ports`), drop all subsequent packets. |
| `mtu-clamp` | Match any outbound packet whose payload exceeds `mtu_payload_bytes`; fragment or drop in live mode. |

## Layout

```
test-lab/chaos/tspu/
├── README.md           # this file
├── matrix.json         # (desync_mode_id × pattern_id) cells the runner sweeps
├── Dockerfile          # Linux container: nftables + Python runner (live mode)
├── patterns/
│   ├── manifest.json   # pattern registry (id, status, description)
│   ├── rst_after_sni_match.py
│   └── quic_initial_drop.py
├── runner/
│   ├── cli.py          # entry point: `python -m runner.cli ...`
│   ├── classifier.py   # shared verdict logic
│   ├── replay.py       # dry-run: reads JSON packet traces, no kernel I/O
│   ├── pcap_writer.py  # stdlib-only pcap writer for evidence artifacts
│   └── schema.py       # verdict JSON schema constants
├── fixtures/
│   └── desync_modes/   # synthetic packet traces per desync mode
└── tests/              # pytest suite, stdlib-only, runs anywhere
```

## Two run modes

### Dry-run (any host, including macOS)

Replays packet traces from `fixtures/desync_modes/*.json` against each pattern's classifier without touching the kernel. Emits:

- `verdict-report.json` — per-cell verdict + evidence summary.
- `<cell>.pcap` — synthesized pcap from the replayed trace + the classifier's injected response (RST or drop marker). Pcap format is stdlib-only; no scapy / dpkt dependency.

This mode exists so the harness shape is verifiable on every PR without requiring a Linux runner.

```bash
cd test-lab/chaos/tspu
python3 -m runner.cli dry-run \
  --matrix matrix.json \
  --fixtures fixtures \
  --out-dir /tmp/tspu-dryrun
```

### Live (Linux only, requires NET_ADMIN)

Builds the container in `Dockerfile`, attaches nfqueue rules, and dispatches real traffic through the userspace classifier. **Not exercised in CI yet** — v1 lands the live surface stubbed; the implementation PR following this v1 will wire it into the `ripdpi-lab` self-hosted runner pool.

## Verdict semantics

| verdict | meaning |
|---|---|
| `bypassed` | Pattern's classifier did not match any packet in the trace. The desync mode evades this pattern. |
| `blocked` | Pattern's classifier matched. In live mode the synthetic adversary response would have terminated/dropped the flow; in dry-run we record the matched packet index. |
| `degraded` | Pattern matched on a non-initial packet, or fixture explicitly flags partial. Reserved primarily for live mode. |
| `inconclusive` | Trace malformed or missing data the pattern requires. Does not gate PRs. |

`blocked` cells gate PRs that touch `ripdpi-desync`; `inconclusive` cells do not. See the design spike for the contract details.

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

- Real-time nfqueue wiring exercised against synthetic outbound traffic is in CI under `tspu-live`; bringing in *real* internet-facing carriers belongs to the Phase-16 real-provider spike.
- Generator-driven sampling across the 7-dim desync space (separate spike).
- Stateful per-flow tracking across multiple SrcPort/DstPort tuples — the dry-run runner treats each fixture as a single flow.
