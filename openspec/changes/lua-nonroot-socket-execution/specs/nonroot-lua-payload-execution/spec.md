## Purpose

Run payload-only Lua strategies through existing protected sockets on non-root Android while preserving packet data, flow state, and root-mode compatibility.

## ADDED Requirements

### Requirement: REQ-LUA-SOCKET-001 — Configuration and ownership
The service MUST pass configured Lua YAML and its app-private script directory to the proxy in non-root mode. The TUN MUST skip those Lua steps when the proxy owns them. Root mode MUST retain the existing raw Lua path.
#### Scenario: Non-root VPN startup
- **WHEN** a non-root session starts with Lua YAML
- **THEN** the proxy receives the same YAML and an absolute script jail, and TUN does not run the Lua step.

### Requirement: REQ-LUA-SOCKET-002 — Payload execution
The proxy MUST execute explicit Write, payload-only MODIFY, and ordered TCP Split on its existing sockets. It MUST lower rawsend_dissect output only when the complete TCP replacement is contiguous, preserves all bytes, and has no header or send-option mutation. It MUST retain rule matching and on_fail policy.
#### Scenario: Supported TCP and UDP plans
- **WHEN** a matching Lua strategy returns a supported plan
- **THEN** TCP writes the ordered payload segments or UDP sends the replacement as one real datagram without opening a raw socket.

### Requirement: REQ-LUA-SOCKET-003 — Failure before side effects
The planner MUST reject unsupported actions before any socket emission and apply on_fail. A failed socket write MUST report committed bytes and MUST NOT replay the original payload after partial delivery. Failed TUN replacement injection MUST retain the original packet; pure explicit DROP MUST still drop it.
#### Scenario: Raw action after a payload action
- **WHEN** a Lua plan contains Write followed by an unsupported raw action
- **THEN** no planned bytes are sent before on_fail is applied.
#### Scenario: TUN injection is denied
- **WHEN** replacement raw injection fails
- **THEN** the original packet is forwarded.

### Requirement: REQ-LUA-SOCKET-004 — Flow state and privacy
The runtime MUST isolate persistent Lua state by live TCP or UDP flow and release it on teardown. It MUST preserve socket protection and script-jail enforcement. It MUST NOT persist payload or endpoint data for this feature.
#### Scenario: Two connections and teardown
- **WHEN** two flows call the same Lua function and one closes
- **THEN** their state remains separate and the closed flow releases its Lua registry entries.
