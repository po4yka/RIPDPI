#!/usr/bin/env python3
"""Unit + integration tests for check_drop_order.py.

Exercises the regex set, brace-balancer, field parser, and resource-
pattern classifier on synthetic Rust snippets so a regression in the
scanner surfaces here rather than as a confusing production-scan false
positive. The integration test at the bottom asserts the workspace
itself is clean against the scanner + allowlist.
"""

from __future__ import annotations

import io
import subprocess
import sys
import textwrap
import unittest
from contextlib import redirect_stdout
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(SCRIPTS_DIR))

import check_drop_order as guard  # noqa: E402 (sys.path tweak above)


def _scan_text(text: str) -> list[guard.DropCandidate]:
    """Run the scanner over an in-memory Rust snippet."""
    cleaned = guard.strip_macro_rules_bodies(guard.strip_comments(text))
    has_marker = bool(guard.DROP_ORDER_MARKER_RE.search(text))
    drop_targets = guard.find_impl_drop_targets(cleaned)
    seen: set[str] = set()
    out: list[guard.DropCandidate] = []
    for type_name, _line in drop_targets:
        if type_name in seen:
            continue
        seen.add(type_name)
        struct = guard.find_struct_body(cleaned, type_name)
        if struct is None:
            continue
        struct_line, body = struct
        fields = guard.parse_fields(body)
        flagged = guard.classify_field_resources(fields)
        if len(flagged) < 2:
            continue
        out.append(
            guard.DropCandidate(
                rel_path="<inline>",
                type_name=type_name,
                line=struct_line,
                resource_fields=tuple(flagged),
                has_marker_comment=has_marker,
            )
        )
    return out


