#!/usr/bin/env python3
"""
check_unsafe_boundaries.py -- CI guard against safe APIs wrapping unsafe operations
without explicit, documented preconditions.

POLICY
------
A safe (`pub fn` / `pub(crate) fn`) public surface must not silently extend an unsafe
contract: every unsafe operation reachable from safe code must either be impossible
to misuse (enforced by types/lifetimes/visibility/RAII) or documented and allowlisted
through this guard.

The companion file `scripts/ci/unsafe-boundary-allowlist.toml` records the EXISTING
unsafe-boundary surface snapshotted from main at the time this guard was introduced.
Each entry must include the file, the pattern, why the boundary is sound, who enforces
the preconditions, the owner, and a review date.

The allowlist exists to grandfather existing code while preventing new occurrences.
The correct response to a guard failure is to:
  1) Restructure the API so the unsafe operation cannot be reached unsoundly, OR
  2) Make the public API `unsafe fn` with a precise `# Safety` section, OR
  3) Add an allowlist entry with full justification and assign an owner.

SCOPE
-----
Scans .rs files under native/rust/crates/*/src/**.
Excludes anything inside `tests/`, `benches/`, `examples/`, or matching
`tests.rs` / `test_*.rs`.

EXIT CODES
----------
  0  No new risky patterns outside the allowlist.
  1  At least one new pattern needs justification (see report below).
  2  Allowlist file is malformed.
"""

from __future__ import annotations

import re
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parents[2]
CRATES_ROOT = REPO_ROOT / "native" / "rust" / "crates"
ALLOWLIST_FILE = REPO_ROOT / "ci" / "unsafe-boundary-allowlist.toml"

