# Negative bundle fixtures

Each file is a full bundle whose `ripdpi` object violates the schema in exactly
one way. They are vendored byte-identical into the RIPDPI client repo and pin
the **intentional asymmetry** of the contract:

- **Server (strict):** `tests/unit/test_bundle_schema.py` asserts
  `validate-bundle.py` *rejects* every one of these — covering both schema
  violations and a format-valid-but-wrong `cohort_fingerprint`
  (`neg-fingerprint-mismatch.json`), which the schema alone accepts and only the
  fingerprint check catches. The server is the producer and must never emit a
  malformed bundle.
- **Client (lenient / forward-compatible):** `RipdpiBundleContractTest` asserts
  the parser handles every one *deterministically and without throwing* — it
  ignores an unrecognised `schema_version`, skips an unparseable AWG entry, and
  tolerates unknown/extra fields. A consumer must degrade gracefully, not crash,
  on a bundle a future or buggy server might send.

The filename describes the single injected violation.
