"""Regressions for the confirmed Hindi copy and settings resource contracts."""

import re
import unittest
import xml.etree.ElementTree as ET

from check_localized_settings_options import EXACT_LABELS, check_options, resolve
from test_localization_regressions import LOCALES, ROOT, resources, text


HISTORY_REFERENCES = (
    "diagnostics_remediation_partial_step_open_history",
    "diagnostics_remediation_review_step_open_history",
    "diagnostics_remediation_open_history_action",
    "diagnostics_remediation_no_hint_step_open_history",
    "home_remediation_review_step_open_history",
)
EDITOR_REFERENCES = (
    "diagnostics_remediation_browser_relay_step_open_mode_editor",
    "diagnostics_remediation_quic_relay_step_open_mode_editor",
    "diagnostics_remediation_open_mode_editor_action",
    "home_remediation_browser_relay_summary",
    "home_remediation_browser_relay_step_open_mode_editor",
    "home_remediation_quic_relay_summary",
    "home_remediation_quic_relay_step_open_mode_editor",
)
DIAGNOSTIC_REFERENCES = (
    "home_diagnostics_no_actionable_result",
    "home_remediation_stale_step_open_diagnostics",
    "home_remediation_no_actionable_step_open_diagnostics",
    "home_remediation_open_diagnostics_action",
    "home_remediation_owned_stack_summary",
    "home_remediation_owned_stack_step_open_diagnostics",
    "home_remediation_no_hint_step_open_diagnostics",
    "home_diagnostics_open_diagnostics_action",
    "config_local_bypass_retest_strategies",
)
ADVANCED_REFERENCES = (
    "home_control_plane_health_body", "home_control_plane_health_action",
    "diagnostics_scan_workflow_blocked_command_line_body_format",
)
ENGLISH_LABELS = (
    "diagnostics_family_network_full", "home_diagnostics_dns_section",
    "diagnostics_device_gate_status_idle", "diagnostics_device_gate_status_running",
    "diagnostics_device_gate_status_incomplete", "diagnostics_device_gate_status_pass",
    "diagnostics_device_gate_status_fail", "diagnostics_replay_probe_summary",
    "diagnostics_replay_step_dns_resolve", "diagnostics_replay_step_tls_handshake",
    "diagnostics_resolver_field_bootstrap", "diagnostics_resolver_field_endpoint",
    "diagnostics_resolver_field_protocol", "diagnostics_resolver_field_resolver",
    "diagnostics_resolver_field_trigger", "diagnostics_tool_zstd_codec_label",
    "home_connection_actuator_relay", "home_connection_stage_handshake",
    "home_connection_stage_tunnel",
)


EXPECTED_VALUES = {'themes_entries': ('system', 'light', 'dark'),
 'ripdpi_hosts_modes_entries': ('disable', 'blacklist', 'whitelist'),
 'warp_route_modes_entries': ('off', 'rules'),
 'warp_endpoint_selection_modes_entries': ('automatic', 'manual'),
 'warp_amnezia_presets_entries': ('off',
                                  'random',
                                  'quic_imitation',
                                  'tls_imitation',
                                  'dns_imitation',
                                  'custom'),
 'quic_initial_modes_entries': ('disabled', 'route', 'route_and_cache'),
 'tls_fingerprint_profiles_entries': ('chrome_stable',
                                      'chrome_desktop_stable',
                                      'firefox_stable',
                                      'firefox_ech_stable',
                                      'safari_stable',
                                      'edge_stable'),
 'entropy_modes_entries': ('disabled', 'popcount', 'shannon', 'combined'),
 'fake_tls_base_modes_entries': ('default', 'original'),
 'http_fake_profiles_entries': ('compat_default', 'iana_get', 'cloudflare_get'),
 'fake_tls_sni_modes_entries': ('fixed', 'randomized'),
 'ip_id_modes_entries': ('', 'seq', 'seqgroup', 'rnd', 'zero'),
 'fake_order_modes_entries': ('0', '1', '2', '3'),
 'tls_fake_profiles_entries': ('compat_default',
                               'iana_firefox',
                               'google_chrome',
                               'vk_chrome',
                               'sberbank_chrome',
                               'rutracker_kyber',
                               'bigsize_iana'),
 'udp_fake_profiles_entries': ('compat_default',
                               'zero_256',
                               'zero_512',
                               'dns_query',
                               'stun_binding',
                               'wireguard_initiation',
                               'dht_get_peers')}

class HindiLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.hi = resources("app", "values-hi")
        cls.en = resources("app", "values")

    def test_privacy_state_and_network_meanings(self):
        for key in ("backup_share_redacted_action", "backup_share_redacted_subject",
                    "backup_share_reminder_title", "diagnostics_device_gate_share",
                    "share_link_preview_row_asn_redacted", "share_link_preview_privacy_message"):
            with self.subTest(key=key):
                value = text(self.hi[key])
                self.assertNotIn("संपादित", value)
                self.assertNotIn("संपादन", value)
                self.assertRegex(value, "संवेदनशील|छिप|हट")
        self.assertIn("सत्यापित नहीं", text(self.hi["remembered_networks_validation_none"]))
        self.assertNotIn("अमान्य", text(self.hi["remembered_networks_validation_none"]))
        self.assertNotIn("चोरी", text(self.hi["detection_resistance_quic_low_port_body"]))
        self.assertNotIn("श्रेय", text(self.hi["diagnostics_region_suite_message"]))
        self.assertNotIn("प्राधिकरण", text(self.hi["home_remediation_no_hint_step_review_evidence"]))
        self.assertIn("सबनेट", text(self.hi["diagnostics_ipv4_whitelist_check_cached"]))
        self.assertIn(text(self.hi["pause_resume_now"]), text(self.hi["pause_clock_failed"]))

    def test_recovery_references_match_visible_labels(self):
        for keys, label in ((HISTORY_REFERENCES, "history_title"),
                            (EDITOR_REFERENCES, "title_mode_editor"),
                            (DIAGNOSTIC_REFERENCES, "diagnostics_title"),
                            (ADVANCED_REFERENCES, "title_advanced_settings")):
            for key in keys:
                with self.subTest(key=key):
                    self.assertIn(text(self.hi[label]), text(self.hi[key]))
        for key in ("diagnostics_pt_privacy_disabled", "diagnostics_rkn_self_info_privacy_suppressed"):
            self.assertIn(text(self.hi["detection_privacy_mode"]), text(self.hi[key]))
        self.assertIn(text(self.hi["home"]), text(self.hi["home_remediation_command_line_step_retry"]))
        self.assertIn(text(self.hi["home_diagnostics_run_analysis"]),
                      text(self.hi["home_remediation_command_line_step_retry"]))

    def test_profile_editor_references_match_in_every_locale(self):
        for locale in LOCALES:
            catalog = resources("app", locale)
            title = text(catalog["title_mode_editor"]).casefold()
            for key in EDITOR_REFERENCES:
                with self.subTest(locale=locale, key=key):
                    self.assertIn(title, text(catalog[key]).casefold())

    def test_audited_display_prose_is_hindi(self):
        for key in ENGLISH_LABELS:
            with self.subTest(key=key):
                value = text(self.hi[key])
                self.assertNotEqual(text(self.en[key]), value)
                self.assertRegex(value, r"[\u0900-\u097f]")
        for key in ("home_remediation_browser_relay_title",
                    "home_remediation_browser_relay_summary",
                    "home_remediation_browser_relay_step_choose_preset"):
            self.assertNotIn("browser-camouflage", text(self.hi[key]))
        self.assertEqual("RIPDPI ब्राउज़र", text(self.hi["title_owned_stack_browser"]))
        simple = {e.get("name"): text(e) for e in ET.parse(
            ROOT / "app/src/simple/res/values-hi/strings.xml").getroot()}
        self.assertIn(simple["simple_title"], simple["qs_tile_label_connected"])

    def test_display_references_reject_missing_empty_and_cyclic_strings(self):
        for reference, catalog in (("@string/missing", {}),
                                   ("@string/blank", {"blank": ""}),
                                   ("@string/a", {"a": "@string/b", "b": "@string/a"})):
            with self.subTest(reference=reference):
                with self.assertRaises(ValueError):
                    resolve(reference, catalog)

    def test_settings_label_references_and_exact_values(self):
        self.assertEqual([], check_options(ROOT / "app/src/main/res"))
        arrays = {entry.get("name"): entry for entry in ET.parse(
            ROOT / "app/src/main/res/values/arrays.xml").getroot()}
        hi_catalog = {key: text(entry) for key, entry in self.hi.items() if entry.tag == "string"}
        for name, values in EXPECTED_VALUES.items():
            with self.subTest(array=name):
                self.assertEqual(values, tuple(text(item) for item in arrays[name]))
        translated = 0
        exact = 0
        for name, array in arrays.items():
            if name.endswith("_entries"):
                continue
            for item in array:
                if item.get("name") in EXACT_LABELS.get(name, {}):
                    exact += 1
                    continue
                translated += 1
                self.assertRegex(resolve(text(item), hi_catalog), r"[\u0900-\u097f]")
        self.assertEqual((46, 13), (translated, exact))


if __name__ == "__main__":
    unittest.main()