# Pattern -> regex. Each pattern flags a class of unsafe operation that, if
# reachable from safe code without local justification, is a soundness risk.
# The regexes are intentionally precise enough to avoid catching unrelated
# identifiers (e.g. `transmute` matches the std `core::mem::transmute` family,
# not a local function named `transmute_payload`).
PATTERNS: dict[str, re.Pattern[str]] = {
    "slice::from_raw_parts": re.compile(r"\b(?:std::|core::)?slice::from_raw_parts(?:_mut)?\b"),
    "Box::from_raw": re.compile(r"\bBox(::<[^>]*>)?::from_raw\b"),
    # `Box::into_raw` is the matching counterpart of `Box::from_raw`. The
    # pair encodes a manual ownership transfer (Rust → caller of the raw
    # pointer → Rust again) that the type system cannot check end-to-end.
    # Scanning only `from_raw` would catch the reclaim side but miss
    # orphaned `into_raw` calls that leak (`mem::forget` equivalent) or
    # that hand the pointer to FFI without a matching `from_raw`. Each
    # occurrence must either restructure (use `Pin<Box<T>>`, a typed FFI
    # wrapper, or an explicit free callback) or earn an allowlist entry
    # naming the matching `from_raw` call site and the ownership-transfer
    # contract. See docs/rust-soundness-policy.md § "`Box::into_raw` /
    # `Box::from_raw` ownership transfer".
    "Box::into_raw": re.compile(r"\bBox(::<[^>]*>)?::into_raw\b"),
    "Vec::from_raw_parts": re.compile(r"\bVec(::<[^>]*>)?::from_raw_parts\b"),
    "String::from_raw_parts": re.compile(r"\bString::from_raw_parts\b"),
    # The allocator-API variant of `Vec::from_raw_parts`. Adds an
    # `Allocator` parameter and rejects `from_raw_parts` callers that
    # used the default global allocator on the `into_raw_parts` side
    # but a custom allocator on the `from_raw_parts_in` side (UB).
    # The base `Vec::from_raw_parts` pattern's `\b` anchor does NOT
    # match `from_raw_parts_in` because `_` is a word character, so
    # a dedicated pattern is required. Issue #16 audit found zero
    # production occurrences. Per docs/rust-soundness-policy.md
    # § "`Vec::from_raw_parts` ownership transfer" any new occurrence
    # must restructure (use `Vec::with_capacity_in` + `set_len` in
    # the same scope, or a typed buffer wrapper) or earn an
    # allowlist entry naming the matching `into_raw_parts_in` call
    # site and the eight-point audit checklist.
    "Vec::from_raw_parts_in": re.compile(r"\bVec(::<[^>]*>)?::from_raw_parts_in\b"),
    # `MaybeUninit::assume_init` (and the four variants documented in
    # the std API: `assume_init_ref`, `assume_init_mut`,
    # `assume_init_drop`, `assume_init_read`). The base `assume_init`
    # promotes `MaybeUninit<T>` to `T` by value; the variants
    # reinterpret a slot of unknown contents as `&T`/`&mut T`/Drop-
    # target. All five require every byte of the slot to be a valid
    # `T` value at the call site — uninit bytes → UB on the very
    # next read. The previous regex (`\b` anchor after
    # `assume_init`) silently failed to match `_ref`/`_mut`/`_drop`/
    # `_read` because `_` is a word character; this version uses
    # the std-API-complete list explicitly. Issue #20 audit found
    # ZERO production occurrences of any variant.
    "MaybeUninit::assume_init": re.compile(
        r"\.assume_init(?:_ref|_mut|_drop|_read)?\(|"
        r"MaybeUninit(?:::<[^>]*>)?::assume_init(?:_ref|_mut|_drop|_read)?\b"
    ),
    "mem::transmute": re.compile(r"\b(std::|core::)?mem::transmute\b|\btransmute(::<[^>]*>)?\("),
    "get_unchecked": re.compile(r"\.get_unchecked(_mut)?\("),
    "unwrap_unchecked": re.compile(r"\.unwrap_unchecked\(\)"),
    "Pin::new_unchecked": re.compile(r"\bPin::new_unchecked\b"),
    "Pin::get_unchecked_mut": re.compile(r"\.get_unchecked_mut\(\)"),
    # NonNull::as_ref / as_mut: the qualified form is the reliable signal.
    # The unqualified method-call form (`ptr.as_ref()`) collides with the
    # ubiquitous `Option::as_ref` / `&str::as_ref` family, so we only catch
    # the explicit `NonNull::...` spelling here. Raw-pointer dereferences
    # are covered separately by `unsafe_op_in_unsafe_fn = deny` and the
    # SAFETY-comment policy.
    "NonNull::as_ref/as_mut": re.compile(r"\bNonNull(::<[^>]*>)?::as_(ref|mut)\b"),
    # NB: the four `^`-anchored patterns below MUST be compiled with
    # `re.MULTILINE` so `^` matches the start of any line, not only the
    # first character of the whole file. Prior to this fix the patterns
    # silently matched zero occurrences in production code; the test
    # suite's single-line fragments happened to pass because `^` aligns
    # with position 0 of a one-line string.
    "unsafe impl Send/Sync": re.compile(
        r"^\s*unsafe\s+impl(\s*<[^>]+>)?\s+(Send|Sync)\b", re.MULTILINE
    ),
    "extern \"C\" fn": re.compile(r"\bextern\s+\"C\"\s+fn\b"),
    "extern \"system\" fn": re.compile(r"\bextern\s+\"system\"\s+fn\b"),
    # `[^;{]*` (rather than `[^;]*`) constrains the body-of-signature match
    # to characters before the opening `{` of the function body — without
    # this, the greedy class spans across the function body and matches
    # raw-pointer casts in unrelated code further down the file.
    "NonNull in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*[^;{]*\bNonNull\b",
        re.MULTILINE,
    ),
    "raw pointer in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*[^;{]*[:,(\s]\*(const|mut)\s",
        re.MULTILINE,
    ),
    "raw usize handle in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*\b"
        r"(handle|token|raw[_a-z]*)\s*:\s*(u64|i64|usize|isize)\b",
        re.MULTILINE,
    ),
    # Option<NonNull<T>> is Copy. Stored in a struct field, returned from a
    # function, or passed as a parameter, it cannot encode ownership: safe
    # callers may duplicate the value and produce stale handles, UAF, or
    # double-free. The fix is a private move-only newtype around `NonNull`
    # (no Copy/Clone) plus `Option<OwnerHandle<T>>`. See
    # docs/rust-soundness-policy.md § "Option<NonNull<T>> ownership tokens".
    "Option<NonNull<T>>": re.compile(r"\bOption\s*<\s*NonNull\s*<"),
    # The slot-mutating form — `fn extract(slot: &mut Option<NonNull<T>>)`
    # is the most common UAF/double-free vector: the function can `take()`
    # the value but safe code already held a copy of the original slot.
    "&mut Option<NonNull<T>>": re.compile(r"&\s*mut\s+Option\s*<\s*NonNull\s*<"),
    # CStr::from_ptr materializes a `&CStr` whose bytes are scanned for a
    # NUL terminator starting at the raw pointer. The pointee must be a
    # valid NUL-terminated C string in an allocation that lives at least
    # as long as the returned `&CStr`. New occurrences require an
    # allowlist entry naming the source of the validity guarantee
    # (POSIX syscall contract, FFI caller contract, etc.) per
    # docs/rust-soundness-policy.md § "Creating `&T` from raw pointers".
    "CStr::from_ptr": re.compile(r"\bCStr(::<[^>]*>)?::from_ptr\b"),
    # str::from_utf8_unchecked turns `&[u8]` into `&str` without
    # validating UTF-8. A safe-API regression here invalidates the
    # `str` invariant and produces UB on any subsequent UTF-8 operation.
    "str::from_utf8_unchecked": re.compile(r"\b(?:std::|core::)?str::from_utf8_unchecked\b"),
    # String::from_utf8_unchecked turns `Vec<u8>` into `String`
    # without validating UTF-8. The owned counterpart of
    # `str::from_utf8_unchecked`: same UB risk, same documentation
    # requirement, separate audit because the input is owned rather
    # than borrowed (so the validity argument must also cover the
    # full ownership transfer). Per docs/rust-soundness-policy.md
    # § "Unsafe `String`/`str` construction", new occurrences must
    # restructure (use `String::from_utf8`, which returns a
    # `Result<String, FromUtf8Error>` at the cost of one linear
    # scan) or earn an allowlist entry naming the source of the
    # UTF-8 guarantee.
    "String::from_utf8_unchecked": re.compile(r"\bString::from_utf8_unchecked\b"),
    # `libc::malloc` / `libc::calloc` / `libc::realloc` /
    # `libc::free` — direct C-allocator calls. Rust's default
    # global allocator (`std::alloc::System` on most targets) and
    # libc's malloc are NOT guaranteed to be the same heap — even
    # when they happen to be in practice, the contract is
    # implementation-defined and changes silently on
    # `#[global_allocator]` switches. Per
    # docs/rust-soundness-policy.md § "Allocator mismatch across
    # FFI", any new occurrence must either:
    #   (a) restructure to keep both ends of the lifetime in C
    #       (the foreign library allocates and frees; Rust only
    #       borrows),
    #   (b) restructure to keep both ends in Rust (`Box`, `Vec`,
    #       `String`),
    #   (c) earn an allowlist entry naming the C-allocator
    #       provenance and proof that every `libc::malloc` /
    #       `libc::calloc` / `libc::realloc` is matched by
    #       exactly one `libc::free`.
    # Issue #18 audit found zero production occurrences.
    "libc::malloc": re.compile(r"\blibc::(?:malloc|calloc|realloc|free)\b"),
    # `CString::from_raw` / `CString::into_raw` — the FFI string
    # analogue of `Box::from_raw` / `Box::into_raw`. The pair has
    # the same allocator-compatibility constraint (both ends must
    # use the global allocator that `CString::new` used) plus a
    # NUL-termination invariant. Mixing
    # `CString::from_raw(libc::malloc(n) as *mut c_char)` is UB
    # because the deallocator that runs on Drop is the global
    # allocator, not `libc::free`. Issue #18 audit found zero
    # production occurrences. New entries must restructure to a
    # typed RAII wrapper or earn an allowlist entry naming the
    # matching `CString::into_raw` / `CString::new` site.
    "CString::from_raw": re.compile(r"\bCString::from_raw\b"),
    "CString::into_raw": re.compile(r"\bCString::into_raw\b"),
    # `Vec::set_len` is an `unsafe fn`; the canonical call shape is
    # `unsafe { v.set_len(n) }`. The bytes `[0, n)` of the Vec's
    # buffer MUST be initialised valid `T` values BEFORE the call —
    # an off-by-one or short fill makes Drop run on uninitialised
    # memory (UB if `T: Drop`) and exposes uninit bytes on any
    # `&[..]` borrow. Per docs/rust-soundness-policy.md
    # § "`Vec::set_len` initialisation contract", new occurrences
    # must either:
    #   (a) restructure to use safe `Vec::push` / `Vec::extend` /
    #       `Vec::extend_from_slice` (the bytes are typed `T` on
    #       the way in),
    #   (b) use the `Vec::with_capacity` + `spare_capacity_mut` +
    #       guarded-`set_len` idiom with a documented producer
    #       that writes `MaybeUninit::write` for every byte in
    #       `[0, n)`, OR
    #   (c) earn an allowlist entry naming the producer of the
    #       initialised prefix and proving `n <= capacity`.
    # The regex catches the `unsafe { ... .set_len( ... ) ... }`
    # form on a single line; that is the only sound spelling
    # (calling `Vec::set_len` outside an `unsafe` block is a
    # compile error). False positives on `BufferHandle::set_len`,
    # `File::set_len`, etc. are avoided because those methods are
    # safe and never appear inside an `unsafe { }` block.
    "unsafe Vec::set_len": re.compile(r"\bunsafe\s*\{[^}]*\.set_len\("),
    # UnsafeCell::get returns `*mut T` from `&UnsafeCell<T>`. Dereferencing
    # it to produce `&mut T` (the canonical `unsafe { (*cell.get()).as_mut() }`
    # pattern) bypasses Rust's shared-vs-exclusive borrow check entirely;
    # soundness depends on the caller proving no other accessor exists.
    # New occurrences require an allowlist entry naming the exclusivity
    # discipline (move-only handle + free list, mutex, type-state, etc.)
    # per docs/rust-soundness-policy.md § "Creating `&mut T` from raw
    # memory".
    "UnsafeCell::get": re.compile(r"\.get\(\)"),  # narrowed below
    # `Cell<bool>` is a common cheap way to encode lifecycle state, but
    # the value's mutation has no synchronisation cost and no exclusivity
    # discipline. Use a typestate or RAII guard instead. There are zero
    # production occurrences today; any new appearance must be reviewed
    # and either restructured or earn an allowlist entry whose
    # `enforcement` field explains why ownership/liveness is encoded
    # elsewhere. See docs/rust-soundness-policy.md § "Ownership must be
    # types, not flags".
    "Cell<bool>": re.compile(r"\bCell\s*<\s*bool\s*>"),
    # Manual `Arc<T>` / `Rc<T>` lifecycle mutation via the raw-handle API
    # surface (`into_raw`/`from_raw`/`increment_strong_count`/
    # `decrement_strong_count`). The standard library handles every
    # sound use of these (Tokio, async traits) internally; application
    # code that calls them is almost always reinventing reference
    # counting unsoundly. Round-tripping `Arc<T>` through `*const T`
    # silently shifts the refcount by 0 or 1 depending on whether the
    # caller remembers to call `Arc::from_raw` exactly once. There are
    # zero production occurrences today; any new appearance trips CI.
    # See docs/rust-soundness-policy.md § "Use `Arc<T>` / `Rc<T>` /
    # `Weak<T>`, not manual refcounting".
    "manual Arc/Rc refcount": re.compile(
        r"\b(?:Arc|Rc|Weak)(?:::<[^>]*>)?::"
        r"(?:into_raw|from_raw|increment_strong_count|decrement_strong_count)\b"
    ),
    # A struct field whose name (`refs`, `refcount`, `ref_count`, `strong`,
    # `weak`) and type (`AtomicUsize`/`AtomicU64`/`AtomicIsize`/`AtomicI64`)
    # together indicate a hand-rolled intrusive reference count. The
    # workspace has none today. Any new occurrence must either restructure
    # to use `Arc<T>` / `Rc<T>` / `Weak<T>` or earn an allowlist entry
    # naming the atomic-ordering proof, overflow policy, reclamation
    # policy, and Send/Sync argument per
    # docs/rust-soundness-policy.md § "Use `Arc<T>` / `Rc<T>` /
    # `Weak<T>`, not manual refcounting".
    "manual atomic refcount field": re.compile(
        r"^\s*(?:pub(?:\s*\([^)]*\))?\s+)?(?:refs|refcount|ref_count|strong|weak)"
        r"\s*:\s*Atomic(?:Usize|U64|Isize|I64)\b",
        re.MULTILINE,
    ),
    # Manual `impl Copy for X { }` (with or without leading `unsafe`).
    # `Copy` is normally derived; a hand-written `impl Copy` block is
    # almost never the right choice — the only legitimate uses are
    # blanket `impl<T: ?Sized> Copy for ManuallyDrop<&T>` style helpers
    # which do not appear in application code. Issue #14 audit found
    # zero production occurrences; any new appearance must restructure
    # (use `#[derive(Copy)]` if Copy is genuinely intended and the
    # field shape supports it) or earn an allowlist entry naming the
    # Copy-trivial-data property per docs/rust-soundness-policy.md
    # § "`Copy` on owner-named types".
    "manual impl Copy": re.compile(
        r"^[ \t]*(?:unsafe\s+)?impl(\s*<[^>]+>)?\s+Copy\s+for\b",
        re.MULTILINE,
    ),
}

