## Context

Portfolio task `UIX-1795661310559744` owns this change. The user can share a saved AmneziaWG server as a complete `.conf` file, from the same server actions menu that relay servers use.

## Goals / Non-Goals

- Goal: deliver `Share AmneziaWG servers as full .conf files` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- `AwgActivationRequest.toWgQuickConf()` in `:core:data:runtime-state` maps a saved server onto `AmneziaWgProfile` and reuses its existing wg-quick writer, so share links and `.conf` files write the same keys. A server without obfuscation is written as plain WireGuard; `S3`, `S4`, and `I1`-`I5` are written only when set.
- `isConfExportable` is the one rule for what a `.conf` can carry: a private key and the UDP carrier. A WS-carrier or keyless server has no Share action.
- Subscription-owned AmneziaWG rows have no Share action, the same as subscription relay members, which are never offered as individual share targets. The row keeps Rename and Duplicate; a duplicate is local and can be shared.
- Share writes the `.conf` to `cacheDir/profile-share/<random>/<name>.conf` and opens the system share sheet through the existing profile FileProvider. Files are removed after a day, on the next app start or share. The conf text is never logged.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
