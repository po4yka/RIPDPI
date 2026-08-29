## Purpose

Preserve usable, private Android networking while enabling target SDK 37 runtime behavior.

## ADDED Requirements

### Requirement: REQ-T37-LAN — Demand-driven local network permission

The app MUST request ACCESS_LOCAL_NETWORK on API 37 only for operations that require
direct LAN access, including resolved IPv4/IPv6 endpoints and incoming LAN listeners.
Denial MUST preserve unrelated public and same-profile loopback operations, respect
the system-DNS port-53 exception, and never change VPN routing to bypass permission.

#### Scenario: Grant and denial

- **WHEN** a user starts a LAN-dependent operation without permission
- **THEN** the app requests permission in the foreground, executes it after grant,
  or reports a recoverable permission requirement after denial without a DPI verdict.

#### Scenario: Revocation or background start

- **WHEN** permission is revoked or a background entry point needs LAN access
- **THEN** only dependent work stops or defers, no background dialog opens, and the
  user can restore access from the app after a fresh system-permission check.

### Requirement: REQ-T37-TLS — Preserve trust across transport fallback

The app MUST propagate certificate and CT failures without retrying through a weaker
client, use supported NSC ECH values, and retain native/platform trust boundaries.

#### Scenario: Platform trust rejection

- **WHEN** platform HTTP rejects a certificate directly or through a nested cause
- **THEN** no HTTP/2 retry or native fallback can turn that rejection into success.

### Requirement: REQ-T37-RUNTIME — Target SDK and runtime acceptance

Every app variant MUST target API 37 while retaining minSdk 27 and the current build
toolchain. Native loading, back/insets, foreground services, process reconstruction,
and export MUST remain functional with actual Android runtime behavior.

#### Scenario: Android 37 runtime

- **WHEN** an API 37 device runs the built app with no compatibility opt-outs
- **THEN** VPN/proxy lifecycle, permission recovery, UI, TLS and export pass the
  applicable instrumented checks, including native loading on 16-KB pages.

### Requirement: REQ-T37-CI — Preserve and extend Android coverage

CI MUST retain API 27/33/35 coverage, add API 36/37, preserve the API 34 benchmark,
and run a mandatory API-37 LAN smoke with non-loopback endpoints and failure artifacts.

#### Scenario: Missing runtime evidence

- **WHEN** an image, LAN endpoint, physical device, or required test is unavailable
- **THEN** acceptance stays incomplete and reports the exact missing evidence.