# The `.get()` method is also used by many safe types (HashMap, Vec,
# Option, AtomicPtr, etc.), so the regex above intentionally matches a
# superset. We refine the match in `_unsafe_cell_get_filter` so only
# `*cell.get()` / `(*cell.get())` / `unsafe { ... .get() ... }` patterns
# co-located with an `UnsafeCell` type signal a real finding.
UNSAFE_CELL_USE_RE = re.compile(r"\bUnsafeCell\b")
UNSAFE_CELL_GET_RE = re.compile(r"\*\s*[A-Za-z_][A-Za-z0-9_\.\[\]]*\.get\(\)")


def _filter_unsafe_cell_get(text: str, candidate_lines: list[int]) -> list[int]:
    """Drop `.get()` matches that are not the `*cell.get()` pattern.

    The regex `\\.get\\(\\)` deliberately over-matches; we only emit a
    finding when:
      - the file mentions `UnsafeCell` at least once (excludes most std
        collection `.get()` callers), AND
      - the matched `.get()` is preceded by a leading `*` deref
        (the only shape that materialises `*mut T` → `&mut T` /
        `&T` from an `UnsafeCell`).
    """
    if not UNSAFE_CELL_USE_RE.search(text):
        return []
    kept: list[int] = []
    deref_lines = {text.count("\n", 0, m.start()) + 1 for m in UNSAFE_CELL_GET_RE.finditer(text)}
    for line in candidate_lines:
        if line in deref_lines:
            kept.append(line)
    return kept

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]
# Strip block comments and line comments before pattern matching so that
# documentation, SAFETY notes, and TODOs don't trigger findings.
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")

