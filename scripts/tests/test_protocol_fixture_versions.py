from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from scripts.ci.check_protocol_fixture_versions import check_protocol_fixture_versions, pinned_upstream_tag


class ProtocolFixtureVersionsTest(unittest.TestCase):
    def test_current_repository_fixtures_match_pins(self) -> None:
        self.assertEqual([], check_protocol_fixture_versions())

    def test_missing_pinned_tag_directory_fails(self) -> None:
        with fixture_repo() as root:
            remove_tree(root / "contract-fixtures/hysteria2/v2")

            failures = check_protocol_fixture_versions(root)

        self.assertTrue(any("hysteria2: missing pinned fixture tag directory" in failure for failure in failures))

    def test_empty_pinned_tag_directory_fails(self) -> None:
        with fixture_repo() as root:
            remove_tree(root / "contract-fixtures/vless/v1.260206.0")
            (root / "contract-fixtures/vless/v1.260206.0/mux/yamux").mkdir(parents=True)

            failures = check_protocol_fixture_versions(root)

        self.assertTrue(any("vless: pinned fixture tag v1.260206.0 has no .bin fixtures" in failure for failure in failures))

    def test_extra_stale_tag_directory_fails(self) -> None:
        with fixture_repo() as root:
            stale = root / "contract-fixtures/vless/v0.0.0/mux/yamux"
            stale.mkdir(parents=True)
            (stale / "old.bin").write_bytes(b"old")

            failures = check_protocol_fixture_versions(root)

        self.assertTrue(any("vless: stale or unpinned fixture tag 'v0.0.0'" in failure for failure in failures))

    def test_declared_local_vless_fixture_directory_is_allowed(self) -> None:
        with fixture_repo() as root:
            local = root / "contract-fixtures/vless/sing-mux-6fb501d/mux/yamux"
            local.mkdir(parents=True)
            (local / "frame.hex").write_text("00000000", encoding="utf-8")

            failures = check_protocol_fixture_versions(root)

        self.assertEqual([], failures)

    def test_annotated_upstream_tag_normalizes_to_first_token(self) -> None:
        tag = pinned_upstream_tag("- **Upstream tag:** v2 (latest tagged release line)\n")

        self.assertEqual("v2", tag)


def fixture_repo():
    temp = TemporaryDirectory()
    root = Path(temp.name)
    write_spec(root, "ripdpi-hysteria2", "v2 (latest tagged release line)")
    write_spec(root, "ripdpi-vless", "v1.260206.0")
    write_bin(root / "contract-fixtures/hysteria2/v2/salamander/746f702d736563726574/hello.bin")
    write_bin(root / "contract-fixtures/vless/v1.260206.0/mux/yamux/frame.bin")

    class FixtureRepo:
        def __enter__(self) -> Path:
            return root

        def __exit__(self, exc_type, exc, tb) -> None:
            temp.cleanup()

    return FixtureRepo()


def write_spec(root: Path, crate: str, upstream_tag: str) -> None:
    path = root / f"native/rust/crates/{crate}/SPEC_VERSION.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(
            [
                "# Spec Version",
                "",
                "- **Upstream repo:** https://example.invalid/repo",
                f"- **Upstream tag:** {upstream_tag}",
                "- **Upstream commit:** unverified",
                "- **Last reviewed:** 2026-05-21",
            ],
        ),
        encoding="utf-8",
    )


def write_bin(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"fixture")


def remove_tree(path: Path) -> None:
    for child in sorted(path.rglob("*"), reverse=True):
        if child.is_file():
            child.unlink()
        else:
            child.rmdir()
    path.rmdir()


if __name__ == "__main__":
    unittest.main()
