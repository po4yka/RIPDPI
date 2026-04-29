# RIPDPI Roadmap

This file tracks the **forward-looking** roadmap. Completed audit, architecture,
bypass, modernization, integration, and hardening work lives in git history and
the compact architecture notes.

For completed work see:

- `git log --oneline` — every landed change.
- `docs/architecture/README.md` — compact architecture notes for current
  ownership boundaries, runtime behavior, and remaining follow-ups.

## Open Follow-ups

These items have an owning architecture note or design doc and are not blocking
the current release.

- Offline Learner: shared-priors release infrastructure. The Rust verifier,
  Kotlin Retrofit transport, JNI bridge, and 24h `SharedPriorsRefreshWorker`
  are landed; the pipeline rejects with `NoProductionKey` until the ed25519
  release public key and manifest/priors URLs are embedded. Tracked in
  architecture notes.
- Offline Learner: emulator / sim-to-field calibration. Type-system
  segregation and Android `EnvironmentDetector` are landed; per-family
  calibration factors remain a research spike. Tracked in architecture notes.

## Roadmap Hygiene

- Update this file in the same change as every roadmap-scoped implementation.
- The "Open Follow-ups" table is the contract; if you close an item, remove
  the row in the same commit. If you discover a new item, add the row.
- Do not accumulate dated completed-work sections here. Git history and compact
  architecture notes are the canonical record of completed work; this file is
  the next-step list.
