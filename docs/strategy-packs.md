# Strategy-Pack Authoring Notes

This document is for offline strategy-pack authors. For the operational runbook (catalog sources, signing, rollout) see [strategy-pack-operations.md](strategy-pack-operations.md).

## Fixed-Config Protocols

Some outbound protocols are **server-coordinated fixed config**: their runtime params are part of the server's configuration and must match it exactly. The strategy learner rotates TLS arms, QUIC variants, direct-mode verdicts, and similar knobs — but for a fixed-config protocol it must treat each profile as opaque and never emit a candidate arm that mutates the profile's params.

The catalog schema carries this constraint in the `fixedConfigProtocols` field of `StrategyPackCatalog`:

```json
{
  "schemaVersion": 3,
  "fixedConfigProtocols": ["amneziawg"],
  "packs": []
}
```

- The field is a list of protocol-type identifiers (lowercase).
- When the field is absent, it falls back to the schema default, which already includes `amneziawg`. Older bundled catalogs therefore keep the constraint without a re-issue.
- Matching is case-insensitive and trim-tolerant.

### AmneziaWG

`amneziawg` is in the default `fixedConfigProtocols` list. AmneziaWG's obfuscation params — `Jc`, `Jmin`, `Jmax`, `S1`–`S4`, `H1`–`H4`, `I1`–`I5` — are negotiated as part of the server's config. Varying them client-side would break every handshake, so the candidate generator must not produce an arm that touches them.

### Validation

The constraint is enforced on both sides of the JNI boundary:

- **Kotlin** — `StrategyPackCatalog.validateCandidateArm` runs in the pack-validation pass.
- **Native** — `ripdpi-strategy-registry`'s `fixed_config` module mirrors the same semantics (`FixedConfigProtocols::validate_candidate_arm` / `filter_candidate_arms`), so the strategy learner's candidate generator cannot emit a violating arm even if a pack reaches the registry unvalidated.

Both apply the same rule:

- A `StrategyPackCandidateArm` whose `protocol` is a fixed-config protocol and whose `mutatedParams` is non-empty is **rejected**.
- An arm that only selects between existing profiles (`mutatedParams` empty) is **accepted** — the runtime selector may still pick between AmneziaWG profiles within a group; it just must not rewrite an individual profile's params.
- Arms for non-fixed-config protocols may mutate params freely.

Pack authors who add a new server-coordinated protocol should add its identifier to `fixedConfigProtocols` so the learner leaves its profiles alone.
