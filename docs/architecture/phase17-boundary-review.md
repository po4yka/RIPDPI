# Phase 17 Boundary Review

Date: 2026-04-18

## Outcome

Phase 17 is closed with targeted extraction, not with blanket module churn.

The stable Kotlin seams are now enforced as package-owned slices:

- `com.poyka.ripdpi.diagnostics.export`
  - archive/export build info, file store, models, redaction, rendering, zip writing, share summary, and share service
- `com.poyka.ripdpi.diagnostics.application`
  - bootstrapper, resolver actions, recommendation store, scan launch metadata, and scan request factory
- `com.poyka.ripdpi.diagnostics.finalization`
  - report persistence
- `com.poyka.ripdpi.diagnostics.queries`
  - detail loader

Compatibility shims remain in the root diagnostics package for app-facing archive and launch types so the external API surface does not need a flag-day rename.

## Enforced Boundaries

- `scripts/ci/verify_diagnostics_boundary.py` still blocks any `:core:diagnostics -> :core:service` dependency or service-package reach-through.
- The same checker now also enforces:
  - extracted Phase 17 files do not regress back into the root diagnostics package
  - the extracted files keep their `export` / `application` / `finalization` / `queries` package declarations

This is the structural guard for the highest-value seam that was already proven stable by the earlier diagnostics split.

## Pruned Extraction Candidates

Two crate-level candidates were reviewed and intentionally pruned from active extraction:

- First-flight IR crate split
  - The Phase 4 seam is real, but current ownership is still shared tightly between `ripdpi-desync` planning and `ripdpi-packets` packet-shaping surfaces. A new crate would add dependency churn faster than it would reduce it.
- Emitter/capability support crate split
  - The Phase 13 taxonomy is stable, but the implementation still lives across runtime lowering, monitor candidate modeling, and diagnostics export. The code does not yet justify a separate crate with a clearer owner than the existing modules.

These are not “forgotten” tasks. They were reviewed and rejected for now because Phase 17’s rule is minimal, justified extraction rather than aesthetics-driven crate growth.

## Final Map

- Diagnostics export/archive ownership is no longer just a directory convention; it is CI-enforced.
- Diagnostics application/finalization ownership is no longer hidden in a root-package services file.
- Rust crate extraction remains intentionally unchanged until one owner can hold the resulting public API without new cross-crate churn.
