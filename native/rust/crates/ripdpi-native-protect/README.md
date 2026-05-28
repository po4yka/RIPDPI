# ripdpi-native-protect

**Layer:** L5 -- platform / privileged.

`ripdpi-native-protect` owns the process-global VPN socket-protection callback registry used to route upstream sockets through Android `VpnService.protect`.

## Boundaries

- The registry and generation-token behavior belong here.
- JNI callback installation belongs in Android adapter crates.
- Runtime callers should use `ripdpi-runtime-platform` facades unless they are the Android adapters that install the callback.

## Checks

Run focused checks with `cargo test -p ripdpi-native-protect`.
