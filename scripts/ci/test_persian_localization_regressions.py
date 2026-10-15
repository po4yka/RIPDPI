"""Regression checks for confirmed Persian meanings and resource contracts."""

import re
import unittest
import xml.etree.ElementTree as ET

from test_localization_regressions import LOCALES, ROOT, resources, text


AUDITED_KEYS = (
    'config_chain_summary_label',
    'config_chain_cli_override_note',
    'config_chain_editor_label',
    'config_chain_block_valid',
    'config_chain_block_invalid',
    'config_chain_block_invalid_body',
    'config_chain_raw_show',
    'config_chain_raw_hide',
    'config_chain_move_up',
    'config_chain_move_down',
    'config_chain_remove_block',
    'ripdpi_desync_method_setting',
    'config_relay_finalmask_off',
    'config_relay_finalmask_off_help',
    'config_relay_finalmask_header_help',
    'config_relay_finalmask_sudoku_help',
    'config_relay_finalmask_fragment_help',
    'title_local_bypass_config',
    'config_preset_proxy_title',
    'config_preset_proxy_body',
    'ripdpi_proxy',
    'settings_exclude_russian_apps_title',
    'settings_full_tunnel_helper',
    'strategy_config_saved_restarting_body',
    'adaptive_fake_ttl_restart_body',
    'adaptive_fake_ttl_learning_runtime',
    'ripdpi_fake_tls_scope_restart',
    'ripdpi_http_parser_restart_title',
    'root_mode_strategies_disabled_body',
    'host_autolearn_store_body',
    'host_pack_badge_offline_snapshot',
    'host_pack_degraded_status_body_incompatible',
    'host_pack_invalid_snapshot_status_title',
    'host_pack_download_failed_status_body',
    'host_pack_snapshot_built_label',
    'strategy_pack_bundled_status_body',
    'strategy_pack_degraded_status_title',
    'strategy_pack_downloaded_status_body',
    'data_transparency_no_browsing',
    'data_transparency_no_personal_data',
    'data_transparency_export_explicit',
    'data_transparency_export_control',
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
    'notice_fake_payload_reset_next_start_message',
    'confirm_reset_http_parser_evasions_body',
    'config_chain_step_fakerst_explanation',
    'routing_protection_anti_correlation_description',
    'routing_protection_full_tunnel_description',
    'pause_clock_failed',
    'vpn_dev_pcap_consent_warning',
    'vpn_replay_rec_handshake_alert',
    'profile_share_warning_body',
    'diagnostics_save_logs_body',
    'diagnostics_pcap_card_body_idle',
    'settings_diagnostics_retention_body',
    'settings_diagnostics_export_history_body',
    'diagnostics_share_archive_line',
    'diagnostics_device_gate_status_idle',
    'diagnostics_device_gate_status_running',
    'diagnostics_device_gate_status_incomplete',
    'diagnostics_device_gate_status_pass',
    'diagnostics_device_gate_status_fail',
    'diagnostics_error_dns_failure',
    'home_diagnostics_dns_resolver_unknown',
    'diagnostics_live_telemetry_error_format',
    'diagnostics_compression_bytes_format',
    'diagnostics_ipv4_whitelist_cache',
)


class PersianLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fa = resources("app", "values-fa")
        cls.en = resources("app", "values")

    def value(self, key):
        return text(self.fa[key])

    def test_exact_audited_scope_has_persian_copy(self):
        self.assertEqual(121, len(set(AUDITED_KEYS)))
        for key in AUDITED_KEYS:
            with self.subTest(key=key):
                self.assertRegex(self.value(key), r"[\u0600-\u06ff]")
                self.assertNotEqual(text(self.en[key]), self.value(key))

    def test_exclusion_is_not_deletion(self):
        for key in ("settings_exclude_russian_apps_title", "settings_full_tunnel_helper",
                    "routing_protection_anti_correlation_description", "routing_protection_full_tunnel_description"):
            with self.subTest(key=key):
                self.assertRegex(self.value(key), "مستثنا|استثنا")
                self.assertNotIn("حذف", self.value(key))
        self.assertIn("TRANSPORT_VPN", self.value("routing_protection_anti_correlation_description"))

    def test_restart_does_not_reset_saved_settings(self):
        for key in ("strategy_config_saved_restarting_body", "adaptive_fake_ttl_restart_body",
                    "adaptive_fake_ttl_learning_runtime", "ripdpi_fake_tls_scope_restart",
                    "ripdpi_http_parser_restart_title", "confirm_reset_http_parser_evasions_body"):
            with self.subTest(key=key):
                self.assertIn("راه‌اندازی مجدد", self.value(key))
                self.assertNotIn("بازنشانی", self.value(key))
        notice = self.value("notice_fake_payload_reset_next_start_message")
        self.assertIn("دوباره راه‌اندازی", notice)
        for token in ("HTTP", "TLS", "UDP"):
            self.assertIn(token, notice)

    def test_storage_middlebox_and_non_root_meanings(self):
        self.assertIn("مخزن", self.value("host_autolearn_store_body"))
        self.assertNotIn("فروشگاه", self.value("host_autolearn_store_body"))
        for key in ("host_pack_badge_offline_snapshot", "host_pack_degraded_status_body_incompatible",
                    "host_pack_invalid_snapshot_status_title", "host_pack_download_failed_status_body",
                    "host_pack_snapshot_built_label", "strategy_pack_bundled_status_body",
                    "strategy_pack_degraded_status_title", "strategy_pack_downloaded_status_body"):
            with self.subTest(key=key):
                self.assertIn("نسخه", self.value(key))
                self.assertNotIn("عکس", self.value(key))
        self.assertIn("تجهیزات میانی شبکه", self.value("config_chain_step_fakerst_explanation"))
        self.assertNotIn("میان‌افزار", self.value("config_chain_step_fakerst_explanation"))
        self.assertIn("بدون ایجاد اختلال", self.value("root_mode_strategies_disabled_body"))
        self.assertNotIn("از کار می‌افتند", self.value("root_mode_strategies_disabled_body"))

    def test_sensitive_data_consent_retention_and_scope(self):
        warning = self.value("support_settings_sensitive_body")
        for term in ("ترافیک", "اعتبارنامه", "تشخیصی", "امتیاز ویژه", "اعتماد"):
            self.assertIn(term, warning)
        self.assertIn("اعتبارنامه", self.value("profile_share_warning_body"))
        self.assertNotIn("گواهی‌نامه", self.value("profile_share_warning_body"))
        self.assertIn("درخواست صریح", self.value("data_transparency_export_explicit"))
        pcap = self.value("diagnostics_pcap_card_body_idle")
        for term in ("خام", "محلی", "۲۴", "۳", "شروع", "خودکار"):
            self.assertIn(term, pcap)
        consent = self.value("vpn_dev_pcap_consent_warning")
        for term in ("TLS SNI", "IP", "۶۴ MiB", "خصوصی برنامه", "صریحاً"):
            self.assertIn(term, consent)
        self.assertIn("جای دیگری", self.value("settings_diagnostics_retention_body"))
        self.assertIn("انتخاب محل ذخیره", self.value("diagnostics_save_logs_body"))
        self.assertIn("رضایت کاربر", self.value("update_channel_github_body"))

    def test_recovery_action_and_diagnostic_terms(self):
        self.assertIn(self.value("pause_resume_now"), self.value("pause_clock_failed"))
        self.assertIn("دست‌دهی TLS", self.value("vpn_replay_rec_handshake_alert"))
        self.assertNotIn("دست‌تکانی", self.value("vpn_replay_rec_handshake_alert"))
        self.assertIn("حل نام DNS", self.value("diagnostics_error_dns_failure"))
        self.assertIn("ارزیابی DNS", self.value("home_diagnostics_dns_resolver_unknown"))
        self.assertIn("خواندن دوره‌ای", self.value("diagnostics_live_telemetry_error_format"))
        self.assertIn("بازکردن فشرده‌سازی", self.value("diagnostics_compression_bytes_format"))
        self.assertNotIn("رمزگشایی", self.value("diagnostics_compression_bytes_format"))
        self.assertIn("ذخیرهٔ زیرشبکه", self.value("diagnostics_ipv4_whitelist_cache"))

    def test_shared_presentation_has_translations_and_count_forms(self):
        catalogs = {}
        filename = "strings_persian_audit_localization.xml"
        for locale in LOCALES:
            path = ROOT / "app/src/main/res" / locale / filename
            self.assertTrue(path.is_file(), locale)
            catalogs[locale] = {entry.get("name"): entry for entry in ET.parse(path).getroot()}
        source = catalogs["values"]
        self.assertEqual(198, len(source))
        self.assertEqual(4, sum(entry.tag == "plurals" for entry in source.values()))
        # Product tokens and valid cognates can be identical to English.
        canonical = {"home_generated_warp_amnezia"}
        cognates = {
            "values-es": {"home_generated_bytes"},
            "values-de": {"home_generated_epsilon", "home_generated_check_port", "diagnostics_strategy_family_parser"},
            "values-fr": {"home_generated_epsilon", "home_generated_progress_service", "home_generated_check_port"},
            "values-pt-rBR": {"home_generated_bytes"},
        }
        for locale, catalog in catalogs.items():
            with self.subTest(locale=locale):
                self.assertEqual(set(source), set(catalog))
            for key, entry in catalog.items():
                with self.subTest(locale=locale, key=key):
                    self.assertTrue(text(entry))
                    if locale != "values" and key not in canonical | cognates.get(locale, set()):
                        self.assertNotEqual(text(source[key]), text(entry))
                    if locale == "values-fa" and key not in canonical:
                        self.assertRegex(text(entry), r"[\u0600-\u06ff]")
        counts = ("stages_failed", "coverage_gap", "host_count", "detection_count")
        for name in counts:
            key = "home_generated_" + name
            self.assertEqual("plurals", source[key].tag)
            self.assertEqual({"zero", "one", "two", "few", "many", "other"},
                             {item.get("quantity") for item in catalogs["values-ar"][key]})
        self.assertIn("تأییدنشده", text(catalogs["values-fa"]["home_generated_vpn_captured"]))
        self.assertIn("مشخص نیست", text(catalogs["values-fa"]["home_generated_reality_rationale"]))
        self.assertIn("تقریبی", text(catalogs["values-fa"]["diagnostics_strategy_note_approximate"]))

    def test_all_locales_preserve_resource_format_and_plural_contracts(self):
        argument = re.compile(r"(?<!%)%(?!%)(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z]")
        required = {"values": {"one", "other"}, "values-ru": {"one", "few", "many", "other"},
                    "values-ar": {"zero", "one", "two", "few", "many", "other"},
                    "values-zh-rCN": {"other"}}
        for module in ("app", "core/service"):
            source = resources(module, "values")
            for locale in LOCALES:
                translated = resources(module, locale)
                for key, original in source.items():
                    if original.get("translatable") == "false":
                        continue
                    with self.subTest(module=module, locale=locale, key=key):
                        self.assertIn(key, translated)
                        entry = translated[key]
                        self.assertEqual(original.tag, entry.tag)
                        if original.tag == "string":
                            self.assertEqual(sorted(argument.findall(text(original))), sorted(argument.findall(text(entry))))
                        elif original.tag == "plurals":
                            self.assertTrue(required.get(locale, {"one", "other"}) <= {v.get("quantity") for v in entry})
                            expected = sorted(argument.findall(text(original.find("item[@quantity='other']"))))
                            for variant in entry:
                                actual = sorted(argument.findall(text(variant)))
                                if locale == "values-ar" and variant.get("quantity") in ("zero", "one", "two"):
                                    self.assertIn(actual, (expected, [a for a in expected if not a.endswith("d")]))
                                else:
                                    self.assertEqual(expected, actual)
                        if key.startswith("language_name_"):
                            self.assertEqual(text(original), text(entry))


if __name__ == "__main__":
    unittest.main()
