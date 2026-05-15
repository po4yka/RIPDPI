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
    for pattern_name, regex in guard.PATTERNS.items():
        for m in regex.finditer(cleaned):
            line = cleaned.count("\n", 0, m.start()) + 1
            matches.append((pattern_name, line))
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
