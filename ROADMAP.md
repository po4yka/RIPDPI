# RIPDPI Roadmap

The audit roadmap and all follow-on structural, tactical, and integration
roadmaps are complete in repo-owned scope. The only remaining roadmap work is
the unfinished bypass-modernization TLS/ECH track.

## Current Status (2026-04-18)

- Audit roadmap: COMPLETE.
- Architecture refactor: COMPLETE.
- Bypass techniques expansion: COMPLETE in repo-owned scope.
- Integrations / Track S: COMPLETE in repo-owned scope.
- Bypass modernization: Workstreams 1-4 and 6-9 are COMPLETE.
- Bypass modernization Workstream 5 (TLS Templates + ECH): PARTIAL.

## Active Roadmap Documents

- [ROADMAP-bypass-modernization.md](/Users/po4yka/GitRep/RIPDPI/ROADMAP-bypass-modernization.md)
  Remaining product and runtime work for TLS browser-family templates, ECH,
  and acceptance coverage.
- [docs/roadmap-execution-queue.md](/Users/po4yka/GitRep/RIPDPI/docs/roadmap-execution-queue.md)
  Ordered execution slices for the remaining Phase 11 work only.

## Remaining Work

1. Validate packet-level parity for extension ordering, GREASE, supported
   groups, key-share shape, and ALPN template families.
   Status: complete. `ripdpi-tls-profiles` now captures live ClientHello records
   per profile and verifies extension-order family behavior, GREASE presence,
   supported-groups payloads, key-share payloads, and ALPN payloads at the
   packet level.
2. Finish true packet-level record-size choreography instead of metadata-only
   template planning.
   Status: complete. `ripdpi-tls-profiles` now publishes real
   profile-selected record-boundary plans, and `ripdpi-desync` ships packet
   rewrites plus goldens for the desktop Chrome and ECH-aware Firefox
   template families.
3. Add controlled HelloRetryRequest-oriented tactics where server behavior
   justifies them.
   Status: complete. The fake TLS path now ships a controlled
   `google_chrome_hrr` profile that preserves Chrome-family
   `supported_groups` while stripping the `x25519` key share, and the strategy
   probe suite exposes it only as a narrow opportunistic `tlsrec_fake_hrr`
   candidate with TLS-alert-biased ordering.
4. Tie ECH and ECH-GREASE planning to DNS bootstrap and Android runtime policy,
   including fallback behavior.
   Status: complete. DNS bootstrap and first-flight planning are now wired to
   the ECH template metadata, and Android now carries explicit ECH policy,
   acceptance-corpus linkage, proxy-mode notice metadata, and native
   bootstrap/fallback details through the owned-TLS path with fail-closed drift
   checks on ECH-capable selections.
5. Surface proxy-mode or browser-native TLS/ECH suppression paths explicitly.
   Status: complete. Strategy-probe recommendations now flag when proxy mode
   leaves browser-originated TLS or ECH under the browser/OS stack, and the
   suppression notice is surfaced in diagnostics UI and archive export.
6. Finish replacing generic fake packet families with coherent client-profile
   families.
   Status: complete. The remaining lab-only fake families now use explicit
   Chrome-family fake ClientHello profiles too, including `adaptive_fake_ttl`
   and `fake_rst`, so randomized/original-byte mutation no longer leaks into
   the shipped fake-family catalog.
7. Build and maintain live acceptance coverage for each shipped TLS template
   family across major CDN and server stacks.
8. Keep bundled strategy-pack catalog entries aligned with the shipped template
   families and acceptance evidence.

## Removed Completed Roadmaps

The following completed roadmap files were removed because they had become
stale implementation archives rather than active planning documents:

- `ROADMAP-architecture-refactor.md`
- `ROADMAP-bypass-techniques.md`
- `docs/roadmap-integrations.md`

Their shipped scope is now summarized here instead of being preserved as
outdated checklists.