class ScannerTests(unittest.TestCase):
    def test_struct_without_drop_impl_is_not_flagged(self) -> None:
        # No `impl Drop for ...` means the struct is out of scope --
        # field ordering only matters when there's a destructor.
        src = textwrap.dedent(
            """\
            pub struct Plain {
                tx: flume::Sender<u8>,
                thread: JoinHandle<()>,
            }
            """
        )
        self.assertEqual(_scan_text(src), [])

    def test_struct_with_one_resource_field_is_not_flagged(self) -> None:
        # 2+ resource fields is the trigger; a sole resource has no
        # cross-field drop interaction.
        src = textwrap.dedent(
            """\
            pub struct Sole {
                inner: JoinHandle<()>,
                label: String,
                count: usize,
            }

            impl Drop for Sole {
                fn drop(&mut self) {}
            }
            """
        )
        self.assertEqual(_scan_text(src), [])

    def test_multi_resource_without_marker_is_flagged(self) -> None:
        src = textwrap.dedent(
            """\
            pub struct Worker {
                tx: flume::Sender<u8>,
                thread: JoinHandle<()>,
            }

            impl Drop for Worker {
                fn drop(&mut self) {
                    let _ = self.tx.send(0);
                }
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        self.assertEqual(flagged[0].type_name, "Worker")
        self.assertFalse(flagged[0].has_marker_comment)
        # Both resource fields surface in the report.
        labels = {(name, label) for name, _ty, label in flagged[0].resource_fields}
        self.assertEqual(labels, {("tx", "flume::Sender"), ("thread", "JoinHandle")})

    def test_multi_resource_with_line_marker_passes(self) -> None:
        src = textwrap.dedent(
            """\
            // Drop order: tx fires Shutdown before thread joins; field
            // order is incidental because the body completes both
            // legs of the handshake before any implicit field drop.
            pub struct Worker {
                tx: flume::Sender<u8>,
                thread: JoinHandle<()>,
            }

            impl Drop for Worker {
                fn drop(&mut self) {
                    let _ = self.tx.send(0);
                }
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        self.assertTrue(flagged[0].has_marker_comment)

    def test_marker_in_doc_comment_counts(self) -> None:
        src = textwrap.dedent(
            """\
            /// A worker pair.
            ///
            /// Drop order: tx then thread.
            pub struct Worker {
                tx: flume::Sender<u8>,
                thread: JoinHandle<()>,
            }

            impl Drop for Worker {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        self.assertTrue(flagged[0].has_marker_comment)

    def test_marker_in_block_comment_counts(self) -> None:
        src = textwrap.dedent(
            """\
            /* Drop order: documented via block comment. */
            pub struct Worker {
                tx: flume::Sender<u8>,
                thread: JoinHandle<()>,
            }

            impl Drop for Worker {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        self.assertTrue(flagged[0].has_marker_comment)

    def test_marker_is_case_insensitive(self) -> None:
        src = textwrap.dedent(
            """\
            // DROP ORDER: capitalised marker still counts.
            pub struct Worker {
                tx: flume::Sender<u8>,
                thread: JoinHandle<()>,
            }

            impl Drop for Worker {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        self.assertTrue(flagged[0].has_marker_comment)

    def test_drop_impl_for_unrelated_type_is_ignored(self) -> None:
        # `impl Drop for Other` does not police `Worker`'s fields.
        src = textwrap.dedent(
            """\
            pub struct Worker {
                tx: flume::Sender<u8>,
                thread: JoinHandle<()>,
            }

            pub struct Other(u8);

            impl Drop for Other {
                fn drop(&mut self) {}
            }
            """
        )
        self.assertEqual(_scan_text(src), [])

    def test_generic_struct_and_generic_impl_are_matched(self) -> None:
        src = textwrap.dedent(
            """\
            // Drop order: state-lock before permit release.
            pub struct LeaseGuard<S>
            where
                S: RelaySession,
            {
                state: Arc<Mutex<RelayMuxState<S>>>,
                session: Option<Arc<S>>,
                permit: OwnedSemaphorePermit,
                handle: JoinHandle<()>,
            }

            impl<S> Drop for LeaseGuard<S>
            where
                S: RelaySession,
            {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        self.assertEqual(flagged[0].type_name, "LeaseGuard")
        self.assertTrue(flagged[0].has_marker_comment)

    def test_raw_pointer_field_is_a_resource(self) -> None:
        src = textwrap.dedent(
            """\
            pub struct DualPtr {
                a: *mut u8,
                b: NonNull<u32>,
            }

            impl Drop for DualPtr {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        labels = {label for _name, _ty, label in flagged[0].resource_fields}
        self.assertIn("raw pointer (*mut)", labels)
        self.assertIn("NonNull", labels)

    def test_field_in_option_is_still_classified(self) -> None:
        # The scanner classifies by substring match on the type
        # expression; `Option<JoinHandle<()>>` still contains
        # `JoinHandle`, so it counts.
        src = textwrap.dedent(
            """\
            pub struct Worker {
                tx: Option<oneshot::Sender<()>>,
                thread: Option<JoinHandle<()>>,
            }

            impl Drop for Worker {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        labels = {label for _name, _ty, label in flagged[0].resource_fields}
        self.assertEqual(labels, {"oneshot::Sender", "JoinHandle"})

    def test_macro_rules_body_is_not_scanned(self) -> None:
        # The macro template defines a `struct` + `impl Drop` pair
        # but expansions inherit the body at each call site; the
        # scanner must not analyse the template as if `$Name` were
        # a concrete identifier.
        src = textwrap.dedent(
            """\
            macro_rules! gen_worker {
                ($name:ident) => {
                    pub struct $name {
                        tx: flume::Sender<u8>,
                        thread: JoinHandle<()>,
                    }
                    impl Drop for $name {
                        fn drop(&mut self) {}
                    }
                };
            }
            """
        )
        self.assertEqual(_scan_text(src), [])

    def test_split_top_level_commas_respects_generics(self) -> None:
        # The field splitter must not break inside `<...>`.
        body = "tx: mpsc::Sender<(String, Vec<u8>)>, rx: mpsc::Receiver<(String, Vec<u8>)>"
        pieces = guard._split_top_level_commas(body)
        self.assertEqual(len(pieces), 2)
        self.assertEqual(pieces[0], "tx: mpsc::Sender<(String, Vec<u8>)>")
        self.assertEqual(pieces[1], "rx: mpsc::Receiver<(String, Vec<u8>)>")

    def test_split_top_level_commas_respects_tuples(self) -> None:
        body = "a: (u8, u16, u32), b: u64"
        pieces = guard._split_top_level_commas(body)
        self.assertEqual(pieces, ["a: (u8, u16, u32)", "b: u64"])

    def test_field_visibility_and_attributes_tolerated(self) -> None:
        src = textwrap.dedent(
            """\
            // Drop order: documented.
            pub struct Worker {
                pub(crate) tx: flume::Sender<u8>,
                #[allow(dead_code)]
                pub thread: JoinHandle<()>,
            }

            impl Drop for Worker {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        names = {name for name, _ty, _label in flagged[0].resource_fields}
        self.assertEqual(names, {"tx", "thread"})

    def test_runtime_inside_arc_is_classified(self) -> None:
        src = textwrap.dedent(
            """\
            pub struct WithRuntime {
                runtime: Arc<tokio::runtime::Runtime>,
                handle: JoinHandle<()>,
            }
            impl Drop for WithRuntime {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        labels = {label for _name, _ty, label in flagged[0].resource_fields}
        self.assertEqual(labels, {"Runtime", "JoinHandle"})

    def test_owned_fd_plus_raw_pointer_is_classified(self) -> None:
        src = textwrap.dedent(
            """\
            pub struct FdPair {
                fd: OwnedFd,
                ptr: NonNull<u8>,
            }
            impl Drop for FdPair {
                fn drop(&mut self) {}
            }
            """
        )
        flagged = _scan_text(src)
        self.assertEqual(len(flagged), 1)
        labels = {label for _name, _ty, label in flagged[0].resource_fields}
        self.assertEqual(labels, {"OwnedFd", "NonNull"})


class CliTests(unittest.TestCase):
    """Smoke test the main entry point on a single-shot scan of the
    real workspace. Assumes the workspace is currently clean (the
    scanner + the inline markers + the empty allowlist must agree)."""

    def test_workspace_passes_scanner(self) -> None:
        scanner = SCRIPTS_DIR / "check_drop_order.py"
        result = subprocess.run(
            [sys.executable, str(scanner)],
            check=False,
            capture_output=True,
            text=True,
            cwd=REPO_ROOT,
        )
        self.assertEqual(
            result.returncode,
            0,
            msg=f"scanner failed unexpectedly:\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}",
        )
        self.assertIn("OK", result.stdout)

    def test_main_passes_in_process_too(self) -> None:
        # The subprocess call above is the integration shape; this one
        # exercises the same flow in-process so test-suite failures
        # come with a Python traceback rather than a returncode-only
        # signal.
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = guard.main()
        self.assertEqual(rc, 0, msg=f"in-process scanner failed:\n{buf.getvalue()}")


if __name__ == "__main__":
    unittest.main()
