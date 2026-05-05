# Crabbook Chapter Map — rust-api-design

Each rule in `SKILL.md` traces to a crabbook source chapter.

| Rule | Crabbook chapter |
|---|---|
| Borrowed args over owned references | `crabbook/borrowed_args.md` |
| Don't store `&'a mut H` in struct fields | `crabbook/impl_trait_references.md` |
| HRTB-shaped callbacks | `crabbook/borrowing_in_generic_functions.md` |
| `fn(T) -> T` vs `fn(&mut T)` perf | `crabbook/consume_and_borrowing.md` |
| `impl Drop` design rules | `crabbook/you_dont_want_drop.md` |
| `ManuallyDrop` + `from_raw_parts` caveats (in `rust-unsafe`) | `crabbook/crafting_reference_to_owned.md` |
| Drop not guaranteed (in `rust-unsafe`) | `crabbook/raii_and_memory_safety.md` |
| One `unsafe` breaks local reasoning (in `rust-unsafe`) | `crabbook/unsafe_is_unsafe.md` |
| Manual `unsafe impl Sync` checklist (in `rust-unsafe`) | `crabbook/send_and_sync.md` |
| HRTB pitfalls in Fn callbacks (in `rust-async-internals`) | `crabbook/borrowing_in_generic_functions.md` |
| Async + shared state in event loops (in `rust-async-internals`) | `crabbook/event_loops_and_shared_state.md` |
| Pin necessity in FFI (in `rust-async-internals`) | `crabbook/pin.md` |
| Aliasing via Box (in `rust-sanitizers-miri`) | `crabbook/raii_and_memory_safety.md` |
