# Vendored RIPDPI bundle contract

These files are **vendored byte-identical** from the server repo
`ripdpi-vpn-deploy/contract/`:

| File | Canonical source |
|------|------------------|
| `ripdpi-bundle.schema.json` | `ripdpi-vpn-deploy/contract/ripdpi-bundle.schema.json` |
| `ripdpi-bundle.example.json` | `ripdpi-vpn-deploy/contract/ripdpi-bundle.example.json` |
| `ripdpi-bundle.golden-full.json` | `ripdpi-vpn-deploy/contract/ripdpi-bundle.golden-full.json` |
| `cohort-fingerprint.golden.json` | `ripdpi-vpn-deploy/contract/cohort-fingerprint.golden.json` |
| `amneziawg-arm64-version-floor.json` | `ripdpi-vpn-deploy/contract/amneziawg-arm64-version-floor.json` |

The server emits the `ripdpi` object; this client parses it
(`SingBoxSubscriptionParser`). The schema is the single source of truth for
that object's shape, and `RipdpiBundleContractTest` validates this side against
it — the mirror of `tests/unit/test_bundle_schema.py` in the server repo. The
two halves make the contract machine-checkable so it cannot drift silently
between the repos.

**When the server contract changes, re-copy the listed files in the same PR.**
The `x-contract-version` integer in the schema is the drift pin:
`RipdpiBundleContractTest` asserts it equals
`SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION`, so an out-of-date vendored
copy (or a parser that hasn't learned a new version) fails the build.
`cohort-fingerprint.golden.json` is the paired golden: both repos compute the
fingerprint from the same `params` and assert the same `fingerprint`, so the
Python emit-side and the Kotlin parse-side can never disagree on the algorithm.

The published producer currently permits IPv4-only AWG addresses and MTU
576..1500. Android deployed-AWG admission is stricter: ordered IPv4 /32 and IPv6
/128 plus MTU 1280..1420. Keep these files unchanged when testing that client
policy. `AwgContractTestFixtures.withClientDualStackTransport` derives explicit
client-owned input for accepted transport and fingerprint tests; canonical
producer input remains covered for typed rejection and other-protocol import.
The deployment `contract-sync` job compares every non-README file byte-for-byte.
