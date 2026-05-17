#!/usr/bin/env python3
"""Unit + integration tests for the audit-issues-35-49 scanner suite.

Covers:
  - check_packed_structs.py            (issue 49)
  - check_pin_and_self_reference.py    (issues 35, 36)
  - check_raw_slice_and_layout.py      (issues 47, 48)
  - check_async_safety.py              (issues 38, 39)
  - check_locking_and_shared_state.py  (issues 40, 41, 43, 44)
  - check_phantomdata_variance.py      (issues 45, 46)

For each scanner the test set exercises:
  - one known-positive snippet (regex hits)
  - one known-negative snippet (regex does NOT hit)
  - one "looks like a hit but is excluded" snippet (e.g. test
    module, macro_rules body, allowed proximity-doc) to assert the
    exclusion logic.

The integration test at the bottom asserts the workspace itself is
clean against every scanner + its allowlist (exit 0). This is the
load-bearing test: it guarantees that a fresh checkout passes
`run-rust-lint.sh` without any operator intervention.
"""

from __future__ import annotations

import subprocess
import sys
import textwrap
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(SCRIPTS_DIR))

import check_async_safety as async_guard  # noqa: E402
import check_locking_and_shared_state as locking_guard  # noqa: E402
import check_packed_structs as packed_guard  # noqa: E402
import check_phantomdata_variance as phantom_guard  # noqa: E402
import check_pin_and_self_reference as pin_guard  # noqa: E402
import check_raw_slice_and_layout as raw_guard  # noqa: E402


