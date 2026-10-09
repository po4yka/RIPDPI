# Change: Preserve routed DNS during proxy peer loss

Task ID: `DNS-1791554915587312`

## Why

A shared proxy route failure can reset encrypted DNS requests. Current failover can treat this as a blocked resolver, persist a block reason, select a public resolver, and rebuild the native tunnel while the peer recovers. The local Xray acceptance test has exposed this recovery race. A proxy transport error alone does not identify a resolver failure.

## What Changes

- Preserve the selected resolver and native tunnel for ambiguous failures on an effective proxy DNS route with a consumed shared upstream.
- Keep failover for direct DNS failures, local native proxy endpoint failures, and resolver-specific evidence.
- Read shared upstream ownership from successful runtime start evidence. Preserve this evidence during DNS-only tunnel refresh.
- Check real peer loss and recovery with separate-UID payloads, DNS receipts, resolver identity, and tunnel establishment observations.
- No breaking schema or wire changes.

## Capabilities

### New Capabilities

- `attribute-routed-dns-failures`: Distinguish shared proxy failures from resolver-specific failures before automatic DNS failover.

### Modified Capabilities

- None.

## Impact

- Android service DNS failover policy and its unit tests.
- Real Xray TUN acceptance tests and local acceptance evidence.
- No new dependency, backend, JNI, protobuf, or persistent schema.