# Proximity detector for `debug_assert!` near `unsafe`. The single-line
# regex set above cannot express "within N lines of X", so we run a small
# post-pass that matches the two sentinels independently and emits a
# finding for every debug-assertion that sits within
# DEBUG_ASSERT_PROXIMITY_LINES lines of an `unsafe` keyword. The policy
# in docs/rust-soundness-policy.md (§ Mandatory Invariant #3) is that
# `debug_assert!` does NOT count as memory-safety enforcement; this scan
# stops new code from re-introducing the pattern.
DEBUG_ASSERT_RE = re.compile(r"\bdebug_assert(?:_eq|_ne)?!")
UNSAFE_KEYWORD_RE = re.compile(r"\bunsafe\b")
DEBUG_ASSERT_PROXIMITY_LINES = 10
DEBUG_ASSERT_PROXIMITY_PATTERN = "debug_assert near unsafe"

# Proximity detector for ownership-flag bool fields near `impl Drop` or
# `unsafe`. The issue-#11 audit established that ownership must be
# encoded as types (move-only handles, RAII, typestate) rather than
# boolean flags. A field named `registered`, `is_alive`, `destroyed`,
# `initialized`, `disowned`, `owned_by_*`, `freed`, or `active` whose
# value gates an `unsafe` operation or a Drop-time cleanup is a
# classic recipe for double-destroy / stale-handle / aliasing-by-flag
# bugs in release builds.
OWNERSHIP_FLAG_RE = re.compile(
    r"^\s*(?:pub(?:\s*\([^)]*\))?\s+)?"
    r"(?:owned_by_\w+|is_alive|destroyed|registered|initialized|disowned|freed)"
    r"\s*:\s*bool\s*[,;}]",
    re.MULTILINE,
)
DROP_IMPL_RE = re.compile(r"\bimpl(\s*<[^>]+>)?\s+Drop\s+for\b")
OWNERSHIP_FLAG_PROXIMITY_LINES = 50
OWNERSHIP_FLAG_PROXIMITY_PATTERN = "ownership flag near drop/unsafe"

