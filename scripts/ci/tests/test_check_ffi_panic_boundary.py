#!/usr/bin/env python3
"""Unit tests for check_ffi_panic_boundary.py.

Exercises the scan engine on synthetic Rust snippets so regressions in
the regex set or brace-balancer surface immediately, independent of any
allowlist baseline.
"""

from __future__ import annotations

import sys
import textwrap
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import check_ffi_panic_boundary as guard  # noqa: E402 (sys.path tweak above)


def _scan(text: str) -> list[tuple[str, str, int, bool]]:
    cleaned = guard.strip_macro_rules_bodies(guard.strip_comments(text))
    out: list[tuple[str, str, int, bool]] = []
    for name, abi, line, body in guard.find_extern_fn_defs(cleaned):
        has = bool(guard.BOUNDARY_RE.search(body))
        out.append((name, abi, line, has))
    return out


class ScannerTests(unittest.TestCase):
    def test_extern_system_fn_without_boundary_is_unprotected(self) -> None:
        src = textwrap.dedent(
            """\
            pub extern "system" fn Java_com_example_foo(env: EnvUnowned<'_>) -> jint {
                adapter::compute(env)
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        name, abi, _line, has_boundary = results[0]
        self.assertEqual(name, "Java_com_example_foo")
        self.assertEqual(abi, "system")
        self.assertFalse(has_boundary)

    def test_extern_c_fn_without_boundary_is_unprotected(self) -> None:
        src = textwrap.dedent(
            """\
            extern "C" fn callback(arg: *mut c_void) -> c_int {
                process(arg)
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        self.assertFalse(results[0][3])

    def test_ffi_boundary_in_body_is_recognised(self) -> None:
        src = textwrap.dedent(
            """\
            pub extern "system" fn Java_com_example_foo(env: EnvUnowned<'_>) -> jstring {
                ffi_boundary(core::ptr::null_mut(), move || adapter::compute(env))
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        self.assertTrue(results[0][3])

    def test_catch_unwind_in_body_is_recognised(self) -> None:
        src = textwrap.dedent(
            """\
            pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _r: *mut c_void) -> jint {
                match std::panic::catch_unwind(|| init()) {
                    Ok(version) => version,
                    Err(_) => JNI_ERR,
                }
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        self.assertTrue(results[0][3])

    def test_qualified_panic_catch_unwind_is_recognised(self) -> None:
        src = textwrap.dedent(
            """\
            extern "C" fn cb(p: *mut u8) -> c_int {
                let result = panic::catch_unwind(|| inner(p));
                result.map(|v| v).unwrap_or(0)
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        self.assertTrue(results[0][3])

    def test_function_pointer_type_is_not_a_definition(self) -> None:
        # `extern "C" fn(...)` without an identifier is a TYPE, not a
        # definition; the scanner must not flag it.
        src = textwrap.dedent(
            """\
            pub fn install(handler: extern "C" fn(c_int)) -> io::Result<()> {
                platform::install(handler)
            }
            """
        )
        results = _scan(src)
        self.assertEqual(results, [])

    def test_forward_declaration_in_extern_block_is_not_a_definition(self) -> None:
        # A forward declaration ends in `;`; only definitions have a body.
        src = textwrap.dedent(
            """\
            extern "C" {
                pub fn SSL_CTX_set_client_hello_cb(ctx: *mut c_void, cb: *mut c_void);
            }
            """
        )
        results = _scan(src)
        self.assertEqual(results, [])

    def test_unsafe_extern_definition_is_scanned(self) -> None:
        src = textwrap.dedent(
            """\
            #[no_mangle]
            pub(super) unsafe extern "C" fn stub_fn(_arg: *mut u8) -> c_int {
                1
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        name, abi, _line, has = results[0]
        self.assertEqual(name, "stub_fn")
        self.assertEqual(abi, "C")
        self.assertFalse(has)

    def test_unwind_capable_abi_variant_is_detected(self) -> None:
        # `extern "C-unwind"` is intentional opt-in; the scanner surfaces
        # it via the `abi` field so the main CLI can require an allowlist
        # entry that documents the unwind tolerance.
        src = textwrap.dedent(
            """\
            pub extern "C-unwind" fn allow_unwind() -> i32 {
                42
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        _name, abi, _line, _has = results[0]
        self.assertEqual(abi, "C-unwind")

    def test_macro_rules_body_is_not_scanned(self) -> None:
        # The `export_jni!` macro defines a template that EXPANDS to
        # `pub extern "system" fn $name(...) { ffi_boundary(...) }`. The
        # scanner must not analyse the template itself as if `$name`
        # were a real symbol -- expansions inherit the body and that's
        # what matters at each call site (which the scanner does walk).
        src = textwrap.dedent(
            """\
            macro_rules! export_jni {
                ($name:ident, $ret:ty, $entry:ident, $default:expr) => {
                    pub extern "system" fn $name(env: EnvUnowned<'_>) -> $ret {
                        $entry(env)
                    }
                };
            }
            """
        )
        results = _scan(src)
        self.assertEqual(results, [])

    def test_macro_rules_does_not_swallow_following_extern_fn(self) -> None:
        # The macro-body stripper must not consume real extern fns that
        # appear AFTER a `macro_rules!` block.
        src = textwrap.dedent(
            """\
            macro_rules! noop_macro {
                () => {
                    pub extern "system" fn ignored() -> i32 { 0 }
                };
            }

            pub extern "system" fn real_export(env: EnvUnowned<'_>) -> jint {
                adapter::compute(env)
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        name, abi, _line, has = results[0]
        self.assertEqual(name, "real_export")
        self.assertEqual(abi, "system")
        self.assertFalse(has)

    def test_body_with_nested_braces_is_correctly_extracted(self) -> None:
        # Brace balancer must walk through nested blocks before deciding
        # the body is complete.
        src = textwrap.dedent(
            """\
            pub extern "system" fn complex(env: EnvUnowned<'_>) -> jint {
                let result = {
                    let x = compute();
                    if x > 0 { x } else { 0 }
                };
                ffi_boundary(0, move || result)
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        # The body includes the inner blocks; ffi_boundary appears once,
        # at the tail, and the scanner must find it.
        self.assertTrue(results[0][3])

    def test_extern_fn_with_return_type_is_handled(self) -> None:
        # The scanner must skip past `-> RetType` (including generics
        # with nested angle brackets) before finding the body brace.
        src = textwrap.dedent(
            """\
            pub extern "system" fn returns_complex<'a>(
                env: EnvUnowned<'a>,
                handle: jlong,
            ) -> Result<jstring, jni::errors::Error> {
                ffi_boundary(core::ptr::null_mut(), move || adapter::run(env, handle))
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        self.assertTrue(results[0][3])

    def test_comment_with_fake_extern_is_ignored(self) -> None:
        # A comment that mentions `extern "C" fn foo` must NOT count as
        # a definition (comments are stripped before scanning).
        src = textwrap.dedent(
            """\
            // pub extern "C" fn fake_in_comment(x: i32) -> i32 { x + 1 }
            /* extern "system" fn also_fake() {} */
            pub fn real_safe_fn() {}
            """
        )
        results = _scan(src)
        self.assertEqual(results, [])

    def test_line_number_points_to_function_name(self) -> None:
        src = textwrap.dedent(
            """\
            // line 1
            // line 2
            extern "C" fn target_fn(arg: *mut u8) {
                process(arg)
            }
            """
        )
        results = _scan(src)
        self.assertEqual(len(results), 1)
        _name, _abi, line, _has = results[0]
        self.assertEqual(line, 3)


class IntegrationTests(unittest.TestCase):
    def test_workspace_scanner_is_green(self) -> None:
        """The committed workspace must pass the scanner with no new
        violations -- regressions surface here."""
        allowlist = guard.load_allowlist()
        scanned = guard.collect()
        unprotected = [fn for fn in scanned if not fn.has_boundary]
        unwind_abi = [fn for fn in scanned if fn.abi.endswith("-unwind")]
        violations = []
        for fn in unprotected + unwind_abi:
            key = (fn.rel_path, fn.function)
            if key not in allowlist:
                violations.append(f"{fn.rel_path}:{fn.line} fn {fn.function} ({fn.abi})")
        self.assertEqual(
            violations,
            [],
            f"workspace has {len(violations)} extern fn(s) without panic boundary or allowlist",
        )


if __name__ == "__main__":
    unittest.main()
