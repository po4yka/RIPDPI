---
name: rust-code-style
description: >
  Use when writing, reviewing, or refactoring Rust code in the native/rust/ workspace.
  Covers module layout, visibility, file structure, imports, function organization, and error handling.
  Based on epage Rust Style Guide adapted to project conventions.
---

# Rust Code Style

Project-specific Rust style rules for the `native/rust/` workspace. Adapted from the [epage Rust Style Guide](https://epage.github.io/dev/rust-style/) to match existing project conventions. The core principle: **code is technical writing for future readers** -- lead with the most important details, keep related things close together.

## Module Layout

### File-named modules (preferred)

Use `name.rs` + `name/` directory pattern for modules with children:

```
src/
  lib.rs
  engine.rs          # module root (declares sub-modules)
  engine/
    plan.rs
    report.rs
    tests.rs
```

Use `mod.rs` only for deeply nested submodules (3+ levels, e.g., `engine/runners/mod.rs`). Never mix both patterns in the same directory level.

### lib.rs is the Table of Contents

`lib.rs` declares modules at the top, then selectively re-exports the public API:

```rust
mod candidates;
mod classification;
mod connectivity;
mod engine;

pub use engine::{run_engine_scan, EngineReport};
pub use types::{ScanRequest, ScanResult};

#[cfg(test)]
mod tests;
```

Never use glob re-exports (`pub use crate::*`) from `lib.rs`. Always list items explicitly so readers can trace the API surface.

### Avoid `#[path]`

Stick with standard module lookup rules. Exception: modding `build.rs`-generated files.

## Visibility

Three levels only -- nothing else:

| Level | When to use |
|-------|-------------|
| private (default) | Implementation details within a module |
| `pub(crate)` | Items shared across modules within the same crate |
| `pub` | Crate public API, re-exported from `lib.rs` |

Start private. Widen only when a concrete caller in another module needs access. Never use `pub(super)` or `pub(in path)` -- they create brittle coupling to module hierarchy.

## File Structure Order

Within any `.rs` file, items appear in this order:

1. Crate/module attributes (`#![forbid(unsafe_code)]`, `#![allow(...)]`)
2. `mod` declarations (private first, then `pub mod`, then `#[cfg(test)] mod tests`)
3. `use` imports (see Import Style below)
4. `pub use` re-exports
5. Constants and statics
6. Type definitions (structs, enums, type aliases)
7. Trait definitions
8. `impl` blocks -- inherent first, then trait impls
9. Free functions -- public first, then private helpers
10. `#[cfg(test)] mod tests` at the very bottom

### Public before private

Within each group, public items come before private ones. This creates a natural table of contents -- readers see the API surface before diving into internals.

### Types before implementations

Show the struct/enum definition before its `impl` block. Readers need to understand the data shape before seeing methods.

### Inherent impls before trait impls

Associated functions form the core API. Trait implementations augment it. Show the core first.

## Import Style

### Group order

Separate imports into groups with a blank line between each:

```rust
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;

use ripdpi_config::DesyncGroup;
use ripdpi_packets::IS_HTTP;

use crate::types::SharedState;
use crate::util::format_duration;
```

Order: `std` > external crates > workspace crates > `crate::`/`self::`/`super::`

### Compound braces for 2+ items

```rust
// Good: compound for multiple items from same module
use std::sync::{Arc, Mutex};

// Good: single item, no braces needed
use std::collections::HashMap;
```

### Limit imports

Import only commonly used items or traits at the top. For rarely-used items, prefer fully-qualified paths inline to reduce import churn and merge conflicts.

## Function Rules

### Caller before callee

Position calling functions before the functions they invoke. Readers follow top-down:

```rust
pub fn process_request(req: &Request) -> Response {
    let validated = validate(req);
    build_response(validated)
}

fn validate(req: &Request) -> ValidatedRequest { ... }
fn build_response(data: ValidatedRequest) -> Response { ... }
```

### Group related statements

Use blank lines to create "paragraphs" of related statements within a function:

```rust
fn connect(config: &Config) -> Result<Connection> {
    let addr = config.resolve_address()?;
    let timeout = config.connect_timeout();

    let stream = TcpStream::connect_timeout(&addr, timeout)?;
    stream.set_nodelay(true)?;

    let tls = setup_tls(config)?;
    tls.connect(stream)
}
```

### Pure xor mutability

Never mix side effects with pure expressions in the same statement. Either compute and return, or declare then mutate:

```rust
// Good: pure computation
let key = format!("{host}:{port}");

// Good: mutation in its own block
let mut map = HashMap::new();
map.insert(key, value);

// Bad: side effect hidden in expression
let result = map.entry(key).or_insert_with(|| {
    log::info!("cache miss");  // hidden side effect
    compute_value()
});
```

### Iterator combinators must be pure

Closures in `.map()`, `.filter()`, `.collect()` chains must not have side effects. Use explicit `for` loops when you need mutation or logging:

```rust
// Good: pure combinator chain
let names: Vec<_> = items.iter().filter(|i| i.active).map(|i| &i.name).collect();

// Good: for loop for side effects
for item in &items {
    if item.active {
        registry.register(&item.name);
    }
}

// Bad: side effect in combinator
items.iter().for_each(|i| registry.register(&i.name));
```

`.for_each()` is banned via `clippy.toml` `disallowed-methods`.

### Business logic uses if/else and match

Reserve early returns for non-business bookkeeping (null checks, permission guards). Use `if/else` and `match` for mutually exclusive business paths so the structure reflects the domain:

```rust
// Good: business logic is visually clear
match strategy {
    Strategy::Direct => connect_direct(target),
    Strategy::Proxy => connect_via_proxy(target, proxy),
    Strategy::Tunnel => connect_via_tunnel(target, tunnel),
}

// Good: early return for bookkeeping only
if handle == 0 {
    return Err(Error::InvalidHandle);
}
```

## Error Handling

| Context | Crate | Pattern |
|---------|-------|---------|
| Library error types | `thiserror` | `#[derive(thiserror::Error)]` |
| Binary/CLI errors | `anyhow` | `anyhow::Result`, `context()` |
| Test assertions | `anyhow` | `#[test] fn foo() -> anyhow::Result<()>` |
| Propagation | `?` operator | Never `.unwrap()` in non-test code |
| Unsafe-free crates | `#![forbid(unsafe_code)]` | Default for crates that do not need unsafe |

## Naming

- Functions/variables: `snake_case`
- Types: `PascalCase`
- Constants: `SCREAMING_SNAKE_CASE`
- Test functions: `snake_case` (e.g., `fn probe_reports_dns_failure()`)
- No abbreviations in public API names; abbreviations OK in locals when context is clear
- Crate names: `ripdpi-*` prefix with hyphens (Cargo convention)

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Using `pub(super)` or `pub(in path)` | Use `pub(crate)` or restructure modules |
| Glob re-export from lib.rs (`pub use types::*`) | List items explicitly: `pub use types::{Foo, Bar}` |
| `.for_each()` with side effects | Use a `for` loop |
| `.unwrap()` in non-test code | Use `?` with proper error types |
| Mixing mutation and computation in one expression | Separate into distinct statements |
| `mod.rs` for new top-level modules | Use `name.rs` + `name/` directory |
| Importing rarely-used items at file top | Use fully-qualified path inline |
| Private helpers before public functions | Public items first, then private |
| `anyhow` in library crate error types | Use `thiserror` for library errors |
| Missing blank lines between import groups | Separate std / external / workspace / crate groups |