# Proximity detector for `#[derive(Clone)]` on a struct/enum whose name
# ends in `Handle`, `Owner`, `Guard`, `Token`, `Resource`, `Registration`,
# or `Slot`. The issue-#13 audit established that ownership and
# exclusive-access handles must be move-only; `Clone` must mean either
# "independent safe duplicate" (Copy-able plain data) or "refcounted
# shared owner" (Arc/Rc-backed). A bare `derive(Clone)` on an
# owner-named struct that holds a raw pointer, file descriptor, or FFI
# handle silently duplicates ownership and is the canonical
# double-free/UAF recipe.
CLONE_DERIVE_RE = re.compile(
    r"#\s*\[\s*derive\s*\([^)]*\bClone\b[^)]*\)\s*\]",
    re.MULTILINE,
)
OWNER_NAMED_TYPE_RE = re.compile(
    # Leading whitespace is `[ \t]*` (not `\s*`) so the `^` anchor stays
    # pinned to the actual line of the type declaration; with `\s*` the
    # engine would greedily consume preceding blank lines and report the
    # match on the wrong line.
    r"^[ \t]*(?:pub(?:\s*\([^)]*\))?\s+)?(?:struct|enum)\s+"
    r"[A-Za-z_][A-Za-z0-9_]*"
    r"(?:Handle|Owner|Guard|Token|Resource|Registration|Slot)\b",
    re.MULTILINE,
)
CLONE_ON_OWNER_PROXIMITY_LINES = 5
CLONE_ON_OWNER_PROXIMITY_PATTERN = "derive Clone on owner-named type"

