## Context

Standalone profiles use the existing AWG codec, boringtun handshake, smoltcp
netstack, JNI adapter, and Android TUN-to-SOCKS composition. This change makes
the editor own an explicit user activation and verifies the production runtime
against an independently implemented, rootless local AmneziaWG peer.

## Goals / Non-Goals

- A validated saved profile can start from idle or replace the active VPN path.
- Success means the requested transport reached runtime application, not merely
  that Android accepted a service intent.
- DNS, routes, MTU, IPv4 and IPv6 follow the selected profile.
- No device installation, remote server deployment, new production dependency,
  or changes to external deployment contracts are included.

## Decisions

1. The editor requests VPN consent through the existing permission bridge. A
   matching attempt and granted permission are required before activation;
   denial, cancellation, and duplicate callbacks cannot start the VPN.
2. `VpnTransportActivationController` dispatches an explicit user-start command
   carrying the existing request ID and exact transport target. It supersedes
   startup recovery but does not invoke the Simple primary-profile reset.
   Explicit Start/Stop intents carry the accepted generation. Stale delivery,
   queued work and suspended Simple preparation cannot supersede newer intent;
   Stop delivery does not advance the same generation twice. Cold start claims
   the apply tracker inside the serialized lifecycle start
   transaction; warm replacement uses the existing TUN-preserving path.
3. The activator persists the selected opaque profile ID and native provider,
   then waits for the runtime acknowledgement. A separate selection mutex lets
   policy resolution read the profile during that wait. Rollback happens only
   after the tracker proves cleanup safe and while this selection still owns
   the durable pointer. Automatic Simple fallback must not erase a newer
   standalone selection. The existing SharedPreferences-backed provider store
   exposes synchronous current/update operations; selection publication and
   dispatch share the intent arbiter without suspending under its lock.
4. Xray relinquishes provider ownership only after a successful stop result.
   The service retains its established TUN until native replacement is ready.
5. `AwgActivationRequest` carries service-owned DNS and AllowedIPs. The JNI DTO
   remains unchanged. Android routes are normalized numerically without DNS
   lookup; configured DNS host routes and servers accompany the profile routes.
   Profile IPv6/MTU override global interface settings, including refreshes.
   Explicit AWG selection disables command-mode resolution without changing
   the saved command configuration.
6. The native runtime publishes the actual bound SOCKS address, preserves UDP
   source addresses, routes both inner address families, and owns connection
   tasks and virtual sockets through shutdown/error cleanup. Active invalid
   obfuscation parameters fail instead of falling back to plain WireGuard.
7. A pinned `amneziawg-go` test-only peer exchanges real encrypted TCP and UDP
   payloads with the production runtime over loopback. Cargo features keep the
   opt-in peer-dependent test out of ordinary unit runs; the existing network
   E2E CI entry point explicitly runs it. Runner timeouts reap process groups.

## Risks / Trade-offs

The loopback peer proves protocol interoperability and native data flow, not
Android device behavior or remote network performance. JVM tests prove consent,
selection, and lifecycle policy separately. The independent Go module is a test
fixture, pinned with module checksums; it is not shipped with the Android app.

## Migration Plan

Run focused regressions, native peer interoperability, combined-tree checks and
hosted CI. Integrate atomic commits through a fast-forward merge to main. Keep
the portfolio item in review until its required evidence is recorded; archive
only through taskctl.
