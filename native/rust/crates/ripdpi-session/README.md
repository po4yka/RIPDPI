# ripdpi-session

**Layer:** L3 -- domain logic.

`ripdpi-session` contains session-level types shared by runtime, policy, and desync code.

## Dependencies

- **Upstream:** `ripdpi-packets`.
- **Downstream:** runtime policy, adaptive logic, desync runtime, and runtime adapters.

## Boundaries

- Session identity and shared session metadata belong here.
- Execution loops, socket ownership, and Android state belong in runtime or platform crates.

## Checks

Run focused checks with `cargo test -p ripdpi-session`.
