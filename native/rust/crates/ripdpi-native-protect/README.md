# ripdpi-native-protect

**Layer:** L5 -- platform / privileged.

`ripdpi-native-protect` owns the process-global VPN socket-protection callback registry used to route upstream sockets through Android `VpnService.protect`.

## Boundaries

- The registry and generation-token behavior belong here.
- JNI callback installation belongs in Android adapter crates.
- The canonical seam for runtime consumers is the `ripdpi-runtime-platform`
  `protect` facade; `ripdpi-relay-core` consumes `SocketProtectionPolicy` only
  through the `ripdpi-relay-tls-transports` re-export so it holds no direct
  registry dependency.
- Relay transport crates currently declare direct dependencies for the policy
  type (and for test fakes). New crates should not add further direct edges;
  follow the `relay-core` re-export pattern or use the facade, and update this
  section plus `docs/architecture/NATIVE_RUST.md` if the grouping changes.

## Checks

Run focused checks with `cargo test -p ripdpi-native-protect`.
