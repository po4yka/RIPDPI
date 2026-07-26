# CensorLab evaluation — offline censor-replay harness

**Spike:** `spike-censorlab-as-offline-censor-replay-harness`; its completed parent `epic-orchestration-test-posture` was closed on 2026-07-26 after child acceptance criteria and regression posture were verified, and is retained in git history.
**Date:** 2026-06-11. **Source citation:** ripdpi-android-research-2026-04-20 §Academic papers; CensorLab = arXiv:2412.16349.

## Decision: REJECT as a maintained dependency — FORK three ideas into `tspu`

Do **not** adopt CensorLab as a build/CI dependency. The offline middlebox-replay niche this spike set out to fill is **already filled** by the in-repo harness in this directory: a classifier-oriented, NFQUEUE-based replay harness with a deterministic offline dry-run mode and path-triggered evidence workflows. CensorLab's packet-interception plumbing is the *same* Linux NFQUEUE mechanism the local harness already uses, so adopting it adds cost without replacing anything load-bearing.

Instead, **selectively port** the three CensorLab capabilities that close real `tspu` gaps (see *Next concrete actions*). This is the "fork" branch of adopt/fork/reject: keep our harness, borrow the distinctive ideas.

### Why reject the dependency (evidence)

| Axis | CensorLab (arXiv:2412.16349, github.com/SPIN-UMass/censorlab) | `test-lab/chaos/tspu` (in-repo) |
| --- | --- | --- |
| What it is | Academic *censor generator* + accuracy benchmarker (Rust ~470 KLOC + Python). The censor side of an experiment — not a replay corpus, not a bypass tool. | Purpose-built TSPU-threat-model censor-replay harness for RIPDPI's desync arms. |
| Interception | Linux **NFQUEUE** (Tap), 2-NIC userspace MITM (Wire), or offline PCAP. | Linux **NFQUEUE** (`runner/nfqueue_adapter.py`, live) + deterministic offline dry-run (`runner/replay.py`). **Same plumbing.** |
| Build/runtime | **Nix-first**, nightly Rust + `libffi` + RustPython, unstable git-pinned deps (`smoltcp` main, `ort 2.0.0-rc.11`); cold build is heavy/slow. Live needs `CAP_NET_ADMIN`+`CAP_NET_RAW`/`sudo`. | Dry-run is pure **Python 3.11 stdlib** (no scapy/dpkt) and needs no privileges or Nix; live additionally requires `NetfilterQueue` and `libnetfilter-queue`. |
| CI fit | PCAP-mode *can* run on `ubuntu-latest`, but only after a warm Nix/Docker cache; cold first build exceeds "a few minutes"; live NFQ is fragile on hosted runners. | `l7-adversarial-dryrun.yml` and `l7-adversarial-live.yml` run only when the harness paths change; they publish evidence but do not gate every desync change. |
| Licence | **GPL-3.0** — copyleft; vendoring its source into our toolchain triggers obligations. | Repo-owned, no external copyleft. |
| Maturity | 2-author lab artifact, ~6 stars, **no releases/tags**, crate 0.2.0, last push ~2026-03. | Maintained in-tree with path-triggered dry-run and live workflows. |
| Verdict model | Research-grade accuracy metrics (TPR/TNR vs `labels.csv`, Tables 3/4 vs Scapy/Zeek) — **not a unit-test oracle**. | `runner/schema.py` `{bypassed,blocked,degraded,inconclusive}`, `REPORT_SCHEMA_VERSION=1`, golden per-cell expectations in `tests/test_replay.py` — a CI oracle. |

Adopting CensorLab would mean carrying a Nix + nightly-Rust + GPL research artifact, pinning a known-good commit against unstable deps, and authoring our own pass/fail assertion layer on top (it has no stable "feed bypass-tool traffic → get pass/fail" API) — to obtain interception we already have. Net marginal value is **ideas, not infrastructure**.

## Acceptance criteria — answered

