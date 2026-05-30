# Offline strategy-pack simulator and the sim-to-field gap

The offline strategy-pack simulator lives in `scripts/analytics/simulate.py` and is
wired into the analytics pipeline CLI (`scripts/analytics/pipeline.py`) via the
`simulate`, `simulate-run`, and `calibrate` subcommands. This document explains
what the simulator is (and is not), the censor scenarios it ships with, its
determinism and provenance contracts, and — most importantly — the **sim-to-field
gap**: what it is, why a perfect internal calibration score does not mean the gap
is zero, and the concrete per-release procedure for measuring it.

## What the simulator is

- A **deterministic, offline, standalone** tool. It synthesizes an
  extracted-records-shaped corpus from a registry of named censor scenarios, then
  feeds that corpus through the *same* `cluster` → `publish` pipeline that
  field-derived diagnostics archives flow through. Same code path, synthetic input.
- A way to exercise the learner end-to-end without collecting real field captures,
  and to **calibrate** whether the learner picks the strategy-arm winner family a
  given block model is expected to yield.
- A generator of **review-gated candidate** strategy packs. Every pack it emits has
  `rollout.staged = true` and `rollout.percentage = 0`, exactly like field-derived
  candidates.

## What the simulator is NOT

- **NOT a real censor.** It does not run packets through an actual middlebox, DPI
  engine, or live network. The "block model" of each scenario is a hand-authored
  description of which strategy arm is expected to survive — it is an assertion, not
  a measurement.
- **NOT a live network test.** No sockets are opened, no hosts are contacted. (Per
  the project's `vpnservice-protect-invariant.md`, this tool opens no outbound
  sockets at all — it is pure offline data synthesis.)
- **NOT an auto-consumed source of truth.** Generated packs are candidate artifacts
  only. They are **never** auto-consumed by runtime ranking. A human analyst must
  review them and a maintainer must `bless` them before any pack leaves the staged,
  0-percent rollout state.

## Built-in censor scenarios

The authoritative scenario list is the `_SCENARIOS` registry in
`scripts/analytics/simulate.py`. Do not invent scenarios — read that file. As of
this writing the built-in scenarios are:

| Scenario id | Block model (label) | Assessment code | Surviving winner family |
| --- | --- | --- | --- |
| `sni_block` | SNI keyword block on TLS ClientHello | `raw_network_selective_blocking` | `tlsrec_split + quic_sni_split` |
| `quic_udp443_block` | QUIC UDP/443 throttle and block | `raw_network_selective_blocking` | `disorder_host + quic_disabled` |
| `rst_on_keyword` | TCP RST injection on keyword match | `raw_network_selective_blocking` | `disorder_host + quic_disabled` |
| `dns_poison` | DNS poisoning / resolver injection | `resolver_interference` | `tlsrec_split + quic_sni_split` |
| `tls_split` | TLS record fragmentation / version split | `raw_network_selective_blocking` | `tlsrec_split + quic_sni_split` |

Each scenario also carries a synthetic strategy `signature` (`desyncMethod`,
`tcpStrategyFamily`, `quicStrategyFamily`, `dnsStrategyFamily`, etc.) whose family
strings mirror the ones the real pipeline already recognises for the sample corpus,
so the simulated payload flows through `cluster`/`publish` exactly like a
field-derived one.

### Privacy posture of simulated records

Simulated records and the packs derived from them contain **only synthetic,
non-identifying values**. Affected-target hostnames are RFC 2606 `.example`
hostnames (`sim-target-N.example`). No SSID, BSSID, IMEI/IMSI, carrier name,
precise location, real user URL, or device IP appears anywhere — this is enforced
by `.claude/rules/network-fingerprint-privacy.md` and is intentional so the corpus
can be committed and shared freely.

## Determinism contract

The simulator is fully deterministic in `(scenarios, seed, count)`:

> **Same `(scenarios, seed, count)` ⇒ byte-identical records and byte-identical
> candidate packs.**

- All per-record variation comes from `random.Random(seed)` **only**. No
  wall-clock, no `time`, no `os.urandom` ever feeds record or pack *content*.
  (`generatedAt` ISO timestamps from `now_iso_utc()` are non-identifying metadata
  and are the only non-deterministic field; they do not affect record identity, the
  cluster fingerprint, or pack ids.)
- `recordId` is derived deterministically as `sim-<scenario_id>-<seed>-<index>`.

### How pack ids are derived

Pack ids are derived from the cluster fingerprint hash, which is itself a stable
hash of the (deterministic) clustered record content. The exact form is:

```
offline-sim-<bucket-slug>-<clusterFingerprintHash[:12]>      # simulated provenance
offline-<bucket-slug>-<clusterFingerprintHash[:12]>          # field provenance
```

The `offline-sim-` prefix is applied by `build_strategy_pack` in
`scripts/analytics/strategy_pack.py` whenever `provenance == "simulated"`. Because
the fingerprint hash is a pure function of deterministic input, re-running the same
`(scenarios, seed, count)` reproduces the same pack ids.

## Provenance: keep simulated packs separate from field packs

Simulated packs are tagged in two redundant, machine-checkable ways:

1. **Id prefix `offline-sim-`** — field packs use `offline-` (no `sim`). The prefix
   alone tells an analyst, a grep, or a CI gate that a pack is synthetic.
2. **Trigger metadata `offline_provenance:simulated`** — added to the pack's
   `triggerMetadata` list by `build_trigger_metadata`. Pack `notes` also carry the
   sentence "Provenance: simulated (synthetic censor-scenario corpus, not
   field-derived)."

The `simulate-run` subcommand passes `provenance="simulated"` into
`publish_outputs`, so any pack emitted by that path is tagged automatically.

