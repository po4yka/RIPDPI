## Context

The previous libXray facade and native patches target an older upstream tree.
The new release exposes `Invoke`, changes runtime cleanup, and changes SOCKS UDP
dispatch. The existing gRPC trailer fix is now upstream.
The real Android build also exposed an unsupported `net.zoneCache` reference
from anet, imported by Pion STUN's default network constructor.

## Goals / Non-Goals

- Goal: preserve managed runtime control, fail-closed protection, and private UDP
  behavior on the current pinned upstream release.
- Non-goal: change profiles, routing policy, persisted settings, or remote wire
  protocols. A local test does not establish physical-device acceptance.

## Decisions

- Keep the current Android engine contract and adapt the narrow native boundary
  to the upstream API. Use the current upstream runtime owner for cleanup.
- Rebase the protection patch on the new socket and UDP dispatch paths. Retain
  real-socket negative tests for rejected protection and unauthorized UDP use.
- Remove the upstreamed gRPC patch from the mandatory patch set. Verify the
  upstream behavior directly and keep source/module checksums mandatory.
- Produce and inspect a real AAR before claiming native API compatibility.
- Patch checksum-pinned Pion STUN 3.1.7 so its Android default dialer does not
  enumerate interfaces that dialing does not use. Resolve named IPv6 scopes
  through public `SIOCGIFINDEX`, then use numeric scopes with public Go net APIs.
  Keep non-Android and custom-network behavior. Do not disable linker checks or
  replace Android interface enumeration with restricted netlink calls.
- Recognize canonical successor Go pseudo-versions in the existing REALITY/XHTTP
  compatibility guard. Accept a successor only when its base release contains
  the known fix. A later commit timestamp does not prove that an earlier base
  contains the fix; even a pre-regression ancestor can acquire the bug. Keep
  stable release policy and reject malformed or unknown version strings.

## Contracts and ownership

- `:core:engine` owns the Kotlin native adapter. `:core:service` keeps runtime
  lifecycle ownership and its existing engine API.
- Native preparation owns source commits, module sums, and patch application.
- Artifact verification owns AAR digests, exported API checks, and ABI inventory.
- No Rust crate, protobuf, stored profile, or public remote wire schema changes
  are planned. RUF1 remains an authenticated private local transport.
- The main agent owns all main-checkout writes, including the version catalog
  and patch manifest. The worker prepares a temporary isolated patch.

## Risks / Trade-offs

- Lost partial-start or failed-close ownership could leak resources: preserve
  failure-injection tests and sticky owner assertions. A consumed native close
  operation is not repeated; later stop calls retain the recorded failure.
- New UDP dispatch could bypass admission: test source, nonce, replay, DNS-pin,
  authority cap, and runtime-stop cleanup with real loopback sockets.
- A matching version label could hide a broken artifact: inspect the produced
  API and verify source, patch, module, recipe, and artifact digests.
- Scope normalization must preserve numeric scopes, caller-owned UDP addresses,
  and irrelevant zones on unspecified or IPv4 addresses. Cover real IPv6 socket
  exchange, custom networks, and the unprivileged ioctl implementation.

## Migration Plan

1. Prepare the current pinned source and replace incompatible native adapters.
2. Update paired source pins and apply only the remaining mandatory patches.
3. Run host protection tests, Kotlin adapter tests, and negative provenance tests.
4. Build the AAR and run artifact verification, then run repository source gates.
5. Roll back with a reviewed revert that restores code, source pins, and patch
   policy together. Deployment and physical-device acceptance stay separate.
