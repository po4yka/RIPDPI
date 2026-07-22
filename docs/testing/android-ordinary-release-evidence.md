# Local Android ordinary release evidence

The Android release target has 11 DNS, IPv6, and kill-switch gates that are not
supplied by the dual-vantage manifest. They currently fail closed.

## Trust boundary

Ordinary PASS is forbidden until the repository contains an audited,
source-owned verifier that derives observations directly from raw packet and
route artifacts. In particular, the checker does not accept:

- operator-authored PASS or counter fields;
- an arbitrary external collector or plugin;
- public hashes, a copied executable, or a self-authored evidence bundle as
  proof that a physical capture ran;
- JVM or host-only tests as physical packet-path release evidence.

This deliberately leaves no configurable allowlist or generic collector
execution path. When a raw-artifact verifier is implemented, it must be added as
checked-in source with gate-specific parsers and adversarial fixtures before the
checker can enable PASS.

## Current local command

Generate the canonical no-ship result document from an exact clean commit:

```bash
python3 scripts/ci/produce_android_ordinary_gate_results.py \
  --output /private/evidence/android-ordinary-results.json
```

The command emits all exact 11 gates as structured FAIL with
`SOURCE_OWNED_VERIFIER_UNAVAILABLE` and exits non-zero. The result is a valid
checker input, so the release report preserves the concrete blocker rather than
failing schema validation:

```bash
python3 scripts/ci/check_dns_ipv6_killswitch_gates.py \
  --results /private/evidence/android-ordinary-results.json \
  --evidence-manifest /private/evidence/dual-vantage/manifest.json \
  --applies-to android-client-release \
  --expected-source-sha "$(git rev-parse HEAD)" \
  --expected-execution-kind local \
  --expected-execution-id "$LOCAL_EVIDENCE_RUN_ID" \
  --expected-execution-attempt 1
```

Do not turn a missing verifier or physical capability into PASS or N/A.
