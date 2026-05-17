# Lantern rec-SNI offset coverage analysis

**Date:** 2026-05-16 **Task:** `cross-check-lantern-record-fragmentation-offsets-against-rec-sni-arms` (ledger row 102)

## Lantern offsets

Lantern ("Unbounded" record fragmentation) splits the TLS ClientHello into two TLS records so the SNI extension straddles the record boundary.

Source: <https://github.com/getlantern/lantern-client> (2026-04-20 research snapshot, see `[[ripdpi-android-research-2026-04-20]]`).

| Strategy | Offset base | Delta | Notes |
|---|---|---|---|
| Lantern canonical | `SniExt` | 0 | Always cuts at the SNI extension type-field start. No randomised rotation observed in public builds. |

Only one confirmed split point. No pre-SNI or mid-SLD variants observed.

## RIPDPI arm coverage

### rec_pre_sni

- **Offset base:** `SniExt` (start of SNI extension type field in the record payload)
- **Delta neighbourhood:** `{-2, -1, 0}` (weighted; 60 % land on delta=0)
- **Step kind:** `TlsRec` (TLS record boundary, not TCP segment split)

### rec_mid_sni

- **Offset base:** `MidSld` (byte at the midpoint of the second-level domain label inside SNI)
- **Delta neighbourhood:** `{-2, -1, 0, 1, 2}` (weighted across 10 buckets)
- **Step kind:** `TlsRec`

Source: `ripdpi-desync-runtime/src/capability_policy/transparent_tls.rs` — `weighted_family_delta` and `tls_marker_step`.

## Gap analysis

| Relation | Result |
|---|---|
| Lantern ⊆ rec_pre_sni (SniExt-relative) | **Subset — fully covered.** rec_pre_sni delta=0 matches Lantern exactly. |
| Lantern ⊆ rec_mid_sni | **Disjoint by base.** rec_mid_sni uses `MidSld`, which is hostname-length-dependent and structurally distinct from `SniExt`. For a 10-byte SLD the two positions may differ by 10–20 bytes. |

**No numeric gap in rec_pre_sni.** Lantern's only known split (SniExt+0) is the highest-weight variant of rec_pre_sni (60 % of sessions).

**Structural gap in rec_mid_sni.** This arm targets a complementary position (hostname center rather than SNI header), so it does not cover SniExt+0 for most real hostnames. This is intentional — the two arms serve different DPI bypass scenarios.

## Recommendation

**No change required.** rec_pre_sni already covers the Lantern canonical offset at its canonical delta=0, which is also its most-probable variant. Widening the neighbourhood or adding a new arm is not warranted by the current evidence:

- Adding a dedicated `SniExt+0`-only arm would duplicate rec_pre_sni's dominant variant.
- Widening rec_mid_sni to also target SniExt would conflate two semantically distinct fragmentation strategies.

If future Lantern releases introduce randomised split offsets (e.g. SniExt-1 or SniExt-2), rec_pre_sni already covers those via its existing neighbourhood. No action needed unless SniExt+{>0} offsets appear, which would require adding delta=+1 to rec_pre_sni.
