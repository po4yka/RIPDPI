# RST-1790427044011189: Prevent signed shared-priors rollback

## Objective

Keep the last accepted signed release order across app restarts before publishing new priors.

## Ownership

One writer owns `native/rust/crates/ripdpi-shared-priors`, the shared-priors JNI and platform adapters, `core/engine` bindings, `core/service` worker, and this change's task/spec files. The marker schema and registry are serialized shared-file lanes.

## Execution

- [x] RST-1790427252517600 Reject rollback and persist signed release marker before registry publication #bug !high @item:RST-1790427044011189
- [x] RST-1790427259666136 Pass private marker path through JNI and cache only successful refreshes #bug !high @item:RST-1790427044011189
- [x] RST-1790427266252901 Run Rust Kotlin JNI and architecture gates and review the combined diff #bug !high @item:RST-1790427044011189

## Verification

Run `cargo test -p ripdpi-shared-priors --locked`, affected Android adapter/JNI checks, `./gradlew :core:service:testDebugUnitTest`, `python3 scripts/ci/check_architecture_health.py`, `cargo metadata --manifest-path native/rust/Cargo.toml --locked`, and `./taskctl validate`. Report an unavailable Android device gate explicitly.
