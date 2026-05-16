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

    def test_vec_from_raw_parts_flagged(self) -> None:
        src = "let v = unsafe { Vec::from_raw_parts(p, len, cap) };"
        self.assertTrue(_has(_scan(src), "Vec::from_raw_parts"))

    def test_maybeuninit_assume_init_flagged(self) -> None:
        src = "let value = unsafe { uninit.assume_init() };"
        self.assertTrue(_has(_scan(src), "MaybeUninit::assume_init"))

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
