from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("adb_ui_flow", ROOT / "test-lab/scripts/adb-ui-flow.py")
assert SPEC is not None and SPEC.loader is not None
adb_ui_flow = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adb_ui_flow)

DUMP = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node text="" resource-id="home-mode-primary-remote-vpn" content-desc="" bounds="[100,200][300,260]">
  <node text="Inactive" resource-id="" content-desc="" bounds="[110,210][190,250]" />
</node>
<node text="" resource-id="com.poyka.ripdpi:id/home-modes-diagnostics-header" content-desc="Modes" bounds="[0,0][1080,120]" />
</hierarchy>UI hierchary dumped to: /dev/tty"""


class AdbUiFlowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = adb_ui_flow.parse_dump(DUMP)

    def test_dump_status_line_is_ignored(self) -> None:
        self.assertEqual("hierarchy", self.root.tag)

    def test_finds_plain_and_package_qualified_test_tags(self) -> None:
        vpn = adb_ui_flow.find_node(self.root, tag="home-mode-primary-remote-vpn")
        header = adb_ui_flow.find_node(self.root, tag="home-modes-diagnostics-header")
        self.assertEqual((200, 230), adb_ui_flow.center(vpn))
        self.assertEqual((540, 60), adb_ui_flow.center(header))

    def test_text_match_is_full_match_over_text_and_content_desc(self) -> None:
        self.assertIsNone(adb_ui_flow.find_node(self.root, text="Active"))
        self.assertIsNotNone(adb_ui_flow.find_node(self.root, text="Inactive"))
        self.assertIsNotNone(adb_ui_flow.find_node(self.root, text="Mod.s"))

    def test_dump_without_xml_is_an_error(self) -> None:
        with self.assertRaises(adb_ui_flow.FlowError):
            adb_ui_flow.parse_dump("ERROR: could not get idle state.")


if __name__ == "__main__":
    unittest.main()
