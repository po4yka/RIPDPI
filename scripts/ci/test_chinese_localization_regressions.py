"""Regression checks for audited Chinese privacy text and action references."""

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from test_localization_regressions import LOCALES, resources, text


class ChineseLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.zh = resources("app", "values-zh-rCN")
        cls.en = resources("app", "values")

    def test_audited_prose_is_translated(self):
        groups = ("support_settings_", "update_", "profile_share_", "config_chain_",
                  "diagnostics_device_gate_status_", "config_relay_finalmask_")
        allowed = {"update_channel_play", "update_channel_fdroid", "update_channel_github",
                   "config_relay_finalmask_title", "config_relay_finalmask_sudoku"}
        extra = ("updates_section_title", "diagnostics_save_logs_body", "diagnostics_pcap_card_body_idle",
                 "diagnostics_share_archive_line", "settings_diagnostics_retention_body",
                 "settings_diagnostics_export_history_body", "title_local_bypass_config",
                 "home_mode_card_remote_relay_disabled_hint", "ripdpi_desync_method_setting",
                 "data_transparency_no_browsing", "data_transparency_no_personal_data",
                 "data_transparency_export_explicit", "data_transparency_export_control")
        for key in self.en:
            if (key.startswith(groups) and key not in allowed) or key in extra:
                with self.subTest(key=key):
                    value = text(self.zh[key])
                    self.assertNotEqual(text(self.en[key]), value)
                    if key != "support_settings_change_summary":
                        self.assertRegex(value, r"[\u4e00-\u9fff]")
        for key in ("config_preset_proxy_title", "config_preset_proxy_body"):
            self.assertNotIn("Local bypass", text(self.zh[key]))

    def test_sensitive_data_and_retention_boundaries_remain_explicit(self):
        warning = text(self.zh["support_settings_sensitive_body"])
        for term in ("流量", "凭据", "诊断", "特权", "信任"):
            self.assertIn(term, warning)
        self.assertIn("凭据", text(self.zh["profile_share_setup_sheet_template"]))
        pcap = text(self.zh["diagnostics_pcap_card_body_idle"])
        for term in ("原始", "24", "3", "自动分享"):
            self.assertIn(term, pcap)
        retention = text(self.zh["settings_diagnostics_retention_body"])
        for term in ("应用", "删除", "其他位置"):
            self.assertIn(term, retention)
        self.assertIn("脱敏", text(self.zh["share_link_preview_privacy_message"]))

    def test_network_authority_is_not_an_organization_or_permission(self):
        keys = (
            "diagnostics_remediation_owned_stack_title", "diagnostics_remediation_owned_stack_summary",
            "diagnostics_remediation_owned_stack_step_open_browser", "diagnostics_remediation_no_hint_step_review_evidence",
            "home_remediation_owned_stack_step_verify_result", "owned_stack_browser_trace_confirmed_ech",
            "home_remediation_owned_stack_summary", "owned_stack_browser_trace_opportunistic_platform",
            "home_remediation_no_hint_step_review_evidence", "home_remediation_owned_stack_step_open_browser",
            "home_remediation_owned_stack_title", "owned_stack_browser_trace_native_ech_missing",
        )
        for key in keys:
            with self.subTest(key=key):
                value = text(self.zh[key])
                self.assertRegex(value, "目标网站|目标主机")
                for wrong in ("机构", "权威", "授权"):
                    self.assertNotIn(wrong, value)

    def test_all_resources_keep_types_and_format_arguments(self):
        argument = re.compile(r"(?<!%)%(?!%)(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z]")
        for module in ("app", "core/service"):
            source = resources(module, "values")
            for locale in LOCALES:
                translated = resources(module, locale)
                for key, original in source.items():
                    if original.get("translatable") == "false":
                        continue
                    with self.subTest(module=module, locale=locale, key=key):
                        entry = translated[key]
                        self.assertEqual(original.tag, entry.tag)
                        original_variants = [original] if original.tag == "string" else list(original)
                        expected = sorted(argument.findall(text(original_variants[0])))
                        variants = [entry] if entry.tag == "string" else list(entry)
                        for variant in variants:
                            actual = sorted(argument.findall(text(variant)))
                            # Arabic zero/one/two forms can spell out the count.
                            if entry.tag == "plurals" and locale == "values-ar" and variant.get("quantity") in ("zero", "one", "two"):
                                without_count = [arg for arg in expected if not arg.endswith("d")]
                                self.assertIn(actual, (expected, without_count))
                            else:
                                self.assertEqual(expected, actual)

    def test_recovery_names_match_visible_actions(self):
        instructions = (
            "diagnostics_remediation_browser_relay_step_open_mode_editor",
            "diagnostics_remediation_quic_relay_step_open_mode_editor",
            "diagnostics_remediation_open_mode_editor_action",
            "home_remediation_browser_relay_step_open_mode_editor",
            "home_remediation_quic_relay_step_open_mode_editor",
            "home_remediation_browser_relay_summary", "home_remediation_quic_relay_summary",
        )
        for key in instructions:
            with self.subTest(key=key):
                self.assertIn(text(self.zh["title_mode_editor"]), text(self.zh[key]))
        self.assertIn(text(self.zh["pause_resume_now"]), text(self.zh["pause_clock_failed"]))
        root = Path(__file__).resolve().parents[2]
        simple = {entry.get("name"): text(entry) for entry in ET.parse(
            root / "app/src/simple/res/values-zh-rCN/strings.xml").getroot()}
        self.assertIn(simple["simple_title"], simple["qs_tile_label_connected"])


if __name__ == "__main__":
    unittest.main()
