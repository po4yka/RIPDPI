"""Regression checks for audited French copy and shared resource contracts."""

import re
import unittest

from test_localization_regressions import LOCALES, resources, text

ENGLISH_PROSE = (
    'data_transparency_no_browsing',
    'data_transparency_export_control',
    'data_transparency_export_explicit',
    'data_transparency_no_personal_data',
    'settings_diagnostics_export_history_body',
    'settings_diagnostics_retention_body',
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
    'support_settings_applied_action',
    'support_settings_restart_ask',
    'support_settings_restart_required',
    'support_settings_restart_never',
    'updates_section_title',
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
    'update_changelog_title',
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
    'ripdpi_desync_method_setting',
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
    'home_mode_card_remote_relay_disabled_hint',
    'title_local_bypass_config',
    'config_relay_finalmask_off',
    'config_relay_finalmask_off_help',
    'config_relay_finalmask_header_help',
    'config_relay_finalmask_sudoku_help',
    'config_relay_finalmask_fragment_help',
    'config_relay_finalmask_noise_help',
    'diagnostics_pcap_card_body_idle',
    'diagnostics_save_logs_body',
    'diagnostics_share_archive_line',
    'diagnostics_device_gate_status_idle',
    'diagnostics_device_gate_status_running',
    'diagnostics_device_gate_status_incomplete',
    'diagnostics_device_gate_status_pass',
    'diagnostics_device_gate_status_fail',
)

SEMANTIC_KEYS = (
    'quic_initial_mode_body',
    'fake_order_card_seqmode_body',
    'config_preset_proxy_title',
    'config_preset_proxy_body',
    'config_relay_credentials_body',
    'config_vpn_credentials_required',
    'warp_profile_id_body',
    'ripdpi_protocols_hint',
    'subscription_failover_event_switched_to_backup',
    'owned_stack_browser_banner_title',
    'owned_stack_browser_trace_native_platform_unavailable',
    'owned_stack_browser_banner_fallback',
    'owned_stack_browser_trace_native_ech_missing',
    'owned_stack_browser_banner_platform',
    'owned_stack_browser_trace_native_platform_failure',
    'owned_stack_browser_error_title',
    'home_remediation_owned_stack_summary',
    'detection_resistance_tls_fingerprint_body',
    'detection_resistance_quic_migrate_body',
    'activation_window_scope_cli',
    'ripdpi_http_parser_http_off_body',
    'ripdpi_http_parser_saved_body',
    'adaptive_fallback_scope_idle',
    'activation_window_group_disabled_body',
    'detection_permission_rationale',
    'quic_fake_profile_udp_disabled_body',
    'ripdpi_fake_approx_protocols_off_body',
    'config_relay_masque_cloudflare_geohash_helper',
    'detection_onboarding_body',
    'detection_permission_settings',
    'home_diagnostics_detection_permission_required',
    'quic_fake_profile_scope_udp_disabled',
    'quic_fake_profile_udp_disabled_title',
    'ripdpi_fake_tls_https_disabled_body',
    'ripdpi_tls_prelude_https_disabled_body',
    'ripdpi_tls_prelude_scope_https_disabled',
    'activation_window_group_off_body',
    'fake_payload_library_protocols_disabled_body',
    'ripdpi_fake_tls_https_disabled_title',
    'ripdpi_tls_prelude_https_disabled_title',
    'tls_fake_profile_scope_off',
    'adaptive_split_saved_body',
    'share_link_preview_privacy_message',
    'diagnostics_status_suppressed',
    'diagnostics_remediation_quic_relay_summary',
    'pause_clock_failed',
    'diagnostics_phase_rate',
    'diagnostics_remediation_no_hint_step_retry',
)

LATENT_KEYS = (
    'home_diagnostics_detection_permission_required',
    'home_mode_card_remote_relay_disabled_hint',
    'support_settings_applied_action',
    'share_link_preview_privacy_message',
)


class FrenchLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fr = resources("app", "values-fr")
        cls.en = resources("app", "values")

    def test_audited_prose_has_no_english_fallback(self):
        self.assertEqual((90, 48, 4), (len(ENGLISH_PROSE), len(SEMANTIC_KEYS), len(LATENT_KEYS)))
        self.assertEqual(138, len(set(ENGLISH_PROSE + SEMANTIC_KEYS)))
        self.assertTrue(set(LATENT_KEYS) <= set(ENGLISH_PROSE + SEMANTIC_KEYS))
        for key in ENGLISH_PROSE:
            with self.subTest(key=key, production_reachability=key not in LATENT_KEYS):
                self.assertTrue(text(self.fr[key]))
                self.assertNotEqual(text(self.en[key]).casefold(), text(self.fr[key]).casefold())

    def test_temporary_suppression_does_not_claim_deletion(self):
        self.assertEqual("Suspendu", text(self.fr["diagnostics_status_suppressed"]))

    def test_failover_uses_a_server_not_a_saved_copy(self):
        value = text(self.fr["subscription_failover_event_switched_to_backup"]).casefold()
        self.assertIn("serveur de secours", value)
        self.assertNotIn("sauvegarde", value)

    def test_quic_recommendation_refers_to_transport_not_load(self):
        value = text(self.fr["diagnostics_remediation_quic_relay_summary"])
        self.assertIn("dominante QUIC", value)
        self.assertNotIn("forte charge", value)
        self.assertIn("UDP/QUIC", value)
        self.assertIn("transparente", value)

    def test_throughput_phase_names_data_rate(self):
        self.assertEqual("Débit", text(self.fr["diagnostics_phase_rate"]))

    def test_instructions_name_the_visible_options_and_recovery_action(self):
        pairs = (
            ("quic_initial_mode_body", "diagnostics_tool_state_disabled"),
            ("quic_initial_mode_body", "settings_option_route_by_sni"),
            ("quic_initial_mode_body", "settings_option_route_and_cache"),
            ("fake_order_card_seqmode_body", "ripdpi_fake_seq_mode_duplicate"),
            ("fake_order_card_seqmode_body", "ripdpi_fake_seq_mode_sequential"),
            ("pause_clock_failed", "pause_resume_now"),
        )
        for instruction, label in pairs:
            with self.subTest(instruction=instruction, label=label):
                self.assertIn(text(self.fr[label]), text(self.fr[instruction]))

    def test_local_mode_and_credentials_use_french_labels(self):
        for key in ("config_preset_proxy_title", "config_preset_proxy_body"):
            with self.subTest(key=key):
                self.assertIn("contournement local", text(self.fr[key]))
                self.assertNotIn("Local bypass", text(self.fr[key]))
        for key in ("config_relay_credentials_body", "config_vpn_credentials_required", "warp_profile_id_body"):
            with self.subTest(key=key):
                self.assertIn("identifiants", text(self.fr[key]).casefold())
                self.assertNotIn("credentials", text(self.fr[key]).casefold())

    def test_owned_transport_names_app_control_consistently(self):
        keys = [key for key in SEMANTIC_KEYS if key.startswith("owned_stack_")]
        keys += ["home_remediation_owned_stack_summary", "detection_resistance_tls_fingerprint_body",
                 "detection_resistance_quic_migrate_body"]
        self.assertEqual(10, len(keys))
        for key in keys:
            with self.subTest(key=key):
                self.assertRegex(text(self.fr[key]), r"géré(?:e|s)? par RIPDPI")
                self.assertNotRegex(text(self.fr[key]), r"owned|détenue|propriétaire")

    def test_redaction_does_not_mean_writing(self):
        value = text(self.fr["share_link_preview_privacy_message"]).casefold()
        self.assertIn("masquage", value)
        self.assertNotIn("rédaction", value)
        self.assertIn("hachés", value)
        self.assertIn("décodé", value)

    def test_strategy_and_recovery_grammar_preserves_feminine_agreement(self):
        keys = [key for key in SEMANTIC_KEYS if key.startswith(("activation_window_", "adaptive_", "ripdpi_", "quic_fake_", "tls_fake_"))
                and key != "ripdpi_protocols_hint"]
        keys += ["fake_payload_library_protocols_disabled_body", "diagnostics_remediation_no_hint_step_retry"]
        for key in keys:
            with self.subTest(key=key):
                self.assertNotRegex(text(self.fr[key]), r"[Ll]e stratégie|au stratégie|du stratégie|Les stratégie |de contournement de contournement|un autre exécution")
                self.assertNotRegex(text(self.fr[key]), r"stratégie[^.]*n’est pas réactivé(?:[ .,]|$)")
        for key in ("detection_permission_rationale", "detection_onboarding_body", "detection_permission_settings", "home_diagnostics_detection_permission_required"):
            with self.subTest(key=key):
                self.assertIn("l’identité", text(self.fr[key]))
                self.assertNotIn("l identité", text(self.fr[key]))
        self.assertIn("Il n’est pas utilisé", text(self.fr["config_relay_masque_cloudflare_geohash_helper"]))

    def test_protocol_hint_separates_tcp_filter_and_udp_configuration(self):
        source = text(self.en["ripdpi_protocols_hint"])
        self.assertIn("without a protocol filter", source)
        self.assertIn("UDP bypass is configured separately", source)
        self.assertIn("sans filtre de protocole", text(self.fr["ripdpi_protocols_hint"]))
        self.assertIn("séparément", text(self.fr["ripdpi_protocols_hint"]))
        for locale in LOCALES:
            with self.subTest(locale=locale):
                value = text(resources("app", locale)["ripdpi_protocols_hint"])
                for token in ("HTTP", "HTTPS", "TCP", "UDP"):
                    self.assertIn(token, value)

    def test_shared_metric_and_debug_templates_exist_in_every_locale(self):
        keys = ("diagnostics_rtt_band_lt50_format", "diagnostics_rtt_band_range_format",
                "diagnostics_rtt_band_500_plus_format", "diagnostics_performance_header_format",
                "diagnostics_performance_slowest_format", "diagnostics_performance_input_format")
        for locale in LOCALES:
            catalog = resources("app", locale)
            for key in keys:
                with self.subTest(locale=locale, key=key):
                    self.assertIn(key, catalog)
                    self.assertTrue(text(catalog[key]))
        self.assertIn("Débogage", text(self.fr[keys[3]]))
        self.assertIn("la plus lente", text(self.fr[keys[4]]))
        self.assertIn("télémétrie %1$d", text(self.fr[keys[5]]))

    def test_privacy_warnings_keep_consent_retention_and_sensitive_scope(self):
        warning = text(self.fr["support_settings_sensitive_body"]).casefold()
        for term in ("trafic", "identifiants", "diagnostics", "privilégiées", "confiance"):
            self.assertIn(term, warning)
        pcap = text(self.fr["diagnostics_pcap_card_body_idle"]).casefold()
        for term in ("octets bruts", "local", "24 h", "3 fichiers", "avant le démarrage", "aucun partage automatique"):
            self.assertIn(term, pcap)
        self.assertIn("demande explicite", text(self.fr["data_transparency_export_explicit"]))
        self.assertIn("ailleurs", text(self.fr["settings_diagnostics_retention_body"]))
        self.assertIn("votre accord", text(self.fr["update_channel_github_body"]))
        self.assertIn("emplacement", text(self.fr["diagnostics_save_logs_body"]))
        for key in ("detection_permission_rationale", "detection_onboarding_body", "detection_permission_settings"):
            value = text(self.fr[key])
            self.assertIn("identifiants bruts ne sont pas stockés", value)
            self.assertIn("masquent", value)

    def test_every_locale_keeps_resource_and_format_contracts(self):
        argument = re.compile(r"(?<!%)%(?!%)(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z]")
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
                        self.assertEqual(sorted(argument.findall(text(original))),
                                         sorted(argument.findall(text(localized))))
                    if key.startswith("language_name_"):
                        self.assertEqual(text(original), text(localized))


if __name__ == "__main__":
    unittest.main()
