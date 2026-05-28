# ripdpi-routing

**Layer:** L3 -- domain logic.

`ripdpi-routing` evaluates routing rules in first-match-wins order and returns outbound actions for incoming flows.

## Boundaries

- Rule matching and action modeling belong here.
- Android rule editing, asset lifecycle, and actual socket dispatch belong outside this crate.

## Checks

Run focused checks with `cargo test -p ripdpi-routing`.
