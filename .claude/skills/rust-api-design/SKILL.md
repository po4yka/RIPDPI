---
name: rust-api-design
description: Design discipline for Rust public APIs — borrowed args, generic-over-references, HRTB-shaped callbacks, Drop placement, value-passing perf.
---

# Rust API Design -- RIPDPI

## Purpose

Encode API-design discipline derived from production Rust pitfalls. Apply these rules when authoring or reviewing public (`pub`) and crate-public (`pub(crate)`) function signatures, struct definitions, and trait bounds anywhere in the 40-crate workspace. Apply every rule to every changed signature, not only the first one in a diff.

## Borrowed args over owned references

**Severity: WARNING**

Accept `&str` / `&[T]` / `&Path` instead of `&String` / `&Vec<T>` / `&PathBuf`. The owned-reference shapes force callers to hold an allocation even when they have a slice. The borrowed shapes accept both.

```rust
// BAD: forces caller to have a String
fn log(msg: &String) {}

// GOOD: accepts &str, String, Arc<str>, Cow<str>, etc.
fn log(msg: &str) {}
```

For generic inputs, prefer `impl AsRef<T>` over concrete `&T`:
```rust
fn open(path: impl AsRef<std::path::Path>) {}
```

Grep for violations: `rg 'fn .+\(&String\|&Vec<\|&PathBuf' native/rust/ --type rust -n`

Reference: `crabbook/borrowed_args.md`

## Don't store `&'a mut H` in struct fields

**Severity: CRITICAL**

Storing `&'a mut H` in a struct field infects every use-site with lifetime `'a`, creating lifetime-propagation failures when you try to store the struct in another struct or return it from a function. This is called **lifetime infection**.

```rust
// BAD: lifetime infection
struct Processor<'a> {
    handler: &'a mut dyn Handler,
}
// Every function that takes `Processor<'_>` must now carry the lifetime.
// Storing in Vec<Processor<'_>> is impossible.

// GOOD: generic H, implement Trait for &mut H and Box<H>
struct Processor<H: Handler> {
    handler: H,
}
impl<H: Handler> Handler for &mut H { ... }
impl<H: Handler> Handler for Box<H> { ... }
// Now Processor<&mut MyHandler> and Processor<Box<dyn Handler>> both work.
```

Macro shorthand to implement the delegation impls without boilerplate:
```rust
macro_rules! impl_handler_for_refs {
    ($T:ident) => {
        impl<H: $T + ?Sized> $T for &mut H { /* delegate all methods */ }
        impl<H: $T + ?Sized> $T for Box<H> { /* delegate all methods */ }
    };
}
```

Grep for violations: `rg "struct .+<'.+>\s*\{" native/rust/ --type rust -n` then check for `&'_ mut` fields.

Reference: `crabbook/impl_trait_references.md`

## HRTB-shaped callbacks are a soundness boundary

**Severity: WARNING**

`for<'a> Fn(&'a T) -> R` (where R does not depend on `'a`) is the correct shape for callbacks that are allowed to save the return value without the reference. The HRTB `for<'a>` prevents the callback from storing `&'a T` — it must extract owned data and return it.

If the callback stores a reference and you used a non-HRTB bound, the borrow checker will eventually fail to compile a valid use site. If you widened the lifetime to avoid the error, you likely have unsound code.

Rule: when a callback must not be able to store the argument reference, use `for<'a>` explicitly. When the callback's return type depends on the argument lifetime (e.g., returning a slice of the input), use GATs or a named lifetime.

Reference: `crabbook/borrowing_in_generic_functions.md`

## `fn(T) -> T` vs `fn(&mut T)` performance

**Severity: WARNING on hot paths**

For structs larger than 4 pointer-sized fields, `fn(T) -> T` forces a `memcpy` in and out per call — the compiler cannot optimize this to in-place mutation because panic semantics require the original to be valid until the function returns.

Decision tree:
- **State-machine transitions** (small structs, no hot path): value-passing is idiomatic.
- **Large structs or hot paths** (per-packet, per-connection, per-tick): use `fn(&mut T)`.
- **Builder pattern** (small struct, sequential calls, typically not hot): `fn(mut self) -> Self` is fine.

Profile before adding value-passing chains to any path in `io_loop`, classifier, or desync engine.

Reference: `crabbook/consume_and_borrowing.md`

## `impl Drop` design rules

**Severity: WARNING**

`impl Drop` on a struct prevents any field from being moved out of the struct — including in `Drop::drop` itself (only `std::mem::take` / `std::ptr::read` via unsafe can extract values). This creates API friction when callers or the destructor logic needs to consume a field.

Rules:
1. Before adding `impl Drop` to a struct, list every field. For each field, ask: "does any code path (drop or non-drop) want to move this out?"
2. If yes: extract the field into a dedicated guard type that wraps it in `ManuallyDrop`. Implement `Drop` on the guard only.
3. If the struct itself must implement `Drop` (e.g., it tracks external resources), use `Option<T>` for fields that need to be consumed: `field.take().map(|f| drop_logic(f))`.

```rust
// Pattern: dedicated guard type
#[repr(transparent)]
struct ResourceGuard(std::mem::ManuallyDrop<Resource>);
impl Drop for ResourceGuard {
    fn drop(&mut self) {
        // SAFETY: only called once from Drop
        let resource = unsafe { std::mem::ManuallyDrop::take(&mut self.0) };
        resource.close();
    }
}
```

Reference: `crabbook/you_dont_want_drop.md`

## Quick review checklist

Apply to every changed public or `pub(crate)` function signature in a diff:

1. Any `&String`, `&Vec<T>`, or `&PathBuf` parameter? → use `&str`, `&[T]`, `&Path`, or `impl AsRef<...>`.
2. Any `&'a mut Trait` stored in a struct field? → use generic `H: Trait` instead.
3. Any callback without `for<'a>` where the caller must not store the reference? → add HRTB.
4. Any `fn(T) -> T` on a struct > 4 pointer fields in a hot path? → use `fn(&mut T)`.
5. Any `impl Drop` on a struct that has a consume-able field? → refactor to guard type.
