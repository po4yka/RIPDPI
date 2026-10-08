## Context

The shared AWG/WireGuard mapper ignores parsed DNS and AllowedIPs. Existing subscription refresh preserves the saved values because that mapper did not represent them. The activation request and service policy already contain and use these fields.

## Goals / Non-Goals

- Goal: preserve explicit policy and distinguish omission from an explicit empty list.
- Non-goal: native runtime changes, new route types, IPv6-only activation, fingerprint validation, or app subscription lifecycle changes.

## Decisions

- Add defaulted dnsSpecified and allowedIpsSpecified booleans to parsed subscription models. Existing direct constructors infer presence from a nonempty list. Sing-box uses JSON key presence; INI uses the scoped key presence with the existing case and comment rules.
- Reject malformed present JSON DNS/AllowedIPs lists before mapping. Only literal arrays of nonblank strings may authorize replacement; typed warnings expose only the field name.
- Carry presence into AwgSubscriptionProfile only. Do not extend stored activation or native wire schemas.
- Mapper passes DNS verbatim and routes verbatim when present. Omitted routes keep the existing default. Find IPv4/IPv6 addresses by family instead of list position.
- Refresh applies each supplied policy field, including empty lists, and preserves each omitted field. Preserve the local private key and editor-owned carrier as before.
- Empty AllowedIPs remains invalid under existing requireRuntimeReady; there is no fallback to a broad route. Empty DNS uses the existing app fallback.

- Preserve all syntactically valid source routes, including a family without an interface address. Runtime readiness requires at least one route for a configured family. The derived Android route plan excludes IPv6 routes when no IPv6 interface address exists; Android then blocks that family. IPv6-only routes without IPv6 remain invalid.
- Keep DNS validation strict. A shared effective DNS helper selects profile DNS or fallback and rejects IPv6 DNS without an IPv6 interface before Builder establishment. Both DNS host routes and Builder DNS use that helper.
- Keep the native runtime and CI bundle unchanged. The server and checked-in CI bundle use an IPv4 address with both default routes; this valid source policy must pass Simple seeding without enabling an unsupported IPv6 family.

## Contracts and ownership

- Own runtime-state subscription models/INI/mappers and AwgActivationRequest validation, core:data wrapper/repository, VpnProfileInterface and the DNS helper call in RipDpiVpnService, and relevant data/service/Simple seeder tests. SingBoxSubscriptionParser ownership is limited to network-policy presence and decode guards, separate from the fingerprint writer.
- No schema version, Room column, protobuf, native crate, locale, golden, baseline, or production dependency changes.
- Gates: mapper/parser regressions, AwgProfileRepositoryRoomTest, ConnectionPolicyAwgResolverTest and VpnTunnelRuntimePolicyTest in the combined tree, staticAnalysis, architecture health, and task contracts.

## Risks / Trade-offs

- Applying server policy can replace a local correction when that field is explicitly present; omission preserves local edits.
- Empty routes become an invalid template; silently adding a default route would change the imported routing policy.

## Migration Plan

No storage migration is needed. Existing activation requests contain the same DNS/route fields. Next refresh applies supplied policy, while omitted values remain unchanged. Rollback restores the previous mapper behavior and does not affect schema decoding.
