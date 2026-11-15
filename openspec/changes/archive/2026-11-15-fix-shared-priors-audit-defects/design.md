# Design

## Scope and owners

Use the existing HTTP client and byte limits. Bound raw bodies before Retrofit buffering, or use the existing raw client with cancellation-safe coroutine adaptation. Keep TLS ownership and timeouts. Resolve URL sources with the same priority as the key. Scale arithmetic only where overflow would change the mean.

Three writers own disjoint downloader, build provisioning and arithmetic paths. The coordinator alone owns task files and the generated board. No serialized schema, lockfile, locale, golden or baseline changes are needed.

## Compatibility and migration

No JNI, protobuf, payload, signing, watermark, storage or public API changes. No migration or release activation. Revert each fix commit independently if needed.

## Validation

Reproduce each defect first. Run core/service SharedPriorsCatalogNetwork tests, build-logic provisioning tests and generated BuildConfig checks, runtime-strategy tests with --locked, just ci-gates, architecture health and locked cargo metadata. Use the machine build gate and no more than four jobs. Run independent pr-reviewer and structured autoreview before commit/integration. Android device and external release acceptance are outside these local regression fixes.
