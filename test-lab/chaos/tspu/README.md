# TSPU adversarial emulator (v1)

Tracked by
[`docs/architecture/spike-tspu-adversarial-emulator.md`](../../../docs/architecture/spike-tspu-adversarial-emulator.md).

v1 ships two patterns and a matrix-runner that reports per-cell verdicts
(`bypassed` / `blocked` / `degraded` / `inconclusive`) per `(desync_mode_id,
pattern_id)` cell:

| pattern_id | description |
|---|---|
| `rst-after-sni-match` | Inspect outbound packets for a TLS ClientHello whose SNI matches a blocklist; emit a synthetic RST when matched. |
| `quic-initial-drop` | Inspect outbound UDP datagrams for QUIC Initial long headers with matching SNI/ALPN; drop the matched datagram. |

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

Replays packet traces from `fixtures/desync_modes/*.json` against each
pattern's classifier without touching the kernel. Emits:

- `verdict-report.json` — per-cell verdict + evidence summary.
- `<cell>.pcap` — synthesized pcap from the replayed trace + the
  classifier's injected response (RST or drop marker). Pcap format is
  stdlib-only; no scapy / dpkt dependency.

This mode exists so the harness shape is verifiable on every PR without
requiring a Linux runner.

```bash
python3 -m test-lab.chaos.tspu.runner.cli dry-run \
  --matrix test-lab/chaos/tspu/matrix.json \
  --fixtures test-lab/chaos/tspu/fixtures \
  --out-dir /tmp/tspu-dryrun
```

### Live (Linux only, requires NET_ADMIN)

Builds the container in `Dockerfile`, attaches nfqueue rules, and dispatches
real traffic through the userspace classifier. **Not exercised in CI yet** —
v1 lands the live surface stubbed; the implementation PR following this v1
will wire it into the `ripdpi-lab` self-hosted runner pool.

## Verdict semantics

| verdict | meaning |
|---|---|
| `bypassed` | Pattern's classifier did not match any packet in the trace. The desync mode evades this pattern. |
| `blocked` | Pattern's classifier matched. In live mode the synthetic adversary response would have terminated/dropped the flow; in dry-run we record the matched packet index. |
| `degraded` | Pattern matched on a non-initial packet, or fixture explicitly flags partial. Reserved primarily for live mode. |
| `inconclusive` | Trace malformed or missing data the pattern requires. Does not gate PRs. |

`blocked` cells gate PRs that touch `ripdpi-desync`; `inconclusive` cells do
not. See the design spike for the contract details.

## Not in v1

- Real-time nfqueue wiring exercised in CI (lands in the live-mode follow-up).
- Patterns 2 / 3 / 5 from the design spike (SNI-replace, IP-blackhole-after-N,
  MTU-clamp).
- Generator-driven sampling across the 7-dim desync space (separate spike).