class PackedStructsTests(unittest.TestCase):
    def test_repr_packed_struct_is_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            #[repr(packed)]
            struct WireHeader { len: u32 }
            """
        )
        self.assertTrue(packed_guard.REPR_ATTR_RE.search(src))
        m = packed_guard.REPR_ATTR_RE.search(src)
        assert m is not None
        self.assertTrue(packed_guard.repr_args_contain_packed(m.group("args")))

    def test_repr_packed_2_is_flagged(self) -> None:
        # `repr(packed(2))` is still packed (just with 2-byte alignment
        # instead of 1-byte). Must trigger.
        src = "#[repr(packed(2))]\nstruct H { len: u32 }\n"
        m = packed_guard.REPR_ATTR_RE.search(src)
        assert m is not None
        self.assertTrue(packed_guard.repr_args_contain_packed(m.group("args")))

    def test_repr_c_alone_is_not_flagged(self) -> None:
        src = "#[repr(C)]\nstruct H { len: u32 }\n"
        m = packed_guard.REPR_ATTR_RE.search(src)
        assert m is not None
        self.assertFalse(packed_guard.repr_args_contain_packed(m.group("args")))

    def test_repr_c_packed_is_flagged(self) -> None:
        # The `repr(C, packed)` combination still demands the
        # discipline; the `C` part alone does NOT excuse it.
        src = "#[repr(C, packed)]\nstruct H { len: u32 }\n"
        m = packed_guard.REPR_ATTR_RE.search(src)
        assert m is not None
        self.assertTrue(packed_guard.repr_args_contain_packed(m.group("args")))


class PinAndSelfReferenceTests(unittest.TestCase):
    def test_phantompinned_is_flagged(self) -> None:
        src = "struct S { _pin: PhantomPinned }\n"
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["PhantomPinned"])

    def test_get_unchecked_mut_is_flagged(self) -> None:
        src = "fn f(s: Pin<&mut S>) { unsafe { s.get_unchecked_mut() }; }\n"
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["get_unchecked_mut"])

    def test_self_referential_field_name_is_flagged(self) -> None:
        src = "struct S { buf: Vec<u8>, ptr_into_buf: *const u8 }\n"
        hits = self._scan(src)
        self.assertIn("self_referential_field_name", [h.pattern_id for h in hits])

    def test_commented_get_unchecked_mut_is_not_flagged(self) -> None:
        # The scanner strips comments before pattern matching; a
        # commented-out reference must not produce a hit.
        src = "// example: s.get_unchecked_mut()\nfn f() {}\n"
        hits = self._scan(src)
        self.assertEqual(hits, [])

    def test_macro_rules_body_is_not_flagged(self) -> None:
        # Macro definitions expand at the CALL site; the pattern
        # inside the body is not itself a Pin violation in the
        # defining module.
        src = textwrap.dedent(
            """\
            macro_rules! my_macro {
                () => { Pin::new_unchecked(self) };
            }
            """
        )
        hits = self._scan(src)
        self.assertEqual(hits, [])

    @staticmethod
    def _scan(text: str) -> list[pin_guard.PinHit]:
        cleaned = pin_guard.strip_macro_rules_bodies(pin_guard.strip_comments(text))
        out: list[pin_guard.PinHit] = []
        for pattern_id, regex in pin_guard.PIN_PATTERNS:
            for m in regex.finditer(cleaned):
                line_no = cleaned.count("\n", 0, m.start()) + 1
                out.append(
                    pin_guard.PinHit(
                        rel_path="<inline>",
                        pattern_id=pattern_id,
                        line=line_no,
                        excerpt="",
                    )
                )
        return out


class RawSliceAndLayoutTests(unittest.TestCase):
    def test_from_raw_parts_is_flagged(self) -> None:
        src = "let s = unsafe { slice::from_raw_parts(ptr, len) };\n"
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["from_raw_parts"])

    def test_from_raw_parts_mut_is_flagged(self) -> None:
        src = "let s = unsafe { slice::from_raw_parts_mut(ptr, len) };\n"
        hits = self._scan(src)
        # The `_mut` variant also matches the non-mut pattern's prefix
        # by design; assert at least the `_mut` id is present.
        self.assertIn("from_raw_parts_mut", [h.pattern_id for h in hits])

    def test_layout_from_size_align_is_flagged(self) -> None:
        src = "let l = Layout::from_size_align(bytes, align)?;\n"
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["Layout::from_size_align"])

    def test_layout_array_is_not_flagged(self) -> None:
        # `Layout::array::<T>(count)` is the SAFE alternative; it must
        # not produce a hit.
        src = "let l = Layout::array::<u32>(count)?;\n"
        hits = self._scan(src)
        self.assertEqual(hits, [])

    def test_size_of_times_count_is_flagged(self) -> None:
        src = "let bytes = count * std::mem::size_of::<u32>();\n"
        hits = self._scan(src)
        self.assertIn("size_of_times_count", [h.pattern_id for h in hits])

    def test_size_of_alone_is_not_flagged(self) -> None:
        # `size_of::<T>()` used as a constant in a non-arithmetic
        # context (e.g. a struct-size assertion) must NOT trigger.
        src = "const SZ: usize = std::mem::size_of::<u32>();\n"
        hits = self._scan(src)
        self.assertEqual(hits, [])

    @staticmethod
    def _scan(text: str) -> list[raw_guard.RawHit]:
        cleaned = raw_guard.strip_macro_rules_bodies(raw_guard.strip_comments(text))
        out: list[raw_guard.RawHit] = []
        for pattern_id, regex in raw_guard.PATTERNS:
            for m in regex.finditer(cleaned):
                line_no = cleaned.count("\n", 0, m.start()) + 1
                out.append(
                    raw_guard.RawHit(
                        rel_path="<inline>",
                        pattern_id=pattern_id,
                        line=line_no,
                        excerpt="",
                    )
                )
        return out


class AsyncSafetyTests(unittest.TestCase):
    def test_blocking_to_socket_addrs_in_async_fn_is_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            async fn resolve(host: &str) -> Result<SocketAddr, Error> {
                let addr = (host, 80).to_socket_addrs()?.next().unwrap();
                Ok(addr)
            }
            """
        )
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["to_socket_addrs"])

    def test_blocking_in_sync_fn_is_not_flagged(self) -> None:
        # The discipline is about parking tokio worker threads.
        # A sync fn doing blocking IO is fine.
        src = textwrap.dedent(
            """\
            fn resolve(host: &str) -> Result<SocketAddr, Error> {
                let addr = (host, 80).to_socket_addrs()?.next().unwrap();
                Ok(addr)
            }
            """
        )
        hits = self._scan(src)
        self.assertEqual(hits, [])

    def test_cfg_test_module_is_skipped(self) -> None:
        # Test modules are skipped wholesale -- they routinely use
        # `std::thread::sleep` for timing fixtures and resolve
        # localhost addresses synchronously.
        src = textwrap.dedent(
            """\
            #[cfg(test)]
            mod tests {
                async fn time_dependent_test() {
                    std::thread::sleep(Duration::from_millis(50));
                }
            }
            """
        )
        hits = self._scan(src)
        self.assertEqual(hits, [])

    def test_thread_sleep_in_async_fn_is_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            async fn slow() {
                std::thread::sleep(Duration::from_secs(1));
            }
            """
        )
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["std::thread::sleep"])

    @staticmethod
    def _scan(text: str) -> list[async_guard.AsyncHit]:
        cleaned = async_guard.strip_cfg_test_modules(
            async_guard.strip_macro_rules_bodies(async_guard.strip_comments(text))
        )
        bodies = async_guard.extract_async_bodies(cleaned)
        out: list[async_guard.AsyncHit] = []
        for start, end in bodies:
            body = cleaned[start:end]
            offset = cleaned.count("\n", 0, start)
            for pattern_id, regex in async_guard.BLOCKING_PATTERNS:
                for m in regex.finditer(body):
                    line_no = offset + body.count("\n", 0, m.start()) + 1
                    out.append(
                        async_guard.AsyncHit(
                            rel_path="<inline>",
                            pattern_id=pattern_id,
                            line=line_no,
                            excerpt="",
                        )
                    )
        return out


class LockingAndSharedStateTests(unittest.TestCase):
    def test_rc_refcell_is_flagged(self) -> None:
        src = "struct G { node: Rc<RefCell<Node>> }\n"
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["Rc<RefCell>"])

    def test_arc_mutex_is_not_flagged(self) -> None:
        # Arc<Mutex<T>> is the legitimate multi-producer primitive
        # and is not in scope for this guard (it has its own
        # `await_holding_lock` lint instead).
        src = "struct S { inner: Arc<Mutex<T>> }\n"
        hits = self._scan(src)
        self.assertEqual(hits, [])

    def test_rc_mutex_is_flagged(self) -> None:
        # Rc<Mutex<T>> is almost always a typo for Arc<Mutex<T>>.
        src = "struct S { inner: Rc<Mutex<T>> }\n"
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["Rc<Mutex>"])

    def test_qualified_rc_refcell_is_flagged(self) -> None:
        # The fully qualified form must also match.
        src = "struct G { node: std::rc::Rc<core::cell::RefCell<Node>> }\n"
        hits = self._scan(src)
        self.assertEqual([h.pattern_id for h in hits], ["Rc<RefCell>"])

    @staticmethod
    def _scan(text: str) -> list[locking_guard.LockHit]:
        cleaned = locking_guard.strip_macro_rules_bodies(
            locking_guard.strip_comments(text)
        )
        out: list[locking_guard.LockHit] = []
        for pattern_id, regex in locking_guard.PATTERNS:
            for m in regex.finditer(cleaned):
                line_no = cleaned.count("\n", 0, m.start()) + 1
                out.append(
                    locking_guard.LockHit(
                        rel_path="<inline>",
                        pattern_id=pattern_id,
                        line=line_no,
                        excerpt="",
                    )
                )
        return out


class PhantomDataVarianceTests(unittest.TestCase):
    def test_undocumented_phantomdata_is_flagged(self) -> None:
        src = "struct W<T> { _p: PhantomData<T> }\n"
        self.assertFalse(self._has_marker_nearby(src, line=1))

    def test_documented_phantomdata_is_not_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            struct W<T> {
                // Variance: invariant in T (zero-sized phantom).
                _p: PhantomData<T>,
            }
            """
        )
        # Field is on line 3; the comment is on line 2 -- within ±10.
        self.assertTrue(self._has_marker_nearby(src, line=3))

    def test_auto_trait_helper_file_is_skipped(self) -> None:
        # Files that house `AmbiguousIfSend` / `AmbiguousIfSync` /
        # `AmbiguousIfCopy` are auto-trait assertion infrastructure;
        # their PhantomData uses are not a soundness boundary and
        # must be skipped wholesale.
        src = "trait AmbiguousIfSend {} \nstruct Check<T>(PhantomData<T>);\n"
        self.assertTrue(phantom_guard.AUTO_TRAIT_HELPER_NAMES.search(src))

    def test_far_comment_does_not_satisfy_marker(self) -> None:
        # A `Variance:` comment 20 lines away from the field does NOT
        # satisfy the proximity rule -- the comment must be near the
        # field it describes, not buried in a distant impl.
        comment_block = "// Variance: imaginary documentation\n" * 1
        spacer = "\n" * 20
        src = comment_block + spacer + "struct W<T> { _p: PhantomData<T> }\n"
        target_line = src.count("\n", 0, src.index("PhantomData")) + 1
        self.assertFalse(self._has_marker_nearby(src, line=target_line))

    @staticmethod
    def _has_marker_nearby(text: str, line: int) -> bool:
        return phantom_guard.has_proximity_marker(text, line)


class WorkspaceCleanIntegrationTests(unittest.TestCase):
    """The load-bearing test: every scanner exits 0 against the live
    workspace under its checked-in allowlist. If this fails, either
    the workspace has gained a new unallowlisted pattern or a recent
    refactor moved a previously-allowlisted line number."""

    SCANNERS = [
        "check_packed_structs.py",
        "check_pin_and_self_reference.py",
        "check_raw_slice_and_layout.py",
        "check_async_safety.py",
        "check_locking_and_shared_state.py",
        "check_phantomdata_variance.py",
    ]

    def test_all_scanners_pass_against_workspace(self) -> None:
        for name in self.SCANNERS:
            with self.subTest(scanner=name):
                result = subprocess.run(
                    [sys.executable, str(SCRIPTS_DIR / name)],
                    capture_output=True,
                    text=True,
                    cwd=str(REPO_ROOT),
                )
                self.assertEqual(
                    result.returncode,
                    0,
                    msg=(
                        f"{name} returned {result.returncode}.\n"
                        f"STDOUT:\n{result.stdout}\n"
                        f"STDERR:\n{result.stderr}"
                    ),
                )


if __name__ == "__main__":
    unittest.main()