# Proximity detector for `#[derive(Copy)]` on the same owner-named
# types. `Copy` is strictly stronger than `Clone`: a `Copy` value
# duplicates implicitly on every move, parameter pass, and assignment,
# so an owner-named `Copy` type cannot be a sound ownership/exclusive-
# access token. The issue-#14 audit established that the only sound
# `Copy` semantics on a type ending in `Handle`/`Owner`/`Guard`/`Token`/
# `Resource`/`Registration`/`Slot` is "Copy-trivial metadata that owns
# nothing" (e.g. `StrategyDescriptorRegistration` — `&'static str` +
# function pointer). Anything else (raw pointer, NonNull, RawFd,
# OwnedFd, FFI handle, arena index, Drop-adjacent state) is unsound
# Copy. The detector reuses `OWNER_NAMED_TYPE_RE`; the proximity
# window matches `CLONE_ON_OWNER_PROXIMITY_LINES` because the layout
# of `#[derive(...)]` above an owner-named declaration is identical.
COPY_DERIVE_RE = re.compile(
    r"#\s*\[\s*derive\s*\([^)]*\bCopy\b[^)]*\)\s*\]",
    re.MULTILINE,
)
COPY_ON_OWNER_PROXIMITY_LINES = 5
COPY_ON_OWNER_PROXIMITY_PATTERN = "derive Copy on owner-named type"

# Proximity detector for a `#[derive(Copy)]` whose struct body — within
# the next 25 source lines — declares a field of an ownership-bearing
# type: `NonNull<T>`, a raw `*const T` / `*mut T` pointer, a `RawFd`,
# an `OwnedFd`, a JNI `JavaVM` / `JObject` / `Global<JObject>`. This
# is the field-shape complement to the name-based detector above:
# even if a struct is named `Config` rather than `OwnerHandle`, a
# `Copy` derive that hands out duplicate `NonNull`s or file
# descriptors is the same UAF/double-free recipe. The detector is
# intentionally separate from the name-based one so a finding cites
# the actual smoking gun (Copy + risky field).
COPY_STRUCT_BODY_CAPTURE_LINES = 25
COPY_RISKY_FIELD_RE = re.compile(
    r"\bNonNull\s*<"
    r"|\bRawFd\b"
    r"|\bOwnedFd\b"
    r"|\bJavaVM\b"
    r"|\bJObject\b"
    r"|\bJNIEnv\b"
    r"|\bGlobal\s*<\s*JObject"
    r"|:\s*\*(?:const|mut)\s",
)
COPY_WITH_RISKY_FIELD_PATTERN = "derive Copy with raw-pointer/handle field"


def find_clone_derive_on_owner_named_type(text: str) -> list[int]:
    """Return line numbers of `#[derive(Clone)]` annotations within ±N
    lines of a struct/enum whose name matches the owner-named regex.

    The window is small (5 lines) because `#[derive(...)]` is always
    immediately above the type it annotates, possibly with a doc-
    comment or another attribute in between. The window is symmetric
    so post-annotation comment blocks don't break detection.
    """
    derive_lines = sorted(
        {text.count("\n", 0, m.start()) + 1 for m in CLONE_DERIVE_RE.finditer(text)}
    )
    if not derive_lines:
        return []
    owner_lines = sorted(
        {text.count("\n", 0, m.start()) + 1 for m in OWNER_NAMED_TYPE_RE.finditer(text)}
    )
    if not owner_lines:
        return []
    out: list[int] = []
    for derive in derive_lines:
        if any(0 < (owner - derive) <= CLONE_ON_OWNER_PROXIMITY_LINES for owner in owner_lines):
            out.append(derive)
    return out


def find_copy_derive_on_owner_named_type(text: str) -> list[int]:
    """Return line numbers of `#[derive(Copy)]` annotations within ±N
    lines of an owner-named struct/enum declaration.

    Mirrors `find_clone_derive_on_owner_named_type` because `Copy`
    `derive`s are placed identically in the source; the only
    difference is which marker we match on.
    """
    derive_lines = sorted(
        {text.count("\n", 0, m.start()) + 1 for m in COPY_DERIVE_RE.finditer(text)}
    )
    if not derive_lines:
        return []
    owner_lines = sorted(
        {text.count("\n", 0, m.start()) + 1 for m in OWNER_NAMED_TYPE_RE.finditer(text)}
    )
    if not owner_lines:
        return []
    out: list[int] = []
    for derive in derive_lines:
        if any(0 < (owner - derive) <= COPY_ON_OWNER_PROXIMITY_LINES for owner in owner_lines):
            out.append(derive)
    return out


COPY_STRUCT_HEADER_RE = re.compile(
    # Match the struct/enum declaration header, allowing the
    # `pub(crate)` / `pub(super)` visibility modifier without
    # treating its parens as a tuple-struct opener. The capture
    # consumes everything up to (but not including) the body
    # opener (`{` or `(`), then groups the opener as `body_open`.
    r"(?:pub(?:\s*\(\s*(?:crate|super|self|in\s+[\w:]+)\s*\))?\s+)?"
    r"(?:struct|enum)\s+[A-Za-z_][A-Za-z0-9_]*"
    r"(?:\s*<[^>]+>)?"
    r"\s*(?P<body_open>[{(])",
)


