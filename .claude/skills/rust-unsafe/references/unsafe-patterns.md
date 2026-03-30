# Unsafe Patterns Reference -- RIPDPI

## Safety comment format

Every unsafe block requires a `// Safety:` comment. For unsafe functions, use `/// # Safety` in the doc comment:

```rust
/// # Safety
/// `ptr` must be non-null, aligned to `align_of::<T>()`, and point to
/// `len` initialized values of type `T`. The memory must remain valid
/// and not be mutated for the lifetime of the returned slice.
unsafe fn raw_slice<'a, T>(ptr: *const T, len: usize) -> &'a [T] {
    std::slice::from_raw_parts(ptr, len)
}
```

## JNI macro pattern (ripdpi-android)

The `export_diagnostics_jni!` macro stamps out `#[unsafe(no_mangle)] extern "system"` functions that delegate to an `_entry` function. The entry function handles `with_env`/`Outcome`. When adding a new JNI export, use this macro rather than hand-writing the extern fn.

```rust
macro_rules! export_diagnostics_jni {
    ($name:ident, ($($arg:ident: $arg_ty:ty),* $(,)?), $ret:ty, $entry:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name(env: EnvUnowned, _thiz: JObject, $($arg: $arg_ty),*) -> $ret {
            $entry(env, $($arg),*)
        }
    };
}
```

## Pointer arithmetic rules

```rust
// wrapping_add: safe to compute, no UB even if out of bounds (do not deref)
let p3 = ptr.wrapping_add(2);

// add: UB if result is out of bounds, even without deref
let third = unsafe { *ptr.add(2) };

// offset_from: both pointers must be in the same allocation
let count = unsafe { end.offset_from(ptr) };
```

## Read/Write without creating references

```rust
// ptr::read: copies T out without binding lifetime
let val: u32 = unsafe { std::ptr::read(ptr) };

// ptr::write: writes T without dropping old value
unsafe { std::ptr::write(mut_ptr, new_val) };

// ptr::copy_nonoverlapping: memcpy (UB if overlapping)
unsafe { std::ptr::copy_nonoverlapping(src, dst, count) };
```

## NonNull wrapper

```rust
use std::ptr::NonNull;

let boxed = Box::new(42u32);
let nn: NonNull<u32> = NonNull::new(Box::into_raw(boxed)).unwrap();
let val = unsafe { nn.as_ref() };
// Take ownership back for cleanup
let boxed_again = unsafe { Box::from_raw(nn.as_ptr()) };
```

## Transmute safety table

| From | To | Safe? | Alternative |
|------|-----|-------|-------------|
| `u32` | `f32` | Yes (same size) | `f32::from_bits(u)` |
| `[u8; 4]` | `u32` | Yes | `u32::from_ne_bytes(arr)` |
| `&T` | `*const T` | Yes | `ptr as *const T` |
| `*mut T` | `*const T` | Yes | `ptr as *const T` |
| `&'a T` | `&'b T` (longer) | **No** | Restructure lifetimes |
| `Box<T>` | `*mut T` | Yes | `Box::into_raw(b)` |
| `u8` | `bool` | **No** unless 0/1 | Match on value |
| `u8` | `MyEnum` | **No** unless valid tag | `MyEnum::try_from(u)` |
| `Vec<T>` | `Vec<U>` | **No** | Manual conversion |

## Stacked Borrows rules (Miri model)

1. Each borrow creates a new "tag" on the borrow stack
2. `&mut T` access pops all borrows above it (invalidates them)
3. `&T` access is valid as long as the shared reference is on the stack
4. Raw pointer access: tag must still be on the stack at time of access

Violation example:
```rust
let mut x = 5u32;
let raw = &mut x as *mut u32;
let shared = &x;         // shared borrow pushed onto stack
let _ = unsafe { *raw }; // VIOLATION: raw's &mut tag was invalidated by &x
```

## Miri testing

```bash
# Run unsafe tests under Miri
cargo +nightly miri test

# With stricter provenance checking
MIRIFLAGS="-Zmiri-strict-provenance" cargo +nightly miri test

# Isolate a specific test
cargo +nightly miri test test_my_unsafe_fn
```

## Clippy lints for unsafe

```bash
cargo clippy -- -W clippy::undocumented-unsafe-blocks \
               -W clippy::multiple-unsafe-ops-per-block \
               -W clippy::transmute-undefined-repr \
               -W clippy::ptr-as-ptr

# Deny undocumented unsafe blocks in production code
#![deny(clippy::undocumented_unsafe_blocks)]
```
