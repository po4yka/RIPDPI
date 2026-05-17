#!/usr/bin/env python3
"""Unit tests for check_unsafe_boundaries.py.

These tests exercise the scan engine on synthetic source snippets so that
regressions in the regex set surface immediately, independent of the
allowlist baseline.
"""

from __future__ import annotations

import sys
import textwrap
import unittest
from pathlib import Path
from typing import Iterable

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import check_unsafe_boundaries as guard  # noqa: E402  (sys.path tweak above)


def _scan(text: str) -> list[tuple[str, int]]:
    cleaned = guard.strip_comments(text)
    matches: list[tuple[str, int]] = []
    raw_unsafe_cell_lines: list[int] = []
    for pattern_name, regex in guard.PATTERNS.items():
        for m in regex.finditer(cleaned):
            line = cleaned.count("\n", 0, m.start()) + 1
            if pattern_name == "UnsafeCell::get":
                raw_unsafe_cell_lines.append(line)
                continue
            matches.append((pattern_name, line))
    for line in guard._filter_unsafe_cell_get(cleaned, raw_unsafe_cell_lines):
        matches.append(("UnsafeCell::get", line))
    for line in guard.find_debug_assert_near_unsafe(cleaned):
        matches.append((guard.DEBUG_ASSERT_PROXIMITY_PATTERN, line))
    for line in guard.find_ownership_flag_near_drop_or_unsafe(cleaned):
        matches.append((guard.OWNERSHIP_FLAG_PROXIMITY_PATTERN, line))
    for line in guard.find_clone_derive_on_owner_named_type(cleaned):
        matches.append((guard.CLONE_ON_OWNER_PROXIMITY_PATTERN, line))
    for line in guard.find_copy_derive_on_owner_named_type(cleaned):
        matches.append((guard.COPY_ON_OWNER_PROXIMITY_PATTERN, line))
    for line in guard.find_copy_derive_with_risky_field(cleaned):
        matches.append((guard.COPY_WITH_RISKY_FIELD_PATTERN, line))
    for line in guard.find_cast_deref_type_pun(cleaned):
        matches.append((guard.CAST_DEREF_TYPE_PUN_PATTERN, line))
    return matches


def _has(matches: Iterable[tuple[str, int]], pattern: str) -> bool:
    return any(name == pattern for name, _ in matches)


