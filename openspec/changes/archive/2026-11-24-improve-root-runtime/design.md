## Context

The existing per-device authorization flag records consent and a past grant. The service owns a separate connection and helper lifecycle.

## Goals / Non-Goals

- Goal: Show permission and session state, and make explicit recovery possible.
- Goal: Bound helper startup and keep the VPN barrier during a compatible fallback.
- Goal: Verify the shipped backend and scripts at the privileged execution boundary.
- Non-goal: Transfer authorization between devices or expose additional privileged commands.

## Decisions

- Keep the existing authorization flag. Put observed access and helper state in the extended service and derive the settings display from both sources.
- Use the selected connection policy to decide if it needs the helper. Use the service policy replacement path for configuration changes and preserve cleanup before fallback.
- Bind trusted asset verification to the helper build. Use content that the app cannot replace after verification when starting nfqws2.
- Keep root-specific types, strings, and assets in extended source sets. Do not change the capture authorization contract.

## Contracts and ownership

- Android app and service own the UI and lifecycle. The native supervisor and Android asset build own privileged asset verification. No settings migration is needed.
- The integration owner controls Android locale files and the edition checks. The native owner controls the nfqws2 build and Rust supervisor.

## Risks / Trade-offs

- Treat unknown command failures as unavailable. Stop automatic retries after loss of access when it cannot be recovered safely.
- If privileged cleanup cannot finish, preserve the VPN failure barrier and report the error.

## Migration Plan

Existing local grants remain valid. Verify both editions, the Play package boundary, and the separate capture contract.