def find_copy_derive_with_risky_field(text: str) -> list[int]:
    """Return line numbers of `#[derive(Copy)]` whose immediately-
    following struct body declares a field of an ownership-bearing
    type (`NonNull<T>`, raw pointer, `RawFd`, `OwnedFd`, JNI handle).

    The "struct body" is the brace-or-paren balanced region that
    starts at the first `{` (field struct) or `(` (tuple struct)
    appearing after the type name, within
    `COPY_STRUCT_BODY_CAPTURE_LINES` lines of the `derive`. The
    helper regex `COPY_STRUCT_HEADER_RE` skips the
    `pub(crate)` / `pub(super)` visibility-modifier parens so the
    tuple-struct fallback doesn't accidentally pick up the
    visibility marker.
    """
    out: list[int] = []
    for match in COPY_DERIVE_RE.finditer(text):
        derive_line = text.count("\n", 0, match.start()) + 1
        # Window: from the `derive` to N lines further on.
        start = match.end()
        end = start
        for _ in range(COPY_STRUCT_BODY_CAPTURE_LINES):
            nl = text.find("\n", end + 1)
            if nl < 0:
                end = len(text)
                break
            end = nl
        window = text[start:end]
        header = COPY_STRUCT_HEADER_RE.search(window)
        if header is None:
            continue
        opener_pos = header.end("body_open") - 1
        opener_close = "}" if header.group("body_open") == "{" else ")"
        depth = 1
        j = opener_pos + 1
        while j < len(window) and depth > 0:
            if window[j] == window[opener_pos]:
                depth += 1
            elif window[j] == opener_close:
                depth -= 1
            j += 1
        body = window[opener_pos:j]
        if COPY_RISKY_FIELD_RE.search(body):
            out.append(derive_line)
    return out


def find_ownership_flag_near_drop_or_unsafe(text: str) -> list[int]:
    """Return line numbers of ownership-flag bool fields within ±N lines of
    an `impl Drop` or `unsafe` keyword in the same file.

    `text` must already have had comments stripped, so doc-comment
    mentions of these names don't fire the rule. The proximity window
    is wider than the debug-assert one (50 lines vs 10) because the
    `impl Drop` for a struct typically lives a struct-body's distance
    away from the bool field declaration.
    """
    flag_lines = sorted(
        {text.count("\n", 0, m.start()) + 1 for m in OWNERSHIP_FLAG_RE.finditer(text)}
    )
    if not flag_lines:
        return []
    sentinel_lines = sorted(
        {text.count("\n", 0, m.start()) + 1 for m in DROP_IMPL_RE.finditer(text)}
        | {text.count("\n", 0, m.start()) + 1 for m in UNSAFE_KEYWORD_RE.finditer(text)}
    )
    if not sentinel_lines:
        return []
    out: list[int] = []
    for flag in flag_lines:
        if any(abs(flag - sentinel) <= OWNERSHIP_FLAG_PROXIMITY_LINES for sentinel in sentinel_lines):
            out.append(flag)
    return out


def find_debug_assert_near_unsafe(text: str) -> list[int]:
    """Return line numbers of `debug_assert*!` within ±N lines of an `unsafe` keyword.

    `text` must already have had comments stripped, so that a SAFETY comment
    or a historical mention of `debug_assert` in a doc-comment does not
    fire the rule. Both sentinel sets are computed from the stripped text.
    """
    da_lines = sorted({text.count("\n", 0, m.start()) + 1 for m in DEBUG_ASSERT_RE.finditer(text)})
    if not da_lines:
        return []
    unsafe_lines = sorted({text.count("\n", 0, m.start()) + 1 for m in UNSAFE_KEYWORD_RE.finditer(text)})
    if not unsafe_lines:
        return []
    out: list[int] = []
    for da in da_lines:
        if any(abs(da - u) <= DEBUG_ASSERT_PROXIMITY_LINES for u in unsafe_lines):
            out.append(da)
    return out


@dataclass(frozen=True)
class Finding:
    rel_path: str
    pattern: str
    line: int


def is_excluded(path: Path) -> bool:
    parts = path.parts
    try:
        src_idx = parts.index("src")
    except ValueError:
        return True
    sub_dirs = parts[src_idx + 1 : -1]
    if any(d in EXCLUDE_DIRS for d in sub_dirs):
        return True
    rel = str(path.relative_to(REPO_ROOT))
    return any(pat.search(rel) for pat in EXCLUDE_FILE_RE)


def strip_comments(text: str) -> str:
    text = BLOCK_COMMENT_RE.sub("", text)
    return LINE_COMMENT_RE.sub("", text)


