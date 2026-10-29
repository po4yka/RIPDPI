# Vendored RIPDPI bundle contract

These files are **vendored byte-identical** from the server repo
`ripdpi-vpn-deploy/contract/`:

| File | Canonical source |
|------|------------------|
| `ripdpi-bundle.schema.json` | `ripdpi-vpn-deploy/contract/ripdpi-bundle.schema.json` |
| `ripdpi-bundle.example.json` | `ripdpi-vpn-deploy/contract/ripdpi-bundle.example.json` |
| `ripdpi-bundle.golden-full.json` | `ripdpi-vpn-deploy/contract/ripdpi-bundle.golden-full.json` |
| `device-migration-metadata.schema.json` | `ripdpi-vpn-deploy/contract/device-migration-metadata.schema.json` |
| `device-migration-receipt.schema.json` | `ripdpi-vpn-deploy/contract/device-migration-receipt.schema.json` |
| `device-migration.golden.json` | `ripdpi-vpn-deploy/contract/device-migration.golden.json` |
| `cohort-fingerprint.golden.json` | `ripdpi-vpn-deploy/contract/cohort-fingerprint.golden.json` |
| `amneziawg-arm64-version-floor.json` | `ripdpi-vpn-deploy/contract/amneziawg-arm64-version-floor.json` |

Other files in this directory are contract schemas, examples, and fixtures that
tests read. They are listed here by purpose:

| File | Purpose |
|------|---------|
| `ripdpi-bundle.hysteria-port-hopping.json` | Sing-box document with a Hysteria2 port-hopping outbound |
| `ripdpi-bundle.payload-failover.json` | Deterministic deployer output for the explicit payload-failover policy |
| `cascade-asn-attestation.schema.json` | Schema: RU cascade candidate ASN attestation |
| `cascade-leg-health.schema.json` | Schema: cascade ingress-to-egress leg health evidence |
| `cascade-leg-probe-config.schema.json` | Schema: operator-side authenticated cascade leg probe configuration |
| `network-exposure-artifact.schema.json` | Schema: network exposure artifact |
| `network-exposure-policy.schema.json` | Schema: network exposure policy |
| `observability-evidence.schema.json` | Schema: observability evidence |
| `observability-expected-inventory.schema.json` | Schema: observability expected inventory |
| `observability-expected-inventory.example.json` | Example: observability expected inventory |
| `observability-metric-manifest.schema.json` | Schema: observability metric manifest |
| `observability-metric-manifest.example.json` | Example: observability metric manifest |
| `observability-topology.schema.json` | Schema: observability topology |
| `probe-matrix-config.schema.json` | Schema: probe matrix configuration |
| `probe-matrix-report.schema.json` | Schema: probe matrix report |
| `probe-matrix-target.schema.json` | Schema: secret-bearing probe matrix target profile |
| `protocol-liveness.schema.json` | Schema: protocol liveness sentinel policy |
| `real-vps-awg-nat-evidence.schema.json` | Schema: real VPS AmneziaWG NAT evidence (v4) |
| `snell-refinement-result.schema.json` | Schema: Snell refinement result |
| `negative/` | Invalid bundle documents that the bundle schema must reject (see `negative/README.md`) |

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

`ripdpi-bundle.payload-failover.json` is the full deterministic deployer output for the explicit payload policy. Its two synthetic relay members and independently named HTTPS controls exercise strict import and persistence. The companion schema remains byte-identical to the deployer source.

`DeviceMigrationContractTest` consumes the shared migration vectors to pin exact
HTTP entity-byte hashing, receipt fields and HMAC framing. The receipt is an
explicit offline export; the shared key is retained only in sealed subscription
state and is never included in that export.
