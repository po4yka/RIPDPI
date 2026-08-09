# SVC-1786272083078316: Activate the signed shared-priors release channel

## Objective

Ship one exact production artifact that can consume an owner-signed shared-priors release while every invalid or unconfigured path remains fail-closed.

## Ownership

- `native/rust/crates/ripdpi-shared-priors/**`
- `core/service/**` shared-priors build configuration, network, worker, and tests
- release CI and exact-artifact verification scripts
- serialized release configuration lane; no private signing material

## Execution

- [ ] SVC-1786272226219182 Add approved public-key and HTTPS release configuration validation #feature !high @item:SVC-1786272083078316
- [ ] SVC-1786272226221574 Prove valid signed apply and fail-secure rejection without replacing prior state #feature !high @item:SVC-1786272083078316 @blocked_by:SVC-1786272226219182
- [ ] SVC-1786272226224464 Wire production refresh status and bounded download-only consumption #feature !high @item:SVC-1786272083078316 @blocked_by:SVC-1786272226221574
- [ ] CIC-1786272226226226 Add hosted release and exact-artifact trust-root checks #feature !high @item:SVC-1786272083078316 @blocked_by:SVC-1786272226224464
- [ ] SVC-1786272226229267 Publish and verify an owner-signed release against the exact artifact #feature !high @item:SVC-1786272083078316 @blocked_by:CIC-1786272226226226

## Verification

- `cargo test -p ripdpi-shared-priors`
- `./gradlew :core:service:testDebugUnitTest`
- focused signed-fixture positive, tamper, wrong-key, missing-config, size-limit, and last-known-good tests
- hosted `task-contract`, Rust, service, and release-verification jobs for the exact commit SHA
- exact APK inspection of public key/URLs and secret scan
- owner publication receipt plus successful download/verify/apply evidence for the exact signed manifest and payload