- **[criterion 1] CensorLab built locally and documented (OS, deps, gotchas).** Evaluated from the paper + the `SPIN-UMass/censorlab` source rather than built locally — the evidence-based "not-worth-it" branch this spike authorises. Documented profile: Linux-only live operation; NFQUEUE + iptables/nftables steering; `CAP_NET_ADMIN`/`CAP_NET_RAW` (or `sudo`); Nix-first toolchain (nightly Rust, `libffi`, RustPython, ONNX `ort` rc); offline **PCAP mode** is the only privilege-free path. Gotchas: cold build is slow; Docker base needs `filter-syscalls = false`; unstable git-pinned deps; GPL-3.0; no tagged releases. A local build was deliberately **not** performed because the decision is reject-the-dependency.
- **[criterion 2] One middlebox-like scenario replayed against ≥2 named arms with captured verdicts.** Satisfied by the **existing** harness rather than by CensorLab: `runner/replay.py::replay_matrix()` sweeps `patterns × desync_modes` from `matrix.json`, classifies via `runner/classifier.py::verdict_for()`, and emits `verdict-report.json` + per-cell pcap evidence. The desync-mode fixtures (`fixtures/desync_modes/*.json`: `split_offset_3_chlo`, `tlsrandrec_profile_a`, `quic_initial_with_blocked_sni`, …) are by-behaviour analogues of the six transparent-mode arms. The spike's question is "does CensorLab add replay we lack" — it does not.
- **[criterion 3] Coverage verdict — six arms + DoH/DoQ classifier, or partial.** **PARTIAL.** The six canonical arms (`seg_pre/mid/post_sni`, `rec_pre/mid_sni`, `two_phase_send` — `KNOWN_STABLE_ARMS` in `native/rust/crates/ripdpi-desync/src/tests/dmap_ambiguity_probe.rs`) are covered **by analogy only**: `tspu` replays hand-authored JSON approximations, not the arms' real emitted bytes, and `two_phase_send`'s timing/phase-gap dimension is unrepresented (fixtures carry no timestamps). The **DoH/DoQ classifier is NOT exercised** — `packet_parser.py` emits `sni/alpn=None` for all UDP (no QUIC header-protection removal / Initial decryption), and there is no DNS-message or port-853 path. CensorLab is *also* weak here (no built-in DoH/DoQ dissector, QUIC Initial SNI only with externally-derived keys), so adopting it would not close this gap out of the box.
- **[criterion 4] Decision recorded with the next concrete action.** REJECT-dependency / FORK-ideas, below.

## Honest gaps in `tspu` that motivate the fork (not the adoption)

From the harness audit (`runner/*.py`, `patterns/*.py`):
1. **No stateful TCP/QUIC reassembly** — a split/record-fragmented ClientHello is scored `bypassed` because no single packet carries the SNI (`tlsrandrec_profile_a`, `split_offset_3_chlo`); a real stream-reassembling DPI would still catch it. (README: "Not in v1.x".)
2. **No DoH/DoQ classifier + no QUIC Initial decryption** — criterion 3's open item.
3. **No timing/phase model** — fixtures have no timestamps; `two_phase_send`'s `phase_gap_ms`, delayed-RST, and residual-censorship windows can't be expressed.
4. **Effects classified, not executed end to end** — `verdict_for()` marks a cell `blocked` on a single matched packet. Dry-run does not inject an RST, mangle the ClientHello, or observe the flow's real fate; live mode can only accept or drop the current packet.
5. **Five hardcoded primitives vs a programmable censor language** — adding a behaviour means writing a new `patterns/<x>.py`, not scripting a strategy.
6. **No ML/DPI-censor emulation** — `tspu` cannot model entropy/popcount or ONNX-classifier "futuristic" censors at all.

## Next concrete actions (fork-in, prioritised) — file as follow-ups when scheduled

1. **Close the DoH/DoQ gap (highest value, directly addresses criterion 3):** add a QUIC-Initial key-derivation + DNS-message path to `packet_parser.py` and a DoH(`:443` TLS-SNI to known resolvers)/DoQ(`:853`) classifier pattern. Borrow CensorLab's `parse_initial`/`parse_client_hello` approach; keep our stdlib-only, no-scapy posture.
2. **Add stateful reassembly** so split-ClientHello arms are scored on the reconstructed SNI, removing the false `bypassed` verdicts (gap 1). Borrow CensorLab's reassembly idea, not its code (GPL).
3. **Port the ML-censor idea as an optional pattern:** an ONNX entropy/length classifier pattern behind a feature flag, to emulate ML/DPI censors `tspu` can't model today (gap 6). Lowest priority; only if ML-censor emulation becomes a first-class requirement.

Do **not** port CensorLab's interception plumbing, Nix toolchain, or PyCL/CensorLang runtimes — `tspu` already has equivalent or simpler infrastructure, and the GPL-3.0 licence makes source reuse costly.

## Sources

- arXiv:2412.16349 — "CensorLab: A Testbed for Censorship Experiments" (Sheffey & Houmansadr, SPIN-UMass / UMass Amherst, PoPETs track).
- github.com/SPIN-UMass/censorlab (GPL-3.0, crate 0.2.0, no releases); docs `censorlab.cs.umass.edu`.
- In-repo: `test-lab/chaos/tspu/{patterns,runner,tests}`, `matrix.json`, `.github/workflows/l7-adversarial-dryrun.yml`, `.github/workflows/l7-adversarial-live.yml`, `scripts/ci/run-tspu-dryrun.sh`, `scripts/ci/run-tspu-live-smoke.sh`, `native/rust/crates/ripdpi-desync/docs/dmap_ambiguity_analysis.md`.