def scan_file(path: Path) -> list[Finding]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    cleaned = strip_comments(text)
    findings: list[Finding] = []
    raw_unsafe_cell_lines: list[int] = []
    for pattern_name, regex in PATTERNS.items():
        for match in regex.finditer(cleaned):
            line = cleaned.count("\n", 0, match.start()) + 1
            if pattern_name == "UnsafeCell::get":
                raw_unsafe_cell_lines.append(line)
                continue
            findings.append(Finding(rel, pattern_name, line))
    for line in _filter_unsafe_cell_get(cleaned, raw_unsafe_cell_lines):
        findings.append(Finding(rel, "UnsafeCell::get", line))
    for line in find_debug_assert_near_unsafe(cleaned):
        findings.append(Finding(rel, DEBUG_ASSERT_PROXIMITY_PATTERN, line))
    for line in find_ownership_flag_near_drop_or_unsafe(cleaned):
        findings.append(Finding(rel, OWNERSHIP_FLAG_PROXIMITY_PATTERN, line))
    for line in find_clone_derive_on_owner_named_type(cleaned):
        findings.append(Finding(rel, CLONE_ON_OWNER_PROXIMITY_PATTERN, line))
    for line in find_copy_derive_on_owner_named_type(cleaned):
        findings.append(Finding(rel, COPY_ON_OWNER_PROXIMITY_PATTERN, line))
    for line in find_copy_derive_with_risky_field(cleaned):
        findings.append(Finding(rel, COPY_WITH_RISKY_FIELD_PATTERN, line))
    return findings


def collect_findings() -> list[Finding]:
    out: list[Finding] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


def load_allowlist() -> dict[tuple[str, str], dict]:
    if not ALLOWLIST_FILE.exists():
        return {}
    with ALLOWLIST_FILE.open("rb") as fh:
        data = tomllib.load(fh)
    entries = data.get("entries", [])
    out: dict[tuple[str, str], dict] = {}
    required = {"file", "pattern", "reason", "preconditions", "enforcement", "owner", "review_date"}
    for entry in entries:
        missing = required - entry.keys()
        if missing:
            print(
                f"ERROR: allowlist entry is missing fields {sorted(missing)}: {entry}",
                file=sys.stderr,
            )
            sys.exit(2)
        key = (entry["file"], entry["pattern"])
        if key in out:
            print(
                f"ERROR: duplicate allowlist entry for {key}",
                file=sys.stderr,
            )
            sys.exit(2)
        out[key] = entry
    return out


def aggregate_findings(findings: Iterable[Finding]) -> dict[tuple[str, str], list[int]]:
    bucket: dict[tuple[str, str], list[int]] = {}
    for finding in findings:
        bucket.setdefault((finding.rel_path, finding.pattern), []).append(finding.line)
    return bucket


def main() -> int:
    allowlist = load_allowlist()
    findings = collect_findings()
    grouped = aggregate_findings(findings)

    new_violations: list[tuple[tuple[str, str], list[int]]] = []
    stale_allows: list[tuple[str, str]] = []

    seen_keys: set[tuple[str, str]] = set()
    for key, lines in grouped.items():
        seen_keys.add(key)
        if key not in allowlist:
            new_violations.append((key, lines))

    for key in allowlist:
        if key not in seen_keys:
            stale_allows.append(key)

    pattern_total = sum(len(lines) for lines in grouped.values())
    print(
        f"Scanned production Rust under {CRATES_ROOT.relative_to(REPO_ROOT)} -- "
        f"{pattern_total} pattern occurrence(s) across {len(grouped)} (file, pattern) pair(s)."
    )

    if stale_allows:
        print()
        print(f"NOTE: {len(stale_allows)} allowlist entry(ies) no longer match any source -- consider removing:")
        for file_, pattern in stale_allows:
            print(f"  {file_}  pattern={pattern}")

    if not new_violations:
        print(f"\nOK: {len(allowlist)} allowlisted (file, pattern) pair(s) cover all findings.")
        return 0

    print()
    print(f"FAIL: {len(new_violations)} new (file, pattern) pair(s) not covered by the allowlist:")
    for (file_, pattern), lines in sorted(new_violations):
        joined_lines = ", ".join(str(line) for line in lines[:8])
        if len(lines) > 8:
            joined_lines += f", ... ({len(lines)} total)"
        print(f"  {file_}  pattern={pattern}  lines={joined_lines}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Restructure so the unsafe operation cannot be reached from safe code\n"
        "     (newtype, RAII, typestate, BorrowedFd/OwnedFd, etc.).\n"
        "  2) Make the public function `unsafe fn` with a precise `# Safety` section\n"
        "     and propagate the contract to callers.\n"
        "  3) If neither is possible, add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} with all required fields\n"
        "     (file, pattern, reason, preconditions, enforcement, owner, review_date).\n"
        "\nDo NOT lower lint levels or pass `--allow` to suppress these findings.\n"
        "Policy: docs/rust-soundness-policy.md"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
