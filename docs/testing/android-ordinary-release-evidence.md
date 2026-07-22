# Local Android ordinary release evidence

The Android release target has 11 DNS, IPv6, and kill-switch gates that are not
supplied by the dual-vantage manifest. They currently fail closed.

## Trust boundary

Ordinary PASS is forbidden until the repository contains audited, source-owned
semantic oracles that derive every result directly from raw packet and route
artifacts. The checked-in preflight now validates the private bundle's source,
APK, run, window, vantage, inventory, size, and digest bindings, but it does not
interpret those artifacts as gate success. In particular, the checker does not
accept:

- operator-authored PASS or counter fields;
- an arbitrary external collector or plugin;
- public hashes, a copied executable, or a self-authored evidence bundle as
  proof that a physical capture ran;
- JVM or host-only tests as physical packet-path release evidence.

This deliberately leaves no configurable allowlist or generic collector
execution path. The seven gate-specific packet/route oracles and their
adversarial fixtures must land before the checker can enable PASS. The local
contract suite is `just test-android-ordinary-release-gates`; it does not require
a hosted runner.

## Private raw bundle

The canonical `android_ordinary_raw_bundle_v1` manifest must be an absolute,
single-link, mode-0600 regular file. It binds an exact clean source SHA, distinct
app and androidTest APK digests, a redacted SHA-256 run id, a fresh creation
timestamp, and an absolute current-user-owned mode-0700 artifact root.

The manifest contains the exact seven actions in source order: IPv4-only,
dual-stack, forced revoke, core fault, Wi-Fi/LTE switch, sleep/wake, and Android
always-on block. Each action binds its exact gate inventory, a unique redacted
SHA-256 correlation id, and one observation window. Each action has exactly
three unique mode-0600 single-link files under the artifact root:

- `action-receipt` from the `android-client` vantage;
- `packet-capture` from the `client-underlay` vantage;
- `route-snapshot` from the `android-client` vantage.

Every artifact entry repeats the action window and carries its exact byte size
and SHA-256 digest. Extra, partial, reordered, stale, cross-run, symlinked,
hardlinked, noncanonical, or digest-tampered inputs fail closed. The results
output must be absolute, outside the artifact root, and must not alias any
input.

## Current local command

Generate the canonical no-ship result document from an exact clean commit:

```bash
python3 scripts/ci/produce_android_ordinary_gate_results.py \
  --raw-manifest /private/evidence/android-ordinary/raw-manifest.json \
  --app-apk /private/build/app-release.apk \
  --test-apk /private/build/app-release-androidTest.apk \
  --output /private/evidence/android-ordinary-results.json
```

Without all three raw inputs the command emits `RAW_EVIDENCE_REQUIRED`. With a
valid preflight bundle it still emits all exact 11 gates as structured FAIL,
using the gate-specific `SEMANTIC_*_ORACLE_UNAVAILABLE` blockers, and exits
non-zero. `SOURCE_OWNED_VERIFIER_AVAILABLE` remains false. The result is a valid
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
