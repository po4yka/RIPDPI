# Change: Project the started relay into proxy configuration

Task ID: `SVC-1792830780372912`

## Why

Normal relay startup resolves saved profile settings for the relay but starts the proxy from the original preferences. A Hysteria2 profile with UDP enabled can therefore run beside a proxy envelope that reports UDP disabled. The native proxy correctly rejects that envelope before payload traffic.

## What Changes

- Project the successfully started relay identity, UDP policy, and bound SOCKS endpoint into proxy startup for normal and race paths.
- Reject inconsistent rendering and invalid readiness endpoints before proxy startup while retaining cleanup ownership.
- Preserve supported Reality TCP Vision UDP eligibility when imported profiles enter selector runtime storage.
- Preserve resolved UDP policy through engine normalization for every supported relay kind, including AnyTLS, Trojan, Shadowsocks, and supported Reality sub-modes.
- Keep the existing UDP requirement checks and peer authentication checks intact.
- No public wire, storage, or dependency contract changes.

## Capabilities

### New Capabilities

- `resolved-relay-proxy-projection`: keep proxy startup consistent with the exact relay runtime it uses.

### Modified Capabilities

- None.

## Impact

- Shared model capability predicate, imported-profile mapping, service admission, engine preference normalization, service-private startup descriptors, upstream supervisor, shared proxy stack, and JVM tests, and the configuration contract documentation.
- Physical Hysteria and migration acceptance must be repeated after integration. No native or production infrastructure changes.
