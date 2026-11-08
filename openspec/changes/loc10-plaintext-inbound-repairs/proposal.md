# Change: Repair plaintext address inbound routing and activation

Task ID: `PRX-1794127994095690`

## Why

The LOC-10 audit found that delayed payload can bypass a domain Block rule, a preferred edge can connect back to the listener, and shipped entry points have no explicit way to select the plaintext address input. Partial initial TLS data also bypasses first-payload assembly.

## What Changes

- Apply destination policy before forwarding application bytes for complete and fragmented plaintext requests. Keep server-first traffic usable.
- Reject listener self-connections after candidate substitution, including fallback candidates.
- Add an explicit `--shadowsocks-plain` option to the shared command-line parser and document its loopback-only, unauthenticated wire format.
- Keep encrypted Shadowsocks outbound separate. No crypto, UDP, LAN access, UI schema, dependency, or environment inheritance changes are included.
- No breaking wire or storage changes are planned. Requests that bypassed policy or reached the listener will now fail.

## Capabilities

### New Capabilities

- `plaintext-address-inbound`: plaintext input framing, policy, candidate safety, and explicit command-line activation.

### Modified Capabilities

- None.

## Impact

- Native proxy runtime, routing, shared configuration parser, CLI, tests, and proxy-engine documentation.
- No JNI/protobuf schema, locale, dependency, golden, deployment, or production infrastructure changes.
