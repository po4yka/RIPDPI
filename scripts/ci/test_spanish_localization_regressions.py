"""Regression checks for the confirmed Spanish resource audit findings."""

import re
import unittest
import xml.etree.ElementTree as ET

from test_localization_regressions import LOCALES, ROOT, resources, text


EXTRA_PROSE = (
    "updates_section_title", "ripdpi_desync_method_setting", "data_transparency_no_browsing",
    "settings_diagnostics_export_history_body", "settings_diagnostics_retention_body",
    "data_transparency_export_control", "title_local_bypass_config",
    "data_transparency_export_explicit", "data_transparency_no_personal_data",
    "diagnostics_save_logs_body", "diagnostics_pcap_card_body_idle", "diagnostics_share_archive_line",
)
EDITOR_REFERENCES = (
    "diagnostics_remediation_browser_relay_step_open_mode_editor",
    "diagnostics_remediation_quic_relay_step_open_mode_editor",
    "diagnostics_remediation_open_mode_editor_action",
    "home_remediation_browser_relay_step_open_mode_editor",
    "home_remediation_quic_relay_step_open_mode_editor",
    "home_remediation_browser_relay_summary", "home_remediation_quic_relay_summary",
)


class SpanishLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.es = resources("app", "values-es")
        cls.en = resources("app", "values")

    def test_audited_prose_is_translated(self):
        groups = ("support_settings_", "update_", "profile_share_", "config_chain_",
                  "diagnostics_device_gate_status_", "config_relay_finalmask_")
        allowed = {"update_channel_play", "update_channel_fdroid", "update_channel_github",
                   "config_relay_finalmask_title", "config_relay_finalmask_sudoku",
                   "config_chain_step_hostfake_label", "config_chain_step_quic_multi_initial_label",
                   "config_chain_step_byte_count"}
        for key in self.en:
            if (key.startswith(groups) and key not in allowed) or key in EXTRA_PROSE:
                with self.subTest(key=key):
                    self.assertNotEqual(text(self.en[key]), text(self.es[key]))

    def test_safety_and_retention_meanings_remain_explicit(self):
        warning = text(self.es["support_settings_sensitive_body"])
        for term in ("tráfico", "credenciales", "diagnóstico", "privileg", "confía"):
            self.assertIn(term, warning)
        pcap = text(self.es["diagnostics_pcap_card_body_idle"])
        for term in ("sin procesar", "24", "3", "automática"):
            self.assertIn(term, pcap)
        retention = text(self.es["settings_diagnostics_retention_body"])
        for term in ("aplicación", "elimine", "otros lugares"):
            self.assertIn(term, retention)
        for key in ("detection_permission_rationale", "detection_onboarding_body",
                    "detection_permission_settings", "home_diagnostics_detection_permission_required"):
            with self.subTest(key=key):
                self.assertIn("ocultan", text(self.es[key]))
                self.assertNotIn("redact", text(self.es[key]))

    def test_recovery_names_match_visible_actions(self):
        for key in EDITOR_REFERENCES:
            with self.subTest(key=key):
                self.assertIn(text(self.es["title_mode_editor"]), text(self.es[key]))
        self.assertIn(text(self.es["title_owned_stack_browser"]),
                      text(self.es["diagnostics_remediation_open_owned_stack_action"]))
        self.assertIn(text(self.es["pause_resume_now"]), text(self.es["pause_clock_failed"]))
        simple = {e.get("name"): text(e) for e in ET.parse(
            ROOT / "app/src/simple/res/values-es/strings.xml").getroot()}
        self.assertIn(simple["simple_title"], simple["qs_tile_label_connected"])

    def test_strategy_gender_and_duplicate_are_correct(self):
        for key, entry in self.es.items():
            value = text(entry)
            with self.subTest(key=key):
                self.assertNotRegex(value, r"(?i)\bel estrategia\b")
                if key == "activation_window_group_off_body":
                    self.assertNotIn("de bypass de bypass", value)
                self.assertNotRegex(value, r"estrategia de bypass (?:HTTP|HTTPS|UDP) está (?:deshabilitado|desactivado)")


    def test_all_locale_resource_contracts(self):
        argument = re.compile(r"(?<!%)%(?!%)(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z]")
        for directory in (ROOT / "app/src/main/res", ROOT / "app/src/simple/res",
                          ROOT / "core/service/src/main/res"):
            def entries(locale):
                result = {}
                for file in (directory / locale).glob("*.xml"):
                    for entry in ET.parse(file).getroot():
                        if entry.tag in ("string", "plurals", "string-array"):
                            name = entry.get("name")
                            self.assertNotIn(name, result)
                            result[name] = entry
                return result

            source = {key: entry for key, entry in entries("values").items()
                      if entry.get("translatable") != "false"}
            for locale in LOCALES:
                translated = entries(locale)
                self.assertTrue(source.keys() <= translated.keys())
                for key, original in source.items():
                    with self.subTest(directory=directory, locale=locale, key=key):
                        entry = translated[key]
                        self.assertEqual(original.tag, entry.tag)
                        if key.startswith("language_name_"):
                            self.assertEqual(text(original), text(entry))
                        originals = [original] if original.tag == "string" else list(original)
                        variants = [entry] if entry.tag == "string" else list(entry)
                        expected = sorted(argument.findall(text(originals[0])))
                        for variant in variants:
                            actual = sorted(argument.findall(text(variant)))
                            if entry.tag == "plurals" and locale == "values-ar" and variant.get("quantity") in ("zero", "one", "two"):
                                self.assertIn(actual, (expected, [arg for arg in expected if not arg.endswith("d")]))
                            else:
                                self.assertEqual(expected, actual)
                        if entry.tag == "plurals":
                            self.assertIn("other", {item.get("quantity") for item in entry})


if __name__ == "__main__":
    unittest.main()
