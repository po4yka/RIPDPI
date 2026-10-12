"""Regression checks for audited Russian safety text and locale resource contracts."""

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LOCALES = ("values", "values-ru", "values-es", "values-de", "values-fr", "values-fa",
           "values-ar", "values-zh-rCN", "values-hi", "values-pt-rBR")
FORMAT = re.compile(r"%(?:\d+\$)?[a-zA-Z]")


def resources(module, locale):
    result = {}
    for file in sorted((ROOT / module / "src/main/res" / locale).glob("*.xml")):
        for entry in ET.parse(file).getroot():
            name = entry.get("name")
            if entry.tag in ("string", "plurals", "string-array"):
                if name in result:
                    raise AssertionError(f"Duplicate {module}/{locale}/{name}")
                result[name] = entry
    return result


def text(entry):
    return "".join(entry.itertext()).strip()


class LocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ru = resources("app", "values-ru")
        cls.en = resources("app", "values")

    def test_sensitive_settings_and_analysis_keep_their_safety_meaning(self):
        warning = text(self.ru["support_settings_sensitive_body"])
        for term in ("трафик", "учётные данные", "диагностические данные", "привилегиями", "доверяете"):
            self.assertIn(term, warning)
        analysis = text(self.ru["home_diagnostics_analysis_body"])
        for term in ("применить", "обхода", "DNS", "WARP"):
            self.assertIn(term, analysis)
        self.assertNotIn("без ограничения", analysis)
        for key in ("detection_onboarding_body", "detection_permission_rationale", "detection_permission_settings"):
            self.assertIn("скрывает", text(self.ru[key]))
            self.assertNotIn("редактирует", text(self.ru[key]))

    def test_recovery_actions_reference_the_visible_russian_labels(self):
        pairs = (
            ("home_remediation_command_line_step_open_settings", "title_advanced_settings"),
            ("diagnostics_remediation_command_line_step_disable_setting", "use_command_line_settings"),
            ("home_remediation_command_line_step_retry", "home"),
            ("home_remediation_command_line_step_retry", "home_diagnostics_run_analysis"),
            ("diagnostics_remediation_browser_relay_step_open_mode_editor", "title_mode_editor"),
            ("home_remediation_stale_step_open_diagnostics", "diagnostics_title"),
            ("diagnostics_remediation_review_step_open_history", "history_title"),
            ("pause_clock_failed", "pause_resume_now"),
        )
        for instruction, label in pairs:
            with self.subTest(instruction=instruction, label=label):
                self.assertIn(text(self.ru[label]), text(self.ru[instruction]))
        for key, entry in self.ru.items():
            if key.startswith(("home_remediation_", "diagnostics_remediation_")):
                for leaked_label in ("Advanced Settings", "Mode Editor", "Diagnostics", "History", "Home", "Full Analysis"):
                    self.assertNotIn(leaked_label, text(entry), key)

    def test_audited_user_prose_is_translated_but_protocol_tokens_remain(self):
        groups = ("support_settings_", "update_", "profile_share_", "config_chain_",
                  "diagnostics_device_gate_status_", "config_relay_finalmask_")
        allowed = {"update_channel_play", "update_channel_fdroid", "update_channel_github",
                   "config_relay_finalmask_title", "config_relay_finalmask_sudoku"}
        for key, entry in self.en.items():
            if entry.tag == "string" and key.startswith(groups) and key not in allowed:
                self.assertNotEqual(text(entry), text(self.ru[key]), key)
        parser = text(self.ru["ripdpi_http_parser_probing_summary"])
        for token in ("Host Pad", "Unix EOL", "Method EOL", "Method Space"):
            self.assertIn(token, parser)
        self.assertIn("повтор", text(self.ru["block_signal_tcp_retransmissions"]).lower())
        self.assertNotIn("недоступен", text(self.ru["diagnostics_scan_dns_baseline_resolution_failed"]))

    def test_new_resources_keep_type_and_format_contracts_in_every_locale(self):
        for module in ("app", "core/service"):
            source = resources(module, "values")
            directory = ROOT / module / "src/main/res/values"
            audited_keys = {
                entry.get("name")
                for file in directory.glob("strings*localization.xml")
                for entry in ET.parse(file).getroot()
            }
            for locale in LOCALES:
                translated = resources(module, locale)
                for key in audited_keys:
                    with self.subTest(module=module, locale=locale, key=key):
                        original, entry = source[key], translated[key]
                        self.assertEqual(original.tag, entry.tag)
                        original_text = text(original) if original.tag == "string" else text(next(iter(original)))
                        expected = sorted(FORMAT.findall(original_text))
                        variants = [entry] if entry.tag == "string" else list(entry)
                        for variant in variants:
                            self.assertEqual(expected, sorted(FORMAT.findall(text(variant))))
                        if entry.tag == "plurals":
                            quantities = {item.get("quantity") for item in entry}
                            self.assertIn("other", quantities)
                            if locale == "values-ru":
                                self.assertTrue({"one", "few", "many", "other"} <= quantities)
                            elif locale in ("values-es", "values-fr", "values-pt-rBR"):
                                self.assertTrue({"one", "many", "other"} <= quantities)


if __name__ == "__main__":
    unittest.main()
