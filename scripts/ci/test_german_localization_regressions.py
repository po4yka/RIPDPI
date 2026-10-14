"""Regression checks for confirmed German copy and resource contracts."""

import re
import unittest

from test_localization_regressions import LOCALES, resources, text

ENGLISH_PROSE = (
    'data_transparency_no_browsing',
    'data_transparency_no_personal_data',
    'data_transparency_export_explicit',
    'data_transparency_export_control',
    'settings_diagnostics_export_history_body',
    'settings_diagnostics_retention_body',
    'diagnostics_save_logs_body',
    'diagnostics_pcap_card_body_idle',
    'diagnostics_share_archive_line',
    'config_chain_editor_label',
    'config_chain_summary_label',
    'config_chain_cli_override_note',
    'config_chain_block_valid',
    'config_chain_block_invalid',
    'config_chain_block_invalid_body',
    'config_chain_raw_show',
    'config_chain_raw_hide',
    'config_chain_move_up',
    'config_chain_move_down',
    'config_chain_remove_block',
    'config_relay_finalmask_off',
    'config_relay_finalmask_off_help',
    'config_relay_finalmask_header_help',
    'config_relay_finalmask_sudoku_help',
    'config_relay_finalmask_fragment_help',
    'config_relay_finalmask_noise_help',
    'diagnostics_device_gate_status_idle',
    'diagnostics_device_gate_status_running',
    'diagnostics_device_gate_status_incomplete',
    'diagnostics_device_gate_status_pass',
    'diagnostics_device_gate_status_fail',
    'title_local_bypass_config',
    'ripdpi_desync_method_setting',
    'dns_mode_plain',
    'update_channel_title',
    'update_channel_play_body',
    'update_channel_fdroid_body',
    'update_channel_github_body',
    'update_idle',
    'update_check_action',
    'update_checking',
    'update_install_action',
    'update_downloading_action',
    'update_no_update',
    'update_available_title_format',
    'update_size_format',
    'update_permission_required',
    'update_install_started',
    'update_error_format',
    'update_error_network',
    'update_error_invalid_metadata',
    'update_error_invalid_repository_asset',
    'update_error_invalid_package_name',
    'update_error_invalid_version',
    'update_error_unsupported_sdk',
    'update_error_checksum',
    'update_error_package_read',
    'update_error_installer_unavailable',
    'update_error_not_available',
    'update_error_unknown',
    'profile_share_loading_title',
    'profile_share_loading_body',
    'profile_share_qr_body',
    'profile_share_link_title',
    'profile_share_link_body',
    'profile_share_subscription_label',
    'profile_share_share_link_action',
    'profile_share_sheet_title',
    'profile_share_sheet_body',
    'profile_share_copy_sheet_action',
    'profile_share_share_sheet_action',
    'support_settings_title',
    'support_settings_default_title',
    'support_settings_default_reason',
    'support_settings_loading_title',
    'support_settings_loading_body',
    'support_settings_invalid_title',
    'support_settings_invalid_body',
    'support_settings_sensitive_title',
    'support_settings_sensitive_body',
    'support_settings_changes_title',
    'support_settings_change_summary',
    'support_settings_no_changes',
    'support_settings_apply_action',
    'support_settings_restart_ask',
    'support_settings_restart_required',
    'support_settings_restart_never',
)

SEMANTIC_KEYS = (
    'diagnostics_share_redaction_line',
    'detection_permission_rationale',
    'detection_onboarding_body',
    'detection_permission_settings',
    'diagnostics_pcap_card_body_recording',
    'diagnostics_pcap_disclosure_body',
    'data_transparency_export_redaction',
    'home_diagnostics_pcap_helper',
    'diagnostics_rkn_group_control',
    'diagnostics_rkn_complete',
    'diagnostics_rkn_running',
    'diagnostics_cidr_whitelist_summary_detected',
    'fake_order_card_seqmode_body',
    'quic_initial_mode_body',
    'home_remediation_no_hint_step_review_evidence',
    'diagnostics_audit_short_circuit_title',
    'pause_clock_failed',
    'ripdpi_protocols_hint',
    'diagnostics_field_metered',
    'diagnostics_field_network_metered',
)

LATENT_KEYS = (
    'support_settings_applied_action',
    'home_mode_card_remote_relay_disabled_hint',
    'onboarding_privacy_title',
    'home_diagnostics_detection_permission_required',
    'share_link_preview_privacy_message',
)

REDACTION_KEYS = (
    "diagnostics_share_redaction_line", "detection_permission_rationale",
    "detection_onboarding_body", "detection_permission_settings",
    "diagnostics_pcap_card_body_recording", "diagnostics_pcap_disclosure_body",
    "data_transparency_export_redaction", "home_diagnostics_pcap_helper",
    "home_diagnostics_detection_permission_required",
)
CONTROL_KEYS = (
    "diagnostics_rkn_group_control", "diagnostics_rkn_complete",
    "diagnostics_rkn_running", "diagnostics_cidr_whitelist_summary_detected",
)
FORMAT_ARGUMENT = re.compile(r"(?<!%)%(?!%)(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z]")


class GermanLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.de = resources("app", "values-de")
        cls.en = resources("app", "values")

    def test_audited_prose_has_no_english_fallback(self):
        self.assertEqual((87, 20, 5), (len(ENGLISH_PROSE), len(SEMANTIC_KEYS), len(LATENT_KEYS)))
        audited = ENGLISH_PROSE + SEMANTIC_KEYS + LATENT_KEYS
        self.assertEqual(112, len(set(audited)))
        self.assertTrue(set(audited) <= self.de.keys())
        keys = ENGLISH_PROSE + LATENT_KEYS[:3]
        for key in keys:
            with self.subTest(key=key):
                self.assertTrue(text(self.de[key]))
                self.assertNotEqual(text(self.en[key]).casefold(), text(self.de[key]).casefold())

    def test_redaction_and_decoding_do_not_claim_editing_or_decryption(self):
        for key in REDACTION_KEYS:
            with self.subTest(key=key):
                value = text(self.de[key]).casefold()
                self.assertNotIn("redig", value)
                self.assertRegex(value, r"unkenntlich|schw[aä]rz|entfern|ausblend")
        privacy = text(self.de["share_link_preview_privacy_message"]).casefold()
        self.assertNotIn("entschlüss", privacy)
        self.assertRegex(privacy, r"decod|dekod")

    def test_diagnostic_and_network_semantics(self):
        for key in CONTROL_KEYS:
            with self.subTest(key=key):
                value = text(self.de[key]).casefold()
                self.assertNotIn("steuer", value)
                self.assertIn("kontroll", value)
        self.assertNotIn("behörd", text(self.de["home_remediation_no_hint_step_review_evidence"]).casefold())
        self.assertRegex(text(self.de["home_remediation_no_hint_step_review_evidence"]).casefold(),
                         r"ziel|host|url|autorität")
        self.assertNotIn("kurzgeschlossen", text(self.de["diagnostics_audit_short_circuit_title"]).casefold())
        self.assertRegex(text(self.de["diagnostics_audit_short_circuit_title"]).casefold(),
                         r"vorzeitig|abgebroch|beendet")
        self.assertNotIn("Strategieen", text(self.de["ripdpi_protocols_hint"]))
        for key in ("diagnostics_field_metered", "diagnostics_field_network_metered"):
            self.assertNotIn("kostenpflicht", text(self.de[key]).casefold())
            self.assertIn("getaktet", text(self.de[key]).casefold())

    def test_instructions_name_the_visible_options_and_recovery_button(self):
        instruction = text(self.de["fake_order_card_seqmode_body"])
        for label in ("ripdpi_fake_seq_mode_duplicate", "ripdpi_fake_seq_mode_sequential"):
            self.assertIn(text(self.de[label]), instruction)
        instruction = text(self.de["quic_initial_mode_body"])
        for label in ("diagnostics_tool_state_disabled", "settings_option_route_by_sni",
                      "settings_option_route_and_cache"):
            self.assertIn(text(self.de[label]), instruction)
        self.assertIn(text(self.de["pause_resume_now"]), text(self.de["pause_clock_failed"]))

    def test_safety_warning_preserves_scope_and_recovery(self):
        warning = text(self.de["support_settings_sensitive_body"]).casefold()
        for term in ("verkehr", "zugang", "diagnose", "privileg", "vertrau"):
            self.assertIn(term, warning)
        pcap = text(self.de["diagnostics_pcap_card_body_idle"]).casefold()
        for term in ("roh", "24", "3", "automatisch"):
            self.assertIn(term, pcap)
        self.assertRegex(pcap, r"keine|nicht")
        retention = text(self.de["settings_diagnostics_retention_body"]).casefold()
        self.assertRegex(retention, r"app-speicher|app.?speicher")
        self.assertRegex(retention, r"ander|anderswo")
        self.assertIn("lösch", retention)

    def test_every_locale_keeps_resource_and_format_contracts(self):
        for locale in LOCALES:
            catalog = resources("app", locale)
            for key, original in self.en.items():
                if original.get("translatable") == "false":
                    continue
                with self.subTest(locale=locale, key=key):
                    self.assertIn(key, catalog)
                    localized = catalog[key]
                    self.assertEqual(original.tag, localized.tag)
                    if original.tag == "string":
                        self.assertEqual(sorted(FORMAT_ARGUMENT.findall(text(original))),
                                         sorted(FORMAT_ARGUMENT.findall(text(localized))))
                    if key.startswith("language_name_"):
                        self.assertEqual(text(original), text(localized))


if __name__ == "__main__":
    unittest.main()
