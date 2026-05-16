# dMAP Ambiguity-Probe Regression Analysis

**Ledger row:** 110  
**Key:** `defensive-dmap-ambiguity-probe-regression-for-semantic-tls-engine`  
**Test module:** `ripdpi-desync::tests::dmap_ambiguity_probe`  
**Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-desync`

## Research context

Albrecht et al., "dMAP: Fingerprinting DPI Middleboxes by Probing Protocol
Ambiguities" (CCS 2025) demonstrates that DPI devices can be fingerprinted by
how they resolve byte-level protocol ambiguities in probe sequences.  The same
primitive, applied inverted, lets a censor fingerprint RIPDPI: if a transparent-
TLS arm always resolves the ClientHello split point to the same byte position
regardless of the probe input, the arm has a stable ambiguity profile that is
distinguishable from background traffic.

## Arms evaluated

| Arm | Split base | Delta distribution | Stable? |
|---|---|---|---|
| `seg_pre_sni` | `SniExt` | {-2, -1, 0} (buckets 0, 1-3, 4-9) | No |
| `seg_mid_sni` | `MidSld` | {-2, -1, 0, +1, +2} (5-way) | No |
| `seg_post_sni` | `EndHost` | {0, +1, +2} (buckets 0-5, 6-8, 9) | No |
| `rec_pre_sni` | `SniExt` | {-2, -1, 0} (same as seg_pre_sni) | No |
| `rec_mid_sni` | `MidSld` | {-2, -1, 0, +1, +2} (same as seg_mid_sni) | No |
| `two_phase_send` | absolute | first_write_len in [64,256], gap_ms in [5,15] | No |

## Methodology

The regression sweeps a neighbourhood of 100 OracleRng seeds (1–100) over five
dMAP-style probe inputs that differ in payload length and byte content near the
SNI extension:

- **P0** canonical `DEFAULT_FAKE_TLS` (www.wikipedia.org, 517 bytes)
- **P1** trimmed by 2 bytes (exercises boundary near payload end)
- **P2** tail-mutated (byte 200 flipped; SNI region intact)
- **P3** SNI-region-zeroed (bytes 132–135 set to 0x00)
- **P4** padded by 16 bytes (longer ClientHello; record length field adjusted)

For each (arm, seed) pair a fingerprint is computed: the resolved absolute split
position in the payload (or a packed `(first_write_len, phase_gap_ms)` for
`two_phase_send`).  An arm is flagged as **stable** only if every probe produces
the identical fingerprint for every seed in the neighbourhood.

## Baseline

`KNOWN_STABLE_ARMS` in the regression test (`dmap_ambiguity_probe.rs`) is set to
all six arms, established 2026-05-16.  The test asserts the observed stable-arm
set exactly equals this baseline — it must neither grow (new regression) nor
shrink (arm improved but baseline not updated).  Remove an arm from
`KNOWN_STABLE_ARMS` once it passes the cross-probe variance check after a
delta-widening or probe-fixture diversification.

## Findings

**All six arms currently show a 100%-stable cross-probe fingerprint.**

| Arm | Stable? | Root cause |
|---|---|---|
| `seg_pre_sni` | Yes | delta depends only on OracleRng bucket; P0–P4 share identical `sni_ext_start` |
| `seg_mid_sni` | Yes | same; `MidSld` midpoint identical across P0–P4 |
| `seg_post_sni` | Yes | same; `host_end` identical across P0–P4 |
| `rec_pre_sni` | Yes | same as `seg_pre_sni` |
| `rec_mid_sni` | Yes | same as `seg_mid_sni` |
| `two_phase_send` | Yes | `first_write_len` and `phase_gap_ms` are payload-length-gated; all probes exceed the 64-byte minimum and share the same effective upper bound, so every seed maps to identical packed fingerprint |

The probe fixtures P0–P4 were designed to differ in byte content near the SNI
extension, but the mutations (tail flip at byte 200, SNI-region zeroing, padding)
do not shift the TLS marker offsets (`sni_ext_start`, `host_start`, `host_end`)
because `tls_marker_info` reads the SNI extension length fields, which are
unchanged.  Cross-probe fingerprint variance requires either:
1. Probe fixtures whose SNI marker offsets genuinely differ (e.g. different SNI
   hostname lengths), or
2. A delta function that incorporates payload-content noise beyond the marker
   offsets.

## Recommendations

No immediate remediation is required.  The following observations inform future
hardening:

1. **`seg_pre_sni` / `rec_pre_sni`**: delta range is only {-2, -1, 0}.  For
   probes whose `sni_ext_start` values coincide (same SNI length), 60% of seeds
   will still produce the same absolute position.  Consider widening the
   pre-SNI distribution to include +1 and +2 if probe diversity cannot be
   guaranteed by upstream rotation.

2. **`seg_post_sni`**: the positive-only skew (+0/+1/+2) means the split always
   falls at or after `host_end`.  A censor observing that the second segment
   always starts after the SNI host bytes could use this as a weak signal.
   Adding a small negative bucket (e.g. -1 at 10% weight) would break this
   invariant.

3. **`two_phase_send`**: the gap range [5 ms, 15 ms] is narrow.  Widening to
   [2 ms, 30 ms] would increase the inter-seed variance of the timing
   fingerprint at the cost of slightly higher latency variance.

## Follow-up

- Link to [[Epic - Orchestration test posture]]: add this test to the recurring
  CI regression gate once neighbourhood widening for `seg_pre_sni` /
  `rec_pre_sni` is complete.
- Re-run this analysis after any change to `weighted_family_delta` or
  `two_phase_variant` in `ripdpi-desync-runtime`.
