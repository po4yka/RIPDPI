"""Regression checks for confirmed Arabic translation meanings and tokens."""

import re
import unittest
import xml.etree.ElementTree as ET

from test_localization_regressions import LOCALES, ROOT, resources, text


class ArabicLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ar = resources("app", "values-ar")

    def value(self, key):
        return text(self.ar[key])

    def test_sensitive_support_warning_explains_risks_and_trust_in_arabic(self):
        title = self.value("support_settings_sensitive_title")
        body = self.value("support_settings_sensitive_body")
        self.assertRegex(title, "[\\u0600-\\u06ff]")
        for term in ("حركة البيانات", "بيانات الاعتماد", "التشخيص", "صلاحيات", "تثق"):
            self.assertIn(term, body)

    def test_support_chain_share_and_device_gate_copy_is_not_english_fallback(self):
        source = resources("app", "values")
        keys = [key for key in self.ar if key.startswith(("support_settings_", "config_chain_", "profile_share_"))]
        keys += ["ripdpi_desync_method_setting", "config_relay_finalmask_fragment",
                 "config_relay_finalmask_fragment_help", "config_relay_finalmask_header",
                 "config_relay_finalmask_header_help", "config_relay_finalmask_noise",
                 "config_relay_finalmask_off", "config_relay_finalmask_off_help",
                 "config_relay_finalmask_sudoku_help"]
        keys += ["diagnostics_device_gate_status_" + status
                 for status in ("idle", "running", "incomplete", "pass", "fail")]
        for key in keys:
            with self.subTest(key=key):
                self.assertNotEqual(text(source[key]), self.value(key))
                self.assertRegex(self.value(key), "[\\u0600-\\u06ff]")

    def test_local_first_copy_keeps_device_and_cloud_meanings(self):
        chip = self.value("onboarding_chip_no_cloud_sync")
        self.assertIn("مزامنة سحابية", chip)
        body = self.value("onboarding_local_first_body")
        self.assertIn("كل شيء", body)
        self.assertIn("على جهازك", body)
        for value in (chip, body):
            self.assertNotIn("استراتيجية التجاوز", value)

    def test_clock_recovery_names_the_visible_resume_action(self):
        self.assertIn(self.value("pause_resume_now"), self.value("pause_clock_failed"))

    def test_cache_misses_are_distinct_from_failed_queries(self):
        value = self.value("connection_health_dns_cache_misses")
        self.assertNotEqual(value, self.value("connection_health_dns_failures"))
        self.assertNotRegex(value, "إخفاق|فشل")
        self.assertIn("ذاكرة التخزين المؤقت", value)

    def test_suffix_matching_keeps_dsl_entry_types(self):
        value = self.value("domain_bypass_intro_body")
        self.assertIn("لواحق", value)
        for token in ("example.com", ".example.com", "domain:", "domain_suffix:", "domain_regex:"):
            self.assertIn(token, value)
        value = self.value("host_pack_semantics_note")
        self.assertIn("لاحقة", value)
        self.assertIn("full:", value)
        self.assertIn("نطاقاته الفرعية", value)
        self.assertNotIn("البادئة النهائية", value)

    def test_network_port_and_data_spans_are_not_seaports_or_time_periods(self):
        self.assertIn("منفذ", self.value("dns_custom_dot_body"))
        self.assertNotIn("ميناء", self.value("dns_custom_dot_body"))
        for key in ("fake_order_card_body", "fake_order_card_order_body"):
            with self.subTest(key=key):
                self.assertIn("مقاطع البيانات", self.value(key))
                self.assertNotIn("الفترات", self.value(key))

    def test_activation_gating_and_udp_bursts_keep_packet_meaning(self):
        for key in ("notice_activation_window_reset_message", "notice_activation_window_reset_next_start_message"):
            self.assertNotIn("تبويب", self.value(key))
        self.assertIn("حزم", self.value("activation_window_custom_body"))
        self.assertNotIn("الطرد", self.value("activation_window_custom_body"))
        self.assertIn("دفعات", self.value("config_udp_chain_hint"))
        self.assertIn("fake_burst", self.value("config_udp_chain_hint"))
        self.assertNotIn("تنبيهات", self.value("config_udp_chain_hint"))

    def test_keystore_and_tls_warning_preserve_security_meaning(self):
        self.assertIn("Android Keystore", self.value("config_relay_body"))
        warning = self.value("ws_tunnel_allow_insecure_sni_helper")
        for token in ("SNI", "WS", "TLS", "يعطّل", "التحقق", "موثوق"):
            self.assertIn(token, warning)
        self.assertNotIn("يُكرِّم", warning)

    def test_diagnostics_explain_provenance_and_network_conditions(self):
        self.assertIn("المنشأ", self.value("diagnostics_share_archive_line"))
        self.assertNotIn("سجل الاستخدام", self.value("diagnostics_share_archive_line"))
        self.assertIn("تسجيل الدخول", self.value("diagnostics_field_captive_portal"))
        self.assertNotIn("محجوزة", self.value("diagnostics_field_captive_portal"))
        self.assertIn("الاستخدام", self.value("diagnostics_field_network_metered"))
        self.assertNotIn("مقيَّدة", self.value("diagnostics_field_network_metered"))
        self.assertEqual(self.value("ripdpi_desync_method_setting"), self.value("diagnostics_field_desync"))
        self.assertNotIn("إزالة", self.value("diagnostics_field_desync"))
        self.assertIn("اتصال الشبكة", self.value("home_diagnostics_network_transport"))

    def test_protocol_strategy_captions_keep_protocol_and_agreement(self):
        for key, protocol in (("http_fake_profile_scope_off", "HTTP"),
                              ("tls_fake_profile_scope_off", "HTTPS"),
                              ("udp_fake_profile_scope_off", "UDP"),
                              ("quic_fake_profile_scope_udp_disabled", "UDP"),
                              ("quic_fake_profile_udp_disabled_title", "UDP")):
            with self.subTest(key=key):
                value = self.value(key)
                self.assertIn("لبروتوكول " + protocol, value)
                self.assertIn("معطّلة", value)
        self.assertIn("استراتيجية التجاوز لبروتوكول UDP", self.value("quic_fake_profile_udp_disabled_body"))
        self.assertIn("لبروتوكولات HTTP وHTTPS وUDP", self.value("fake_payload_library_protocols_disabled_body"))

    def test_simple_tile_uses_the_translated_feature_name(self):
        catalog = {entry.get("name"): entry for entry in ET.parse(
            ROOT / "app/src/simple/res/values-ar/strings.xml").getroot()}
        self.assertEqual(text(catalog["simple_title"]) + ": " + text(catalog["simple_status_connected"]),
                         text(catalog["qs_tile_label_connected"]))

    def test_home_counts_have_all_arabic_categories_and_keep_count_arguments(self):
        keys = ("home_diagnostics_actionable_detector_count", "home_diagnostics_actionable_dns_count",
                "home_diagnostics_actionable_bypass_count")
        for key in keys:
            with self.subTest(key=key):
                entry = self.ar[key]
                self.assertEqual("plurals", entry.tag)
                items = {item.get("quantity"): text(item) for item in entry}
                self.assertEqual({"zero", "one", "two", "few", "many", "other"}, set(items))
                # The Arabic singular and dual forms can state the count without digits.
                for quantity in ("few", "many", "other"):
                    self.assertEqual(["%1$d"], re.findall(r"%\d+\$[ds]", items[quantity]))
                for value in items.values():
                    self.assertRegex(value, "[\u0600-\u06ff]")

    def test_bootstrap_help_excludes_loopback_and_unspecified_not_lan(self):
        terms = {
            "values": ("loopback", "unspecified"),
            "values-ru": ("петлевые", "неопределённые"),
            "values-es": ("bucle local", "no especificadas"),
            "values-de": ("Loopback", "unspezifizierte"),
            "values-fr": ("bouclage", "non spécifiées"),
            "values-fa": ("حلقهٔ محلی", "نامشخص"),
            "values-ar": ("الاسترجاع", "غير المحدد"),
            "values-zh-rCN": ("回环", "未指定"),
            "values-hi": ("लूपबैक", "अनिर्दिष्ट"),
            "values-pt-rBR": ("loopback", "não especificados"),
        }
        self.assertEqual(set(LOCALES), set(terms))
        for locale, required in terms.items():
            with self.subTest(locale=locale):
                value = text(resources("app", locale)["dns_custom_bootstrap_error"])
                for term in required:
                    self.assertIn(term, value)
        self.assertNotIn("من غير الشبكة المحلية", self.value("dns_custom_bootstrap_error"))


if __name__ == "__main__":
    unittest.main()