class ScanRegressionTests(unittest.TestCase):
    def test_extern_system_fn_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            pub extern "system" fn Java_com_example_test(env: EnvUnowned<'_>) -> jint {
                42
            }
            """
        )
        self.assertTrue(_has(_scan(src), 'extern "system" fn'))

    def test_extern_c_fn_flagged(self) -> None:
        src = "extern \"C\" fn handler() {}\n"
        self.assertTrue(_has(_scan(src), 'extern "C" fn'))

    def test_slice_from_raw_parts_flagged(self) -> None:
        for fragment in (
            "let s = unsafe { slice::from_raw_parts(p, n) };",
            "let s = unsafe { std::slice::from_raw_parts_mut(p, n) };",
            "let s = unsafe { core::slice::from_raw_parts(p, n) };",
        ):
            self.assertTrue(_has(_scan(fragment), "slice::from_raw_parts"), msg=fragment)

    def test_box_from_raw_flagged(self) -> None:
        src = "let owned = unsafe { Box::from_raw(ptr) };"
        self.assertTrue(_has(_scan(src), "Box::from_raw"))

    def test_box_into_raw_flagged(self) -> None:
        # `Box::into_raw` is the matched counterpart of `Box::from_raw`;
        # the issue-#15 audit requires both sides of the ownership
        # transfer to surface independently so an orphaned `into_raw`
        # (leak / handed to FFI without matching reclaim) doesn't slip
        # past the scanner. Turbofish form must also trigger.
        for fragment in (
            "let raw = Box::into_raw(boxed);",
            "let raw: *mut T = Box::into_raw(b);",
            "let raw = Box::<MyState>::into_raw(b);",
        ):
            self.assertTrue(_has(_scan(fragment), "Box::into_raw"), msg=fragment)

    def test_box_into_raw_unrelated_methods_not_flagged(self) -> None:
        # `Box::new` and other unrelated methods must not match the
        # into_raw pattern.
        for fragment in (
            "let b = Box::new(42);",
            "let b: Box<u32> = Box::default();",
            "let p = b.as_mut_ptr();",
        ):
            self.assertFalse(_has(_scan(fragment), "Box::into_raw"), msg=fragment)

    def test_vec_from_raw_parts_flagged(self) -> None:
        src = "let v = unsafe { Vec::from_raw_parts(p, len, cap) };"
        self.assertTrue(_has(_scan(src), "Vec::from_raw_parts"))

    def test_vec_from_raw_parts_in_flagged(self) -> None:
        # The allocator-API variant `Vec::from_raw_parts_in` has a
        # distinct soundness contract (allocator must match the
        # `into_raw_parts_in` side). The base `Vec::from_raw_parts`
        # regex's `\\b` anchor does NOT match `from_raw_parts_in`
        # (underscore is a word char), so a dedicated pattern is
        # required.
        for fragment in (
            "let v = unsafe { Vec::from_raw_parts_in(p, len, cap, alloc) };",
            "let v = unsafe { Vec::<T>::from_raw_parts_in(p, len, cap, alloc) };",
        ):
            self.assertTrue(_has(_scan(fragment), "Vec::from_raw_parts_in"), msg=fragment)

    def test_vec_from_raw_parts_in_is_distinct_from_from_raw_parts(self) -> None:
        # A `from_raw_parts_in` call must trigger the `_in` pattern
        # only, not the base `Vec::from_raw_parts` pattern (the two
        # APIs are semantically distinct).
        src = "let v = unsafe { Vec::from_raw_parts_in(p, len, cap, alloc) };"
        matches = _scan(src)
        self.assertTrue(_has(matches, "Vec::from_raw_parts_in"))
        self.assertFalse(_has(matches, "Vec::from_raw_parts"), msg="Vec::from_raw_parts must not match the _in variant")

    def test_safe_vec_constructors_not_flagged(self) -> None:
        # Safe Vec construction APIs MUST NOT trigger either raw-
        # parts pattern. This guards against future regex
        # broadening that would catch the safe `Vec::with_capacity`
        # / `Vec::new` / `Vec::from` family.
        for fragment in (
            "let v: Vec<u8> = Vec::with_capacity(64);",
            "let v: Vec<u8> = Vec::new();",
            "let v: Vec<u8> = Vec::from([1u8, 2, 3]);",
            "let mut v = Vec::with_capacity(16); v.spare_capacity_mut();",
        ):
            self.assertFalse(_has(_scan(fragment), "Vec::from_raw_parts"), msg=fragment)
            self.assertFalse(_has(_scan(fragment), "Vec::from_raw_parts_in"), msg=fragment)

    def test_maybeuninit_assume_init_flagged(self) -> None:
        # Issue #20: all five std-API variants of `assume_init` must
        # trigger. The previous regex (`\b` anchor after
        # `assume_init`) silently missed the `_ref` / `_mut` /
        # `_drop` / `_read` suffixes because `_` is a word
        # character. The fix uses the std-API-complete list
        # explicitly.
        for fragment in (
            "let value = unsafe { uninit.assume_init() };",
            "let r: &T = unsafe { uninit.assume_init_ref() };",
            "let m: &mut T = unsafe { uninit.assume_init_mut() };",
            "unsafe { uninit.assume_init_drop() };",
            "let v: T = unsafe { uninit.assume_init_read() };",
            "let value = unsafe { MaybeUninit::<T>::assume_init(uninit) };",
            "let r = unsafe { MaybeUninit::<T>::assume_init_ref(&uninit) };",
        ):
            self.assertTrue(_has(_scan(fragment), "MaybeUninit::assume_init"), msg=fragment)

    def test_safe_maybeuninit_apis_not_flagged(self) -> None:
        # `MaybeUninit::uninit()`, `MaybeUninit::new(t)`, and
        # `MaybeUninit::write(value)` are SAFE constructors and
        # writers — they MUST NOT trigger the assume_init pattern.
        # `spare_capacity_mut()` is the safe Vec API that returns
        # `&mut [MaybeUninit<T>]`; bare uses of MaybeUninit's safe
        # surface must not be misattributed.
        for fragment in (
            "let u: MaybeUninit<u8> = MaybeUninit::uninit();",
            "let u = MaybeUninit::new(42);",
            "slot.write(value);",
            "let buf = v.spare_capacity_mut();",
        ):
            self.assertFalse(_has(_scan(fragment), "MaybeUninit::assume_init"), msg=fragment)

    def test_transmute_flagged(self) -> None:
        for fragment in (
            "let x: u64 = unsafe { mem::transmute(f) };",
            "let x: u64 = unsafe { std::mem::transmute(f) };",
            "let x: u64 = unsafe { transmute::<f64, u64>(f) };",
        ):
            self.assertTrue(_has(_scan(fragment), "mem::transmute"), msg=fragment)

    def test_get_unchecked_flagged(self) -> None:
        src = "let v = unsafe { xs.get_unchecked(0) };"
        self.assertTrue(_has(_scan(src), "get_unchecked"))

    def test_unwrap_unchecked_flagged(self) -> None:
        src = "let v = unsafe { o.unwrap_unchecked() };"
        self.assertTrue(_has(_scan(src), "unwrap_unchecked"))

    def test_pin_get_unchecked_mut_flagged(self) -> None:
        src = "let inner = unsafe { pinned.get_unchecked_mut() };"
        self.assertTrue(_has(_scan(src), "Pin::get_unchecked_mut"))

    def test_nonnull_as_ref_qualified_flagged(self) -> None:
        for fragment in (
            "let r = unsafe { NonNull::<u8>::as_ref(p) };",
            "let r = unsafe { NonNull::as_mut(&mut p) };",
        ):
            self.assertTrue(_has(_scan(fragment), "NonNull::as_ref/as_mut"), msg=fragment)

    def test_option_as_ref_not_flagged(self) -> None:
        # The qualified-only pattern intentionally ignores `opt.as_ref()` etc.
        src = "let r = some_option.as_ref();"
        self.assertFalse(_has(_scan(src), "NonNull::as_ref/as_mut"))

    def test_unsafe_impl_send_sync_flagged(self) -> None:
        for fragment in (
            "unsafe impl Send for Foo {}",
            "unsafe impl<T: Send> Sync for Wrapper<T> {}",
        ):
            self.assertTrue(_has(_scan(fragment), "unsafe impl Send/Sync"), msg=fragment)

    def test_nonnull_in_public_fn_flagged(self) -> None:
        src = "pub fn take(handle: NonNull<u8>) {}"
        self.assertTrue(_has(_scan(src), "NonNull in public fn"))

    def test_raw_pointer_in_public_fn_flagged(self) -> None:
        for fragment in (
            "pub fn read(ptr: *const u8) -> u8 { 0 }",
            "pub(crate) fn write(ptr: *mut u8, value: u8) {}",
        ):
            self.assertTrue(_has(_scan(fragment), "raw pointer in public fn"), msg=fragment)

    def test_raw_usize_handle_in_public_fn_flagged(self) -> None:
        for fragment in (
            "pub fn destroy(handle: u64) {}",
            "pub fn use_token(token: usize) {}",
        ):
            self.assertTrue(_has(_scan(fragment), "raw usize handle in public fn"), msg=fragment)

    def test_option_nonnull_field_flagged(self) -> None:
        for fragment in (
            "struct Slot { ptr: Option<NonNull<u8>> }",
            "let x: Option<NonNull<MyType>> = None;",
            "pub fn take(slot: Option<NonNull<u8>>) {}",
            # whitespace variant: `Option < NonNull <`
            "struct Slot { ptr: Option  <  NonNull < u8 >> }",
        ):
            self.assertTrue(_has(_scan(fragment), "Option<NonNull<T>>"), msg=fragment)

    def test_option_nonnull_mut_slot_flagged(self) -> None:
        for fragment in (
            "fn extract(slot: &mut Option<NonNull<u8>>) -> Option<NonNull<u8>> { slot.take() }",
            "pub fn swap(slot: &mut Option<NonNull<MyType>>) {}",
        ):
            self.assertTrue(_has(_scan(fragment), "&mut Option<NonNull<T>>"), msg=fragment)
            # The narrower pattern is a subset; the broader Option<NonNull<T>>
            # match must also fire so the policy doc reference is consistent.
            self.assertTrue(_has(_scan(fragment), "Option<NonNull<T>>"), msg=fragment)

    def test_option_nonnull_not_triggered_by_unrelated_option(self) -> None:
        # `Option<NonZero...>` and `Option<MyHandle>` must not trigger the rule.
        for fragment in (
            "let x: Option<NonZeroU64> = None;",
            "let x: Option<MyHandle> = None;",
            "let x: Option<&NonNull<u8>> = None;",  # reference, not by-value slot
        ):
            self.assertFalse(_has(_scan(fragment), "Option<NonNull<T>>"), msg=fragment)

    def test_debug_assert_near_unsafe_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            fn write(ptr: *mut u8, len: usize) {
                debug_assert!(!ptr.is_null());
                unsafe {
                    ptr.write(0);
                }
            }
            """
        )
        self.assertTrue(_has(_scan(src), guard.DEBUG_ASSERT_PROXIMITY_PATTERN))

    def test_debug_assert_inside_unsafe_fn_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            unsafe fn cast<T>(ptr: *const T) -> &'static T {
                debug_assert!(!ptr.is_null(), "caller must pass a non-null pointer");
                &*ptr
            }
            """
        )
        self.assertTrue(_has(_scan(src), guard.DEBUG_ASSERT_PROXIMITY_PATTERN))

    def test_debug_assert_eq_and_ne_variants_flagged(self) -> None:
        for fragment in (
            "fn f() {\n    debug_assert_eq!(len, 4);\n    unsafe { do_thing(); }\n}",
            "fn f() {\n    debug_assert_ne!(p, std::ptr::null_mut());\n    unsafe { do_thing(); }\n}",
        ):
            self.assertTrue(_has(_scan(fragment), guard.DEBUG_ASSERT_PROXIMITY_PATTERN), msg=fragment)

    def test_debug_assert_far_from_unsafe_not_flagged(self) -> None:
        # 30 blank lines between the debug_assert and the `unsafe` keyword
        # exceeds the proximity window — must not fire.
        gap = "\n" * 30
        src = (
            "fn f() {\n"
            "    debug_assert!(x > 0);\n"
            f"{gap}"
            "    let _ = unsafe {{ raw() }};\n"
            "}\n"
        )
        self.assertFalse(_has(_scan(src), guard.DEBUG_ASSERT_PROXIMITY_PATTERN))

    def test_debug_assert_without_unsafe_not_flagged(self) -> None:
        src = "fn f() {\n    debug_assert!(x > 0);\n    return ();\n}\n"
        self.assertFalse(_has(_scan(src), guard.DEBUG_ASSERT_PROXIMITY_PATTERN))

    def test_debug_assert_in_comment_not_flagged(self) -> None:
        # A doc-comment mentioning debug_assert next to an unsafe block must
        # NOT trigger — comments are stripped before scanning.
        src = textwrap.dedent(
            """\
            /// historical: a debug_assert! guarded this unsafe block.
            fn f() {
                let _ = unsafe { raw() };
            }
            """
        )
        self.assertFalse(_has(_scan(src), guard.DEBUG_ASSERT_PROXIMITY_PATTERN))

    def test_cstr_from_ptr_flagged(self) -> None:
        for fragment in (
            "let s = unsafe { CStr::from_ptr(ptr) };",
            "let s = unsafe { std::ffi::CStr::from_ptr(p) };",
        ):
            self.assertTrue(_has(_scan(fragment), "CStr::from_ptr"), msg=fragment)

    def test_str_from_utf8_unchecked_flagged(self) -> None:
        for fragment in (
            "let s = unsafe { str::from_utf8_unchecked(bytes) };",
            "let s = unsafe { std::str::from_utf8_unchecked(bytes) };",
            "let s = unsafe { core::str::from_utf8_unchecked(bytes) };",
        ):
            self.assertTrue(_has(_scan(fragment), "str::from_utf8_unchecked"), msg=fragment)

    def test_from_utf8_safe_variant_not_flagged(self) -> None:
        # The safe `str::from_utf8` and `String::from_utf8` returning Result
        # must not trigger.
        for fragment in (
            "let s = str::from_utf8(bytes)?;",
            "let s = std::str::from_utf8(bytes).unwrap();",
            "let s = String::from_utf8(bytes)?;",
        ):
            self.assertFalse(_has(_scan(fragment), "str::from_utf8_unchecked"), msg=fragment)
            self.assertFalse(_has(_scan(fragment), "String::from_utf8_unchecked"), msg=fragment)

    def test_string_from_utf8_unchecked_flagged(self) -> None:
        # Issue #17: the owned counterpart of `str::from_utf8_unchecked`
        # must surface as a distinct scanner finding. Both bare and
        # method-syntax-equivalent forms must trip.
        for fragment in (
            "let s = unsafe { String::from_utf8_unchecked(bytes) };",
            "let s: String = unsafe { String::from_utf8_unchecked(v) };",
        ):
            self.assertTrue(_has(_scan(fragment), "String::from_utf8_unchecked"), msg=fragment)

    def test_libc_malloc_family_flagged(self) -> None:
        # Issue #18: every direct C-allocator call must surface as
        # a scanner finding so the allocator-mismatch audit can be
        # applied. The four entry points are `libc::malloc`,
        # `libc::calloc`, `libc::realloc`, and `libc::free`.
        for fragment in (
            "let p = unsafe { libc::malloc(64) };",
            "let p = unsafe { libc::calloc(16, 4) };",
            "let p = unsafe { libc::realloc(old, 128) };",
            "unsafe { libc::free(p) };",
        ):
            self.assertTrue(_has(_scan(fragment), "libc::malloc"), msg=fragment)

    def test_cstring_round_trip_flagged(self) -> None:
        # Issue #18: `CString::from_raw` and `CString::into_raw`
        # are the FFI-string analogue of `Box::into_raw` /
        # `Box::from_raw`. Both sides must surface as distinct
        # findings so the matched-pair allowlist requirement can
        # name the partner call site.
        #
        # NOTE: regex-based scanning catches only the qualified-
        # path form (`CString::from_raw` / `CString::into_raw`).
        # The method-call form (`cstr.into_raw()`) cannot be
        # disambiguated from other `.into_raw()` methods without
        # type analysis. The qualified path is the canonical
        # spelling in this workspace (zero method-call form
        # findings); a future migration could add a method-call
        # detector if needed.
        for fragment in (
            "let s = unsafe { CString::from_raw(ptr) };",
            "let raw = CString::into_raw(cstr);",
        ):
            scan = _scan(fragment)
            self.assertTrue(
                _has(scan, "CString::from_raw") or _has(scan, "CString::into_raw"),
                msg=fragment,
            )
        # Direct path form must trigger the from_raw pattern.
        self.assertTrue(_has(_scan("CString::from_raw(p)"), "CString::from_raw"))
        # Direct path form must trigger the into_raw pattern.
        self.assertTrue(_has(_scan("CString::into_raw(s)"), "CString::into_raw"))

    def test_unsafe_vec_set_len_flagged(self) -> None:
        # Issue #19: `Vec::set_len` is an `unsafe fn`; the canonical
        # call shape is `unsafe { v.set_len(n) }`. The pattern catches
        # this single-line shape because `Vec::set_len` cannot be
        # called outside an `unsafe { }` block (compile error
        # otherwise).
        for fragment in (
            "unsafe { v.set_len(n) };",
            "unsafe { buf.set_len(written) }",
            "let x = unsafe { vec.set_len(count); 42 };",
        ):
            self.assertTrue(_has(_scan(fragment), "unsafe Vec::set_len"), msg=fragment)

    def test_zero_init_patterns_flagged(self) -> None:
        # Issue #21: every zero-init primitive must surface as a
        # scanner finding so the per-field zero-validity audit can
        # be applied. Five canonical entry points: `mem::zeroed`,
        # `MaybeUninit::zeroed`, `ptr::write_bytes`, `libc::memset`
        # (the C-side equivalent), and turbofish variants.
        for fragment, pattern in (
            ("let x = unsafe { mem::zeroed::<T>() };", "mem::zeroed"),
            ("let x = unsafe { std::mem::zeroed() };", "mem::zeroed"),
            ("let x = unsafe { core::mem::zeroed::<u32>() };", "mem::zeroed"),
            ("let u = MaybeUninit::<T>::zeroed();", "MaybeUninit::zeroed"),
            ("let u = MaybeUninit::zeroed();", "MaybeUninit::zeroed"),
            ("unsafe { ptr::write_bytes(p, 0, n) };", "ptr::write_bytes"),
            ("unsafe { std::ptr::write_bytes(p, 0xff, n) };", "ptr::write_bytes"),
            ("unsafe { libc::memset(p, 0, n) };", "libc::memset"),
        ):
            self.assertTrue(_has(_scan(fragment), pattern), msg=fragment)

    def test_transmute_copy_flagged(self) -> None:
        # Issue #22: `mem::transmute_copy` was previously missed
        # because the `mem::transmute` regex used a `\b` anchor
        # that does not cross `_` (word boundary requirement). The
        # new pattern catches it explicitly.
        for fragment in (
            "let u: U = unsafe { mem::transmute_copy(&t) };",
            "let u = unsafe { std::mem::transmute_copy::<T, U>(&t) };",
            "let u = unsafe { core::mem::transmute_copy(&t) };",
        ):
            self.assertTrue(_has(_scan(fragment), "mem::transmute_copy"), msg=fragment)

    def test_explicit_leak_flagged(self) -> None:
        # Issue #22: `Box::leak` / `Vec::leak` / `String::leak`
        # promote a heap allocation to `&'static`. Sound by
        # language definition but the leaked memory is
        # unreachable for the rest of the process lifetime. Each
        # use must be deliberate and documented.
        for fragment in (
            "let s: &'static str = String::leak(s);",
            "let v: &'static mut [u8] = Vec::leak(v);",
            "let b: &'static mut T = Box::leak(b);",
            "let s = String::leak(format!(\"hello\"));",
        ):
            self.assertTrue(_has(_scan(fragment), "explicit leak"), msg=fragment)

    def test_unrelated_leak_apis_not_flagged(self) -> None:
        # `Pin::leak`, `Rc::strong_count`, and other unrelated APIs
        # MUST NOT trigger the explicit-leak pattern.
        for fragment in (
            "let count = Arc::strong_count(&a);",
            "let p = std::ptr::null::<T>();",
            "let r = Rc::clone(&rc);",
            "let s = leak.to_string();",  # bare `leak.` field access, no `::leak`
        ):
            self.assertFalse(_has(_scan(fragment), "explicit leak"), msg=fragment)

    def test_zero_init_unrelated_apis_not_flagged(self) -> None:
        # Safe zero-init APIs (Default::default, integer literal 0,
        # `Vec::new`, etc.) MUST NOT trigger the issue-#21 patterns.
        # Unrelated libc / std / core APIs that share substrings
        # with the patterns must also not match.
        for fragment in (
            "let x: u32 = 0;",
            "let v: Vec<u8> = Vec::new();",
            "let d = T::default();",
            "let m = mem::take(&mut value);",     # mem::take, not mem::zeroed
            "let p = ptr::null::<T>();",          # ptr::null, not ptr::write_bytes
            "unsafe { libc::memcpy(dst, src, n) };",  # memcpy, not memset
        ):
            self.assertFalse(_has(_scan(fragment), "mem::zeroed"), msg=fragment)
            self.assertFalse(_has(_scan(fragment), "MaybeUninit::zeroed"), msg=fragment)
            self.assertFalse(_has(_scan(fragment), "ptr::write_bytes"), msg=fragment)
            self.assertFalse(_has(_scan(fragment), "libc::memset"), msg=fragment)

    def test_safe_set_len_methods_not_flagged_by_vec_pattern(self) -> None:
        # `BufferHandle::set_len`, `File::set_len`, and other safe
        # inherent `.set_len()` methods MUST NOT trigger the
        # `Vec::set_len` pattern. The distinguishing feature is the
        # absence of the enclosing `unsafe { }` block — safe
        # `.set_len()` methods are called bare.
        for fragment in (
            "handle.set_len(len);",            # BufferHandle::set_len (safe)
            "file.set_len(0)?;",               # File::set_len (truncate)
            "self.set_len(new_len);",          # any other safe inherent set_len
        ):
            self.assertFalse(_has(_scan(fragment), "unsafe Vec::set_len"), msg=fragment)

    def test_libc_unrelated_functions_not_flagged(self) -> None:
        # The libc::malloc regex must NOT match unrelated libc
        # calls (mmap, munmap, write, read, etc.).
        for fragment in (
            "let p = unsafe { libc::mmap(...) };",
            "unsafe { libc::munmap(p, len) };",
            "unsafe { libc::write(fd, p, n) };",
            "unsafe { libc::close(fd) };",
        ):
            self.assertFalse(_has(_scan(fragment), "libc::malloc"), msg=fragment)

    def test_safe_string_from_utf8_does_not_match_unchecked(self) -> None:
        # The safe `String::from_utf8` (release-mode validation, Result
        # return) and `String::from_utf8_lossy` MUST NOT trigger the
        # unchecked pattern. These are the workspace's two recommended
        # alternatives per docs/rust-soundness-policy.md.
        for fragment in (
            "let s = String::from_utf8(bytes)?;",
            "let s = String::from_utf8(v).unwrap();",
            "let s = String::from_utf8_lossy(bytes).into_owned();",
            "let s = String::from_utf8_lossy(&buf).to_string();",
        ):
            self.assertFalse(_has(_scan(fragment), "String::from_utf8_unchecked"), msg=fragment)

    def test_unsafe_cell_get_deref_flagged(self) -> None:
        for fragment in (
            "use std::cell::UnsafeCell;\nfn f(c: &UnsafeCell<u8>) { unsafe { let _x = *c.get(); } }",
            "use std::cell::UnsafeCell;\nfn f(c: &UnsafeCell<Vec<u8>>) { let _r = unsafe { (*c.get()).as_mut() }; }",
        ):
            self.assertTrue(_has(_scan(fragment), "UnsafeCell::get"), msg=fragment)

    def test_plain_get_method_not_flagged(self) -> None:
        # The bare `.get()` form, used by HashMap, Vec, Option, AtomicPtr,
        # etc., must NOT trigger — neither the `UnsafeCell` type token nor
        # the `*x.get()` deref shape is present.
        for fragment in (
            "let v = map.get(&key);",
            "let v = vec.get(0);",
            "let v = some_option.get();",
            "let p = atomic_ptr.get();",
        ):
            self.assertFalse(_has(_scan(fragment), "UnsafeCell::get"), msg=fragment)

    def test_unsafe_cell_get_without_deref_not_flagged(self) -> None:
        # `.get()` on an `UnsafeCell` without the `*deref` shape (e.g.
        # passing the raw pointer to another function) is still risky but
        # is *not* the same pattern; we leave it for `raw pointer in
        # public fn` and the SAFETY-comment policy.
        src = "use std::cell::UnsafeCell;\nfn f(c: &UnsafeCell<u8>) -> *mut u8 { c.get() }"
        self.assertFalse(_has(_scan(src), "UnsafeCell::get"))

    def test_unsafe_impl_send_sync_flagged_on_later_line(self) -> None:
        # Regression: prior to the `re.MULTILINE` fix, the `^`-anchored
        # `unsafe impl Send/Sync` regex only matched at file position 0,
        # so any occurrence on line 2+ was silently invisible. This
        # multi-line fragment exercises the multi-line path.
        src = textwrap.dedent(
            """\
            struct Foo;

            unsafe impl Send for Foo {}
            unsafe impl Sync for Foo {}
            """
        )
        matches = [m for m in _scan(src) if m[0] == "unsafe impl Send/Sync"]
        self.assertEqual(len(matches), 2, msg=f"expected two findings, got {matches}")

    def test_raw_pointer_in_public_fn_flagged_on_later_line(self) -> None:
        # Regression: same MULTILINE issue for the `pub fn ... *const T`
        # signature regex. The signature appears on line 3, not line 1.
        src = textwrap.dedent(
            """\
            mod m {
                use libc;
                pub fn write(ptr: *mut u8, value: u8) {}
            }
            """
        )
        self.assertTrue(_has(_scan(src), "raw pointer in public fn"))

    def test_raw_usize_handle_in_public_fn_flagged_on_later_line(self) -> None:
        src = textwrap.dedent(
            """\
            mod m {
                pub fn destroy(handle: u64) {}
            }
            """
        )
        self.assertTrue(_has(_scan(src), "raw usize handle in public fn"))

    def test_raw_pointer_in_body_does_not_match_signature_pattern(self) -> None:
        # Regression: the `[^;{]*` (not `[^;]*`) constraint stops the
        # signature regex from greedily spanning across the function body
        # and matching raw-pointer casts in unrelated code further down.
        # The signature here takes `&T` (no raw pointer), so the rule must
        # NOT fire even though the body contains `*const T`.
        src = textwrap.dedent(
            """\
            pub fn safe_helper<T>(val: &T) -> u32 {
                let cast = val as *const T;
                cast as usize as u32
            }
            """
        )
        self.assertFalse(_has(_scan(src), "raw pointer in public fn"))

    def test_raw_pointer_in_multi_line_signature_still_flagged(self) -> None:
        # The `[^;{]*` constraint must still tolerate multi-line signatures
        # (rust-fmt wraps long arg lists). The raw pointer is in the args,
        # not the body, so the rule MUST fire.
        src = textwrap.dedent(
            """\
            pub fn long_signature(
                a: &Foo,
                b: *const u8,
                c: usize,
            ) -> io::Result<()> {
                Ok(())
            }
            """
        )
        self.assertTrue(_has(_scan(src), "raw pointer in public fn"))

    def test_cell_bool_flagged(self) -> None:
        for fragment in (
            "use std::cell::Cell;\nstruct S { ready: Cell<bool> }",
            "use std::cell::Cell;\nstatic READY: Cell<bool> = Cell::new(false);",
            # whitespace variant
            "let x: Cell  <  bool > = Cell::new(false);",
        ):
            self.assertTrue(_has(_scan(fragment), "Cell<bool>"), msg=fragment)

    def test_cell_other_types_not_flagged(self) -> None:
        # `Cell<u32>`, `Cell<MyType>`, etc. must not trigger.
        for fragment in (
            "let x: Cell<u32> = Cell::new(0);",
            "let x: Cell<MyType> = Cell::new(MyType);",
            "let x: RefCell<bool> = RefCell::new(false);",
        ):
            self.assertFalse(_has(_scan(fragment), "Cell<bool>"), msg=fragment)

    def test_ownership_flag_near_drop_flagged(self) -> None:
        # The classic shape: a struct with a lifecycle flag and a Drop impl
        # that branches on it. The issue-#11 audit names this pattern as
        # the canonical "bool as ownership token" anti-pattern.
        src = textwrap.dedent(
            """\
            struct Guard {
                registered: bool,
            }

            impl Drop for Guard {
                fn drop(&mut self) {
                    if self.registered { unregister(); }
                }
            }
            """
        )
        self.assertTrue(_has(_scan(src), guard.OWNERSHIP_FLAG_PROXIMITY_PATTERN))

    def test_ownership_flag_near_unsafe_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            struct Resource {
                is_alive: bool,
            }

            impl Resource {
                fn use_it(&self) {
                    if self.is_alive {
                        unsafe { do_thing(); }
                    }
                }
            }
            """
        )
        self.assertTrue(_has(_scan(src), guard.OWNERSHIP_FLAG_PROXIMITY_PATTERN))

    def test_ownership_flag_far_from_drop_not_flagged(self) -> None:
        # 60 blank lines between the flag field and any Drop / unsafe
        # exceeds the proximity window — must not fire.
        gap = "\n" * 60
        src = (
            "struct A {\n"
            "    registered: bool,\n"
            "}\n"
            f"{gap}"
            "impl Drop for B {\n"
            "    fn drop(&mut self) {}\n"
            "}\n"
        )
        self.assertFalse(_has(_scan(src), guard.OWNERSHIP_FLAG_PROXIMITY_PATTERN))

    def test_ownership_flag_without_drop_or_unsafe_not_flagged(self) -> None:
        # A `registered: bool` in a struct without any Drop or unsafe in
        # the file is plain control-flow state, not an ownership token.
        src = "struct Plain { registered: bool }\nfn main() {}"
        self.assertFalse(_has(_scan(src), guard.OWNERSHIP_FLAG_PROXIMITY_PATTERN))

    def test_other_bool_fields_not_flagged(self) -> None:
        # `closed`, `validated`, `is_ready`, etc. are not lifecycle/
        # ownership flag names by the audit's vocabulary and must not
        # trigger even when colocated with Drop.
        src = textwrap.dedent(
            """\
            struct Session {
                closed: bool,
                validated: bool,
            }
            impl Drop for Session { fn drop(&mut self) {} }
            """
        )
        self.assertFalse(_has(_scan(src), guard.OWNERSHIP_FLAG_PROXIMITY_PATTERN))

    def test_manual_arc_refcount_flagged(self) -> None:
        for fragment in (
            "let raw = Arc::into_raw(arc);",
            "let arc = unsafe { Arc::from_raw(raw) };",
            "unsafe { Arc::increment_strong_count(raw) };",
            "unsafe { Arc::decrement_strong_count(raw) };",
            "let weak_raw = Weak::into_raw(weak);",
            "let weak = unsafe { Weak::from_raw(weak_raw) };",
            "let raw = Rc::into_raw(rc);",
            "let rc = unsafe { Rc::from_raw(raw) };",
        ):
            self.assertTrue(_has(_scan(fragment), "manual Arc/Rc refcount"), msg=fragment)

    def test_arc_clone_and_new_not_flagged(self) -> None:
        # The sound API surface — `Arc::clone`, `Arc::new`, `Arc::strong_count`,
        # `Arc::downgrade`, `Arc::weak_count` — must NOT trigger.
        for fragment in (
            "let a2 = Arc::clone(&a);",
            "let a = Arc::new(42);",
            "let n = Arc::strong_count(&a);",
            "let w = Arc::downgrade(&a);",
            "let n = Arc::weak_count(&a);",
            "let r = Rc::new(42);",
            "let r2 = Rc::clone(&r);",
        ):
            self.assertFalse(_has(_scan(fragment), "manual Arc/Rc refcount"), msg=fragment)

    def test_manual_atomic_refcount_field_flagged(self) -> None:
        for fragment in (
            "struct Node {\n    refs: AtomicUsize,\n}",
            "struct Node {\n    refcount: AtomicU64,\n    data: u32,\n}",
            "struct Node {\n    ref_count: AtomicIsize,\n}",
            "struct Node {\n    strong: AtomicUsize,\n    weak: AtomicUsize,\n}",
            "pub struct Header {\n    pub(crate) refcount: AtomicUsize,\n}",
        ):
            self.assertTrue(_has(_scan(fragment), "manual atomic refcount field"), msg=fragment)

    def test_unrelated_atomic_fields_not_flagged(self) -> None:
        # Atomic fields with unrelated names (counters, flags, sequence
        # numbers) must NOT trigger.
        for fragment in (
            "struct State { dropped: AtomicUsize }",
            "struct State { seq: AtomicU64 }",
            "struct State { shutdown: AtomicBool }",
            "struct State { events: AtomicUsize, errors: AtomicUsize }",
        ):
            self.assertFalse(_has(_scan(fragment), "manual atomic refcount field"), msg=fragment)

    def test_clone_derive_on_owner_named_type_flagged(self) -> None:
        for fragment in (
            "#[derive(Clone)]\npub struct MyHandle { inner: u32 }",
            "#[derive(Debug, Clone)]\nstruct OwnerGuard { fd: i32 }",
            "#[derive(Clone, Copy)]\npub struct ResourceToken { id: u64 }",
            "#[derive(Clone)]\nenum SessionRegistration { Active, Idle }",
            "#[derive(Clone)]\npub(crate) struct CacheSlot { ptr: u64 }",
        ):
            self.assertTrue(_has(_scan(fragment), guard.CLONE_ON_OWNER_PROXIMITY_PATTERN), msg=fragment)

    def test_clone_derive_on_non_owner_named_type_not_flagged(self) -> None:
        # Types whose names don't match the ownership pattern must NOT trigger.
        for fragment in (
            "#[derive(Clone)]\nstruct Config { value: u32 }",
            "#[derive(Clone)]\nenum Event { A, B }",
            "#[derive(Clone)]\npub struct Snapshot { value: u32 }",
        ):
            self.assertFalse(_has(_scan(fragment), guard.CLONE_ON_OWNER_PROXIMITY_PATTERN), msg=fragment)

    def test_owner_named_type_without_clone_not_flagged(self) -> None:
        # An owner-named struct without `derive(Clone)` is fine — that's
        # the move-only pattern this rule encourages.
        src = textwrap.dedent(
            """\
            pub struct BufferHandle<'a> {
                _phantom: core::marker::PhantomData<&'a ()>,
            }
            """
        )
        self.assertFalse(_has(_scan(src), guard.CLONE_ON_OWNER_PROXIMITY_PATTERN))

    def test_clone_derive_far_from_owner_type_not_flagged(self) -> None:
        # A derive(Clone) more than 5 lines from any owner-named struct
        # must not be (mis)attributed.
        gap = "\n" * 10
        src = (
            "#[derive(Clone)]\n"
            "pub struct PlainData { value: u32 }\n"
            f"{gap}"
            "pub struct MyHandle { fd: i32 }\n"
        )
        self.assertFalse(_has(_scan(src), guard.CLONE_ON_OWNER_PROXIMITY_PATTERN))

    def test_copy_derive_on_owner_named_type_flagged(self) -> None:
        # Every owner-suffix spelling × every realistic derive arrangement
        # — `Copy` alone, `Copy + Clone`, multiple traits — must trigger.
        for fragment in (
            "#[derive(Copy, Clone)]\npub struct MyHandle { inner: u32 }",
            "#[derive(Debug, Clone, Copy)]\nstruct OwnerGuard { fd: i32 }",
            "#[derive(Clone, Copy)]\npub struct ResourceToken { id: u64 }",
            "#[derive(Copy, Clone, Debug)]\nenum SessionRegistration { Active, Idle }",
            "#[derive(Copy, Clone, PartialEq, Eq, Hash)]\npub(crate) struct CacheSlot { ptr: u64 }",
        ):
            self.assertTrue(_has(_scan(fragment), guard.COPY_ON_OWNER_PROXIMITY_PATTERN), msg=fragment)

    def test_copy_derive_on_non_owner_named_type_not_flagged(self) -> None:
        # Types whose names don't match the ownership suffix list must NOT
        # trigger, even though `Copy` is a strictly stronger trait. The
        # rule deliberately targets owner-named types — bare value/config
        # PODs are sound to `Copy`.
        for fragment in (
            "#[derive(Copy, Clone)]\nstruct Config { value: u32 }",
            "#[derive(Copy, Clone)]\nenum Event { A, B }",
            "#[derive(Copy, Clone, Debug)]\npub struct Snapshot { value: u32 }",
            "#[derive(Copy, Clone)]\npub struct FlowId(pub u64);",
        ):
            self.assertFalse(_has(_scan(fragment), guard.COPY_ON_OWNER_PROXIMITY_PATTERN), msg=fragment)

    def test_owner_named_type_without_copy_not_flagged(self) -> None:
        # An owner-named struct without `derive(Copy)` is the move-only
        # default this rule encourages; even a bare `derive(Clone)` must
        # not trip the Copy-specific pattern (the Clone pattern catches
        # that separately).
        src = textwrap.dedent(
            """\
            #[derive(Clone)]
            pub struct MyHandle { fd: i32 }
            """
        )
        self.assertFalse(_has(_scan(src), guard.COPY_ON_OWNER_PROXIMITY_PATTERN))

    def test_copy_derive_far_from_owner_type_not_flagged(self) -> None:
        # A derive(Copy) more than 5 lines from any owner-named struct
        # must not be (mis)attributed. Also exercises the `^[ \\t]*`
        # leading-whitespace anchor fix: an interleaved blank-line block
        # must not shift the match across to an unrelated owner-named
        # declaration further down.
        gap = "\n" * 10
        src = (
            "#[derive(Copy, Clone)]\n"
            "pub struct PlainData { value: u32 }\n"
            f"{gap}"
            "pub struct MyHandle { fd: i32 }\n"
        )
        self.assertFalse(_has(_scan(src), guard.COPY_ON_OWNER_PROXIMITY_PATTERN))

    def test_manual_impl_copy_flagged(self) -> None:
        # Both the bare `impl Copy` and the (rare) `unsafe impl Copy`
        # forms must trigger. Generic params on the impl must also
        # match.
        for fragment in (
            "impl Copy for Foo {}",
            "unsafe impl Copy for Foo {}",
            "impl<T> Copy for Wrap<T> {}",
            "impl<'a, T: ?Sized> Copy for Borrow<'a, T> {}",
        ):
            self.assertTrue(_has(_scan(fragment), "manual impl Copy"), msg=fragment)

    def test_impl_clone_or_other_traits_not_flagged_by_manual_copy(self) -> None:
        # A manual `impl Clone` / `impl Debug` etc. must NOT be
        # misattributed to the manual-impl-Copy pattern; only `impl
        # Copy for ...` should fire.
        for fragment in (
            "impl Clone for Foo { fn clone(&self) -> Self { *self } }",
            "impl Debug for Foo {}",
            "impl Drop for Foo { fn drop(&mut self) {} }",
            "impl PartialEq for Foo {}",
        ):
            self.assertFalse(_has(_scan(fragment), "manual impl Copy"), msg=fragment)

    def test_derive_copy_with_risky_field_flagged(self) -> None:
        # A `derive(Copy)` struct whose body declares an ownership-
        # bearing field (NonNull, raw pointer, RawFd, OwnedFd, JNI
        # handle) must trigger.
        for fragment in (
            "#[derive(Copy, Clone)]\npub struct A { ptr: NonNull<u8>, len: usize }",
            "#[derive(Copy, Clone)]\npub struct B { ptr: *mut u8 }",
            "#[derive(Copy, Clone)]\npub struct C { fd: RawFd }",
            "#[derive(Copy, Clone)]\nstruct D { fd: OwnedFd }",
            "#[derive(Copy, Clone)]\npub(crate) struct E { vm: JavaVM }",
            "#[derive(Copy, Clone)]\nstruct F { obj: JObject<'static> }",
            "#[derive(Copy, Clone)]\nstruct G { env: JNIEnv<'static> }",
            "#[derive(Copy, Clone)]\nstruct H { ptr: *const u8 }",
        ):
            self.assertTrue(_has(_scan(fragment), guard.COPY_WITH_RISKY_FIELD_PATTERN), msg=fragment)

    def test_derive_copy_with_safe_fields_not_flagged(self) -> None:
        # POD `derive(Copy)` structs with only safe fields must NOT
        # trigger — this is the standard sound case (config/enum/ABI
        # mirror).
        for fragment in (
            "#[derive(Copy, Clone)]\npub struct A { a: u32, b: u64 }",
            "#[derive(Copy, Clone)]\nenum Direction { In, Out }",
            "#[derive(Copy, Clone)]\npub struct Coord { x: f32, y: f32 }",
            "#[derive(Copy, Clone)]\npub struct Tag(u16);",
            "#[derive(Copy, Clone)]\npub struct StaticStr { value: &'static str }",
        ):
            self.assertFalse(_has(_scan(fragment), guard.COPY_WITH_RISKY_FIELD_PATTERN), msg=fragment)

    def test_clone_only_with_risky_field_not_flagged_by_copy_rule(self) -> None:
        # A `derive(Clone)` (without `Copy`) on a struct with a risky
        # field must NOT fire the Copy-risky-field rule. The Clone
        # case is policy-bounded separately by
        # `find_clone_derive_on_owner_named_type`.
        src = "#[derive(Clone)]\npub struct Foo { ptr: NonNull<u8>, len: usize }"
        self.assertFalse(_has(_scan(src), guard.COPY_WITH_RISKY_FIELD_PATTERN))

    # --- Issue #23: type punning and layout reinterpretation -------------

    def test_union_declaration_flagged(self) -> None:
        for fragment in (
            "union Foo { a: u32, b: f32 }",
            "pub union Bar { a: [u8; 4], b: u32 }",
            "pub(crate) union Baz { a: u64, b: [u32; 2] }",
        ):
            self.assertTrue(_has(_scan(fragment), "union declaration"), msg=fragment)

    def test_struct_with_union_in_name_not_flagged(self) -> None:
        # The `union declaration` regex must require the `union` keyword
        # as the type kind — a struct or enum that happens to mention
        # "union" in its name (e.g. `SetUnion`, `DnsUnionPolicy`) must
        # not trip the rule.
        for fragment in (
            "struct SetUnion { left: BTreeSet<u32>, right: BTreeSet<u32> }",
            "pub struct DnsUnionPolicy { shared: f64 }",
            "let union_size = 4;",
        ):
            self.assertFalse(_has(_scan(fragment), "union declaration"), msg=fragment)

    def test_bytemuck_cast_flagged(self) -> None:
        for fragment in (
            "let x: u32 = bytemuck::cast(arr);",
            "let r: &Header = bytemuck::cast_ref(&buf);",
            "let s: &[u32] = bytemuck::cast_slice(&bytes);",
            "let v: u64 = bytemuck::pod_read_unaligned(&buf[..8]);",
            "let h: Header = bytemuck::from_bytes::<Header>(&buf).clone();",
            "let h = bytemuck::try_cast::<[u8; 4], u32>(arr)?;",
        ):
            self.assertTrue(_has(_scan(fragment), "bytemuck::cast"), msg=fragment)

    def test_zerocopy_transmute_flagged(self) -> None:
        for fragment in (
            "let x: u32 = zerocopy::transmute!(arr);",
            "let r: &Header = zerocopy::transmute_ref!(&buf);",
            "let m: &mut Header = zerocopy::transmute_mut!(&mut buf);",
            "let r = zerocopy::Ref::new(&buf)?;",
            "let r = zerocopy::Ref::new_unaligned(&buf)?;",
        ):
            self.assertTrue(_has(_scan(fragment), "zerocopy::transmute"), msg=fragment)

    def test_zerocopy_into_bytes_as_bytes_flagged(self) -> None:
        for fragment in (
            "let bytes = IntoBytes::as_bytes(&header);",
            "let bytes = IntoBytes::as_mut_bytes(&mut header);",
        ):
            self.assertTrue(
                _has(_scan(fragment), "zerocopy::IntoBytes::as_bytes"), msg=fragment
            )

    def test_string_as_bytes_not_flagged_by_intobytes_rule(self) -> None:
        # The narrow `IntoBytes::as_bytes` qualified-path regex must not
        # collide with the ubiquitous bare-method `.as_bytes()` form
        # used by `String`/`&str`.
        for fragment in (
            'let bytes = "hello".as_bytes();',
            "let bytes = s.as_bytes();",
            "let bytes = String::from(\"x\").as_bytes().to_vec();",
        ):
            self.assertFalse(
                _has(_scan(fragment), "zerocopy::IntoBytes::as_bytes"), msg=fragment
            )

    def test_cast_deref_type_pun_flagged(self) -> None:
        # The canonical type-pun spelling in this workspace fits on one
        # line: `&*(p as *const A).cast::<B>()`. The detector must fire
        # in both orderings (`.cast()` before `&*` and vice versa).
        for fragment in (
            "let sin = unsafe { &*(storage as *const A).cast::<B>() };",
            "let sin = unsafe { &mut *(storage as *mut A).cast::<B>() };",
            "let r: &B = unsafe { &*p.cast::<B>() };",
            "let r = unsafe { &*ptr.cast() }; // bare .cast() also fires",
        ):
            self.assertTrue(
                _has(_scan(fragment), guard.CAST_DEREF_TYPE_PUN_PATTERN), msg=fragment
            )

    def test_cast_without_deref_not_flagged(self) -> None:
        # `.cast::<T>()` on its own — without a paired `&*` deref on
        # the same line — must not trip the rule. Plenty of legitimate
        # FFI pointer-passing uses .cast() without materialising a
        # Rust reference at the call site.
        for fragment in (
            "let p = ssl.as_ptr().cast::<SslHandle>();",
            "libc::setsockopt(fd, level, name, val.cast(), len);",
            "let raw = boxed.cast::<c_void>();",
        ):
            self.assertFalse(
                _has(_scan(fragment), guard.CAST_DEREF_TYPE_PUN_PATTERN), msg=fragment
            )

    def test_deref_without_cast_not_flagged(self) -> None:
        # `&*p` on its own (no `.cast()`) is just a raw-pointer deref;
        # covered by `unsafe_op_in_unsafe_fn = deny` + SAFETY policy,
        # not by the cast-then-deref type-pun rule.
        for fragment in (
            "let r: &T = unsafe { &*ptr };",
            "let m: &mut T = unsafe { &mut *ptr };",
        ):
            self.assertFalse(
                _has(_scan(fragment), guard.CAST_DEREF_TYPE_PUN_PATTERN), msg=fragment
            )

    # --- Negative cases: must NOT trigger the scan -----------------------

    def test_comments_are_ignored(self) -> None:
        src = textwrap.dedent(
            """\
            // The function used to call mem::transmute and Box::from_raw,
            // but we removed it in favor of safer alternatives. The
            // comment must not re-trigger the lint.
            pub fn ok() {}
            """
        )
        self.assertEqual(_scan(src), [])

    def test_block_comments_are_ignored(self) -> None:
        src = textwrap.dedent(
            """\
            /* historical note:
               slice::from_raw_parts was used before MmapRegion took over.
            */
            pub fn ok() {}
            """
        )
        self.assertEqual(_scan(src), [])

    def test_local_identifier_is_not_transmute(self) -> None:
        # A local helper named `transmute_payload` must not be flagged.
        src = "fn transmute_payload(buf: &mut [u8]) {}\n"
        self.assertFalse(_has(_scan(src), "mem::transmute"))

    def test_private_fn_with_raw_pointer_not_flagged(self) -> None:
        # Only public signatures are flagged for raw-pointer arguments.
        src = "fn private_read(ptr: *const u8) -> u8 { 0 }"
        self.assertFalse(_has(_scan(src), "raw pointer in public fn"))


if __name__ == "__main__":
    unittest.main()
