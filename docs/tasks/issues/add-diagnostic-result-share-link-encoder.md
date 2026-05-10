---
title: Add Compact URL-Encoded Share Link for Diagnostic Results
type: task
status: backlog
area: ui
priority: low
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: [add-detection-export-share]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Compact URL-Encoded Share Link for Diagnostic Results #repo/RIPDPI #area/ui #status/backlog 🔽

## Objective

Add `DiagnosticShareLinkCodec` that encodes a diagnostic result summary into a compact base64url string (≤ 200 chars typically), embeds it as a URL fragment of a documented share-page URL, and decodes the same format on the receiving side — letting users share results via short links without revealing IPs or hostnames in the URL itself.

## Context

dpi-checkers' `tcp-16-20/share/encoder.js` + `decoder.js` implement a binary-packed share format: per-endpoint `(alive: 2 bits, dpi: 3 bits)` packed into a `Uint8Array`, prefixed by a commit hash + timestamp + ASN, XOR-obfuscated with a fixed key, and base64url-encoded. Receiving the link reverses the process and renders the original result table.

The format is deliberately **lossy and compact**: only enough to render the result table on the receiving side. It does NOT carry IPs, hostnames, latencies, or error details. The point is to share a *summary* compactly via a link short enough to paste in chat or an issue.

This is distinct from the existing `add-detection-export-share` (which exports full Markdown / JSON for support channels). Share-link is for in-the-flow sharing: post a one-line link, the recipient opens it on the share-page (or in their RIPDPI app), and sees the same result table the sender was looking at.

**Format spec (RIPDPI variant):**
```
fragment = base64url(xor(payload, KEY))
payload  = commit_hash:8 || timestamp_min:24 || asn:24 || items
items    = item[]
item     = (alive:2 || dpi:3) packed lsb-first; padded to byte boundary
```
- `commit_hash`: first 8 bits of git commit hash that produced the asset bundle (so receiver knows which test suite to render)
- `timestamp_min`: minutes since fixed epoch (covers ~30 years in 24 bits)
- `asn`: client AS number (so receiver can render "from AS12389 Rostelecom" context)
- `alive`: `0=NO`, `1=YES`, `2=UNKNOWN`
- `dpi`: `0=NOT_DETECTED`, `1=DETECTED`, `2=PROBABLY`, `3=POSSIBLE`, `4=UNLIKELY`

XOR_KEY is fixed (matches dpi-checkers value); not security, just to ensure the fragment doesn't accidentally render as readable text in logs.

**Privacy note:** since the format carries only categorical signals + ASN (no IPs/hostnames), it's safe to include in shared links even when Privacy Mode is OFF. But Privacy Mode should still suppress the ASN field (replace with `0`) so the link doesn't carry network attribution.

**Reference:**
- `/Users/po4yka/GitRep/dpi-checkers/ru/tcp-16-20/share/encoder.js`
- `/Users/po4yka/GitRep/dpi-checkers/ru/tcp-16-20/share/decoder.js`
- `/Users/po4yka/GitRep/dpi-checkers/ru/tcp-16-20/share/helpers.js`

**RIPDPI placement:**
- Codec: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/DiagnosticShareLinkCodec.kt`
- Share format: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/ShareLinkPayload.kt`
- Share page: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/share/SharedResultRenderScreen.kt`

## Acceptance criteria

- [ ] `DiagnosticShareLinkCodec.encode(payload: ShareLinkPayload): String` returns a base64url fragment
- [ ] `DiagnosticShareLinkCodec.decode(fragment: String): ShareLinkPayload` parses the fragment back; throws `ShareLinkDecodeError` on malformed input
- [ ] `ShareLinkPayload`: `commitHash: Int` (8-bit), `timestampMinutes: Int` (24-bit since fixed epoch), `asn: Int` (24-bit), `items: List<ShareLinkItem>`
- [ ] `ShareLinkItem`: `alive: AliveState (NO | YES | UNKNOWN)`, `dpi: DpiState (NOT_DETECTED | DETECTED | PROBABLY | POSSIBLE | UNLIKELY)`
- [ ] Bit-packing: 2 bits alive + 3 bits dpi per item, packed lsb-first
- [ ] XOR-obfuscation with fixed `XOR_KEY` over the payload (excluding the commit_hash bytes — those stay readable for format-version detection)
- [ ] Round-trip: `decode(encode(p)) == p` for all valid `ShareLinkPayload`s
- [ ] Privacy Mode: when ON, `encode()` zeroes the ASN field
- [ ] Share URL pattern: `https://<docs-base>/share?v=1#<fragment>`; documented base URL configurable via `BuildConfig.DIAGNOSTIC_SHARE_BASE_URL`
- [ ] Receiving: deep-link intent filter on `SharedResultRenderScreen` for the share URL; Android automatically opens the app
- [ ] `SharedResultRenderScreen` decodes the fragment and renders the same result table the sender saw, with a banner: "Shared diagnostic from AS{asn}, {timestamp}. This is a snapshot, not a live test."
- [ ] Unit tests: round-trip; bit-packing correctness; Privacy Mode ASN suppression; decode of malformed fragment throws
- [ ] Integration test: deep-link launch via `adb shell am start -d <share-url>` opens the render screen with correct content

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/DiagnosticShareLinkCodecTest.kt`:
     - `roundtrip_preserves_all_fields()` — encode then decode; assert structurally equal; fails until codec exists
     - `bit_packing_two_items_fits_in_one_byte()` — 2 items × 5 bits = 10 bits; assert encoded payload size correctly accounts for packing
     - `privacy_mode_zeroes_asn()` — `Privacy Mode = ON`; encode `asn = 12389`; decode; assert `asn == 0`
     - `decode_malformed_fragment_throws()` — invalid base64url → `ShareLinkDecodeError`
     - `decode_truncated_payload_throws()` — fragment shorter than minimum size → throws with helpful message
     - `commit_hash_byte_not_xor_obfuscated()` — encode with known commit_hash; assert raw byte readable in fragment without xor reversal (format-version detection)
     - `large_item_list_encodes_within_url_length_budget()` — 50 items; assert encoded length < 600 chars (URL-safe target)
   - `app/src/test/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/share/SharedResultRenderScreenTest.kt`:
     - `deep_link_intent_extracts_fragment_correctly()` — fake intent with share URL; assert ViewModel decodes fragment
     - `render_shows_asn_zero_as_unknown_origin()` — payload with `asn = 0`; assert UI renders "Unknown origin" instead of "AS0"
2. **Confirm red** — `./gradlew :core:diagnostics:test :app:test` — all 9 fail
3. **Implement** — codec, payload model, share screen, deep-link intent filter
4. **Confirm green** — `./gradlew :core:diagnostics:test :app:test`
5. **Refactor** — extract `BitPacker` / `BitUnpacker` utility for reuse

## Definition of done

All 9 unit tests green. Deep-link tested manually via `adb shell am start`. Share URL accessible from suite-result screen; pasting into a chat opens RIPDPI on the recipient's device with the rendered result.