**Why analysts must keep them separate:** simulated packs encode the simulator's
*assumptions* about how a censor behaves, not field-observed evidence. Mixing them
into the field-derived candidate stream would let an unvalidated assumption ship as
if it were measured. The `offline-sim-` prefix and `offline_provenance:simulated`
tag exist precisely so a reviewer never blesses a synthetic pack believing it came
from real captures. Treat a simulated pack as a hypothesis to validate against the
field, never as a field result.

## The sim-to-field gap

### Definition

The **sim-to-field gap** is the discrepancy between the strategy-arm winner the
simulator *predicts* will survive a censor scenario and the winner that *actually*
survives that censorship technique in the real field. The simulator's block models
are hand-authored assertions about censor behaviour; the field is the ground truth.
Wherever the two diverge, that divergence is the gap.

### Why a 1.0 internal calibration score does NOT mean the gap is zero

`calibrate` scores **internal self-consistency**, not field accuracy. The default
calibration fixture (`scripts/analytics/calibration-field-failures.json`) is a set
of **synthetic stand-ins authored from the simulator's own block models** — its
`note` field says so explicitly. There is no curated real field-failure archive
yet.

So a perfect `agreementScore=1.0` means only "the learner reproduces the answer the
simulator was told to expect." Because both the scenario block model and the
fixture's `expectedWinnerFamily` came from the same source, agreement is almost
tautological. It says **nothing** about whether a real censor of that class is
actually defeated by that arm. The gap can be large while the internal score is a
perfect 1.0.

To make calibration measure the *real* gap, the fixture's `expectedWinnerFamily` /
`fieldNote` values must be replaced with archive-derived ground truth from real
captures. Until then, `calibrate` is a regression guard on the simulator's internal
consistency, not a field-accuracy meter.

### Per-release procedure to MEASURE the gap

Do this every release, before shipping any generated (simulated-provenance) pack:

1. **Curate N known real field failures.** Collect real, reproduced field-failure
   cases — each one a censorship technique you have actually observed defeating (or
   being defeated by) a known strategy arm. Keep them privacy-clean: only synthetic
   scenario ids and coarse, non-identifying notes (no SSID/BSSID/IMEI/IMSI/carrier/
   location/device IP), per `network-fingerprint-privacy.md`.
2. **Encode them in `calibration-field-failures.json`.** For each case add an entry
   with `scenarioId` (mapping to a built-in scenario) and the field-observed
   `expectedWinnerFamily`, plus a short `fieldNote`. Replace the synthetic
   stand-ins as real captures land — that is the whole point of the file.
3. **Run the calibration harness:**
   ```sh
   python3 -m scripts.analytics.pipeline calibrate
   ```
   It prints, e.g., `calibrate: agreementScore=1.0 (5/5) threshold=0.8 PASS`. With a
   field-derived fixture, `agreementScore` is now the measured sim-to-field
   agreement; `1 - agreementScore` is the measured gap.
4. **Record the `agreementScore` in the release notes.** Capture the exact score
   and the fixture revision it was computed against, so the gap is tracked
   release-over-release.
5. **Investigate every `agree=false` entry before shipping.** Each disagreement in
   the report's `perEntry` list is a concrete point where the simulator's block
   model diverges from the field. Do not ship a generated pack for a scenario whose
   calibration entry disagrees until you understand why and have either fixed the
   block model or accepted the divergence with rationale. The CLI exits non-zero if
   `agreementScore` falls below the `CALIBRATION_THRESHOLD` (0.8), so CI can gate on
   it.

## Exact commands

Run all commands from the repo root.

### `simulate` — synthesize a corpus only

```sh
# All built-in scenarios, default seed/count, write records JSON.
python3 -m scripts.analytics.pipeline simulate \
  --output /tmp/sim/offline-records.json

# A single scenario with explicit seed and per-scenario count.
python3 -m scripts.analytics.pipeline simulate \
  --scenarios sni_block \
  --seed 1337 \
  --count 2 \
  --output /tmp/sim/sni-records.json
```

Defaults: `--scenarios` = all built-in scenario ids, `--seed 1337`, `--count 4`.

### `simulate-run` — synthesize, cluster, and publish provenance-tagged candidates

```sh
python3 -m scripts.analytics.pipeline simulate-run \
  --scenarios sni_block,quic_udp443_block,rst_on_keyword,dns_poison,tls_split \
  --seed 1337 \
  --count 4 \
  --output-dir /tmp/sim-run
```

This writes `offline-records.json`, `clustered-records.json`, and the published
report/candidate artifacts under `--output-dir`. Every generated pack carries the
`offline-sim-` id prefix and the `offline_provenance:simulated` trigger tag.

### `calibrate` — score sim-to-field agreement

```sh
# Use the default fixture (scripts/analytics/calibration-field-failures.json).
python3 -m scripts.analytics.pipeline calibrate

# Use a curated field-failure fixture and persist the report.
python3 -m scripts.analytics.pipeline calibrate \
  --fixture path/to/field-failures.json \
  --seed 1337 \
  --count 6 \
  --output /tmp/calibration-report.json
```

The command prints the `agreementScore`, the matched/total count, the threshold,
and `PASS`/`FAIL`. It exits `0` when `agreementScore >= 0.8` and `1` otherwise, so
it is safe to wire into a CI gate.

## Cross-references

- `scripts/analytics/simulate.py` — authoritative scenario registry and record
  synthesis.
- `scripts/analytics/calibrate.py` — calibration harness and threshold.
- `scripts/analytics/calibration-field-failures.json` — the fixture to replace with
  real field-derived ground truth.
- `scripts/analytics/strategy_pack.py` — `offline-sim-` id prefix and
  `offline_provenance:simulated` tagging.
- `.claude/rules/network-fingerprint-privacy.md` — the privacy bounds every
  simulated record and fixture entry must honour.
