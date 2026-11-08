## Context

The current plaintext handler bypasses the common connect/relay control flow and passes arbitrary read tails directly as complete seed payloads. Candidate checks precede preferred-edge substitution. Shared parser callers provide default startup environment and expose no raw-input option.

## Goals / Non-Goals

- Goal: repair F1, F2, and F3 with independent regression evidence and one fix commit per finding.
- Non-goal: encrypted inbound, UDP, plugins, UI/protobuf schemas, dependencies, LAN access, or authentication expansion.

## Decisions

- Consume only the address header so application data uses the existing first-payload assembler. Reuse common connect/relay policy and provisional server-first handling. A provisional SYN may use IP/default policy; the first application write must use the final payload policy. Test HTTP and TLS fragmentation and preserve original bytes.
- Record the actual bound endpoint once before RuntimeState clones. Check each final connect candidate and upstream endpoint using the same canonical self-target predicate as the input guard.
- Add --shadowsocks-plain to the shared explicit parser. Keep ambient startup environment disabled. Document the flag as plaintext and keep mode precedence and access validation.
- Investigate H1 with a regression before the F1 fix. Pending SYN cancellation belongs to the active LOC-07 task and must not be duplicated.

## Contracts and ownership

- Primary: handshake, protocol reader, first-payload/policy path, opt-in bounded HTTP/TLS request assembly in proxy-runtime-desync-adapter and its canonical packet-method prefix helper, and its regression tests; portfolio, OpenSpec and final gates.
- F2 writer: runtime state listener endpoint and routing candidate guard, self-loop tests. Primary applies the coordinated handshake predicate replacement.
- F3 writer: shared configuration option/tests, CLI help/binary test, proxy-config public integration test, proxy-engine docs.
- Separate worktrees for each writer. No serialized dependency, locale, protobuf, JNI, golden or baseline changes. Generated API/architecture output has one combined-tree owner: primary.

## Risks / Trade-offs

- Server-first and delayed hostname policy compete: provisional connections may precede hostname discovery, but application bytes remain gated.
- Hostname-policy request assembly is opt-in, with a 64 KiB HTTP-head bound and the existing 75 ms assembly deadline. Partial recognized headers fail closed; canonical non-HTTP methods and policy-off binary flows retain passthrough.
- Header reads must not consume payload or reclassify ATYP=04 as SOCKS4.
- Actual ephemeral port must replace requested port zero before candidate validation.
- A new explicit flag must not imply encrypted Shadowsocks support or silently read Android environment.
- Verify with narrow red/green tests, affected crate tests, scoped clippy/format, independent review, taskctl validation, ci-gates, architecture health, and cargo metadata --locked. Use build-gate and isolated target outputs. Hosted CI and physical-device tests are not required for this host-native change; report them separately.

## Migration Plan

No persisted or wire data migration. Integrate separate fix commits by fast-forward after rebase and required gates. Rollback uses normal revert commits; never weaken access/policy checks. Keep the audit report unchanged as historical evidence.
