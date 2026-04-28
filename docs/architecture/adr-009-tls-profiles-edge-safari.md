# ADR-009: Edge and Safari TLS profiles are complete, intentional implementations

**Status:** Accepted  
**Date:** 2026-04-27  
**Scope:** `ripdpi-tls-profiles`

## Context

A Phase 3 cleanup audit observed that `edge.rs` and `safari.rs` are 37 lines
each while `chrome.rs` and `firefox.rs` are 67 lines each, and flagged the
shorter files as potential stubs or under-implemented profiles. The audit
proposed either removing Edge/Safari with a fallback to Chrome (Variant A) or
completing them to full parity (Variant B).

Investigation findings:

1. **Line count difference is structural, not functional.** Each profile file
   contains one `pub const ProfileConfig` struct literal. `chrome.rs` has two
   constants (`CHROME_LATEST` for Android-stable and `CHROME_DESKTOP_STABLE`
   for desktop); `firefox.rs` also has two (`FIREFOX_LATEST` and
   `FIREFOX_ECH_STABLE`). `edge.rs` and `safari.rs` each have one constant.
   The line count scales with the number of constants, not with implementation
   quality.

2. **Safari has meaningfully distinct fingerprint characteristics:**
   - `extension_order_family: "safari_fixed"` — fixed extension ordering, no
     permutation (Chrome uses `"chromium_permuted"`).
   - `grease_style: "none"`, `grease_enabled: false` — Safari does not send
     GREASE values (Chrome uses `"chromium_single_grease"`).
   - `supported_groups_profile: "x25519_p256_p384_p521"` — includes P-521,
     which Chrome and Firefox omit.
   - `record_choreography: "single_record"` — single TLS record layout vs.
     Chrome's `"host_tail_two_record"`.
   - AES-256-GCM before AES-128-GCM in TLS 1.2 cipher priority (Chrome
     reverses this).
   - `client_hello_size_hint: 498` — distinct from Chrome (512) and Edge (509).
   - `ja3_parity_target: "safari-stable"`, `ja4_parity_target: "safari-stable"`.

3. **Edge is Chromium-based but has its own identity in the catalog:**
   - Shares cipher suites, GREASE strategy, and extension permutation with
     Chrome (Chromium-derived browser, expected).
   - `browser_family: "edge"`, `browser_track: "stable"` — distinct from
     `"chrome"` / `"android-stable"`.
   - `client_hello_size_hint: 509` matches `CHROME_DESKTOP_STABLE` (not the
     Android `CHROME_LATEST` value of 512), reflecting Edge's desktop-primary
     distribution.
   - `ja3_parity_target: "edge-stable"`, `ja4_parity_target: "edge-stable"` —
     these are checked against the `phase11_tls_template_acceptance.json`
     contract fixture in `all_catalog_profiles_publish_safe_parity_metadata`.

4. **Both profiles are fully integrated into the catalog and rotation:**
   - Listed in `AVAILABLE_PROFILES` and `DEFAULT_PROFILE_CATALOG`.
   - Present in `DEFAULT_WEIGHTS`: Safari at 10%, Edge at 5%.
   - Exposed in the Android UI selector (`tls_fingerprint_profiles` and
     `tls_fingerprint_profiles_entries` arrays).
   - Covered by `all_catalog_profiles_publish_safe_parity_metadata` which
     validates every catalog profile against the acceptance fixture.

5. **No packet parity tests exist for Edge or Safari** in
   `packet_parity_tests.rs`, but this is consistent with Chrome and Firefox
   which only have builder-level smoke tests in `lib.rs`. The packet parity
   test suite covers protocol-level choreography and is not profile-specific.

## Decision

**Variant C — Document status quo; no functional code changes.**

Both profiles are complete and intentional. The audit finding is a false
positive caused by conflating file length with implementation completeness.

`edge.rs` and `safari.rs` each receive a module-level doc comment explaining
their design rationale — specifically why Edge is Chromium-derived (expected),
why Safari's fingerprint is distinct, and what distinguishes each from
`chrome.rs`.

## Consequences

- `edge.rs` and `safari.rs` are retained unchanged in behavior.
- Both profiles remain in `AVAILABLE_PROFILES`, `DEFAULT_WEIGHTS`, and the
  Android UI selector.
- Adding a second Edge constant (e.g., `EDGE_DESKTOP_STABLE`) or a Safari
  desktop variant would follow the same pattern as `chrome.rs`: add a second
  `const` in the same file.
- Future contributors must not conflate short profile files with stubs; each
  `ProfileConfig` constant is a complete declaration, not scaffolding.

## Alternatives Considered

- **Variant A (remove Edge/Safari, fallback to Chrome):** Rejected. Safari's
  fingerprint is genuinely distinct — removing it reduces mimicry diversity and
  eliminates the only non-GREASE, fixed-extension-order profile in the catalog.
  Edge removal would lose the Chromium/desktop size-hint variant.
- **Variant B (expand to full pcap-level parity):** Out of scope for Phase 3;
  would require captured pcap samples from Edge/Safari on current OS versions.
  Not needed: the existing constants already carry sufficient differentiation
  for JA3/JA4 fingerprint mimicry purposes.
