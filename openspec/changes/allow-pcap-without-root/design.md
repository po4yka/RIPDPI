## Context

The VPN runtime binds a capture controller to the live tunnel. It currently combines the capture consent and root mode settings.

## Goals / Non-Goals

- Use the existing consent setting for recording.
- Keep the native writer and the export contract intact.

## Decisions

- Bind the controller to the capture consent flow. Keep the controller checks for a live tunnel and a valid session handle.
- Show capture actions in Diagnostics. Show the Status run option when capture consent is enabled.

## Contracts and ownership

The app owns capture actions and disclosure text. The VPN service supplies consent and the live tunnel. One writer updates all ten locales.

## Risks / Trade-offs

The capture action can be visible before the VPN connects. The existing capture controller rejects the request and the route reports the failure.

## Migration Plan

The existing capture setting and stored files are used as-is. Revert the implementation commit to restore the previous access rule.
