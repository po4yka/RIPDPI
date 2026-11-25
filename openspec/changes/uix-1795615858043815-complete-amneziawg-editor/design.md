## Context

Portfolio task `UIX-1795615858043815` owns this change. A user can create an AmneziaWG profile by hand without an external key tool and save it without connecting.

## Goals / Non-Goals

- Goal: deliver `Complete the AmneziaWG editor` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Name: the editor state carries a `name` beside the form, not a new `AwgEditorField`, because `AwgProfileRepository.save(name, request, existingId)` already stores the name apart from the request. A loaded profile fills the field from its saved name. A blank name saves the existing cohort-derived default (`AmneziaWG` or `AmneziaWG (<cohort>)`), so the endpoint host never becomes a list label unless the user types it. The profile picker and the active-exit label already render `SavedAwgProfile.name`, so no list code changes.
- Key generation reuses the native X25519 generator that WARP registration already ships (`ripdpi-warp-core::generate_warp_keypair` through `RipDpiWarpKeyPairNativeBindings.jniGenerateKeyPair` in `libripdpi-warp.so`). No new crypto code, no new JNI symbol, and no new dependency. `:app` must not compile against `:core:engine`, so `WarpKeyPair` and the `WarpKeyPairGenerator` interface move to `:core:engine-api` in the same package; the native implementation and its Hilt binding stay in `:core:engine`. The `Warp` name stays: renaming it would only widen the diff, and the pair is a plain WireGuard key pair for both users.
- Generation fills only an empty private key, and the Generate button shows only while the key is empty, so it can never overwrite a key the user typed, pasted, or imported. A key that arrives while generation runs also wins.
- The public key is held only in editor memory from the generation until the private key changes, and is shown read-only with a Copy action. It is not stored and not derived again for saved profiles: deriving it would need a new JNI entry point, and the user copies it to the server right after generating. The private key keeps the existing `FLAG_SECURE` window (`SecureWindowEffect` in `RelayEditorScaffold`) and the biometric/PIN reveal gate. The public key is not a secret, so its clip carries no sensitive flag.
- Save: a secondary full-width button at the end of the form, beside the existing Connect top-bar action. It runs the same persistence path as Connect (`persist`, which keeps the stale-draft, saved-row, and subscription-server checks) but skips VPN consent and activation. Save and Connect each wait for the other. A successful Save binds the editor to the row, so a later Save or Connect updates the same row. The top hint reports "Saved." until the next edit.
- Field validation stays as is; `UIX-1795615861956333` owns it.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
