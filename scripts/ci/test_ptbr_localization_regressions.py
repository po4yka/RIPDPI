"""Regression checks for audited Brazilian Portuguese resource meanings."""

import re
import unittest
import xml.etree.ElementTree as ET

from test_localization_regressions import LOCALES, ROOT, resources, text


class BrazilianPortugueseLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pt = resources("app", "values-pt-rBR")

    def test_cache_misses_do_not_claim_dns_errors(self):
        value = text(self.pt["connection_health_dns_cache_misses"]).casefold()
        self.assertIn("ausências", value)
        self.assertIn("cache", value)
        self.assertNotIn("erros", value)

    def test_noise_prelude_has_variable_length_and_is_sent_once(self):
        # The native 4-4 range emits four bytes once, not one random byte.
        terms = {
            "values": ("random bytes", "once", "length", "configured range"),
            "values-ru": ("случайных байтов", "один раз", "длина", "диапазона"),
            "values-es": ("bytes aleatorios", "una sola vez", "longitud", "intervalo configurado"),
            "values-de": ("zufälliger Bytes", "einmal", "Länge", "konfigurierten Bereich"),
            "values-fr": ("octets aléatoires", "une seule fois", "longueur", "plage configurée"),
            "values-fa": ("بایت‌های تصادفی", "یک بار", "طول", "بازه"),
            "values-ar": ("بايتات عشوائية", "مرة واحدة", "طوله", "النطاق"),
            "values-zh-rCN": ("随机字节", "一次性", "长度", "配置范围"),
            "values-hi": ("रैंडम बाइट्स", "एक बार", "लंबाई", "रेंज"),
            "values-pt-rBR": ("bytes aleatórios", "uma única vez", "tamanho", "intervalo configurado"),
        }
        self.assertEqual(set(LOCALES), set(terms))
        for locale, required in terms.items():
            value = text(resources("app", locale)["config_relay_finalmask_noise_help"])
            with self.subTest(locale=locale):
                for term in required:
                    self.assertIn(term, value)
        self.assertNotIn("um byte", text(self.pt["config_relay_finalmask_noise_help"]))

    def test_fake_sequence_help_names_the_visible_options(self):
        value = text(self.pt["fake_order_card_seqmode_body"])
        for key in ("ripdpi_fake_seq_mode_duplicate", "ripdpi_fake_seq_mode_sequential"):
            self.assertIn(text(self.pt[key]), value)
        self.assertNotRegex(value, r"Duplicate|Sequential")

    def test_geosite_full_entries_keep_the_technical_type(self):
        value = text(self.pt["host_pack_semantics_note"])
        self.assertIn("full:", value)
        self.assertIn("sufixo", value)
        self.assertIn("host exato", value)
        self.assertIn("subdomínios", value)
        self.assertNotIn("formato completo", value)

    def test_system_dns_captions_use_the_android_setting_name(self):
        # Google uses "DNS particular" for the Android setting in pt-BR.
        keys = ("dns_private_dns_info_body", "dns_private_dns_warning_title",
                "dns_private_dns_warning_body", "remembered_networks_private_dns_label",
                "diagnostics_field_private_dns")
        for key in keys:
            with self.subTest(key=key):
                value = text(self.pt[key])
                self.assertIn("DNS particular", value)
                self.assertNotRegex(value, r"Private DNS|DNS privado")
        self.assertIn("Nenhuma ação é necessária", text(self.pt["dns_private_dns_warning_body"]))

    def test_onboarding_checks_mean_initial_setup(self):
        value = text(self.pt["detection_resistance_tls_fingerprint_body"])
        self.assertIn("verificações da configuração inicial", value)
        self.assertNotIn("verificações de integração", value)
        for token in ("HTTPS", "TLS", "Android 17", "ECH"):
            self.assertIn(token, value)

    def test_clock_recovery_names_the_resume_now_action(self):
        value = text(self.pt["pause_clock_failed"])
        self.assertIn(text(self.pt["pause_resume_now"]), value)
        self.assertIn("relógio", value)
        self.assertIn("identidade de inicialização", value)
        self.assertIn("recuperação", value)

    def test_retry_counters_exclude_the_initial_attempt(self):
        keys = ("diagnostics_probe_retry_count_label", "diagnostics_field_proxy_route_retries",
                "diagnostics_field_tunnel_recovery_retries", "diagnostics_field_total_retries")
        canonical = text(self.pt["diagnostics_metric_retries"]).casefold()
        self.assertEqual("novas tentativas", canonical)
        for key in keys:
            with self.subTest(key=key):
                self.assertIn(canonical, text(self.pt[key]).casefold())

    def test_connected_tile_uses_the_simple_product_name(self):
        path = ROOT / "app/src/simple/res/values-pt-rBR/strings.xml"
        catalog = {entry.get("name"): entry for entry in ET.parse(path).getroot()}
        title = text(catalog["simple_title"])
        connected = text(catalog["simple_status_connected"])
        self.assertEqual(title + ": " + connected, text(catalog["qs_tile_label_connected"]))

    def test_all_locales_keep_types_formats_and_native_language_names(self):
        argument = re.compile(r"(?<!%)%(?!%)(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z]")
        for module in ("app", "core/service"):
            source = resources(module, "values")
            for locale in LOCALES:
                catalog = resources(module, locale)
                for key, original in source.items():
                    if original.get("translatable") == "false":
                        continue
                    with self.subTest(module=module, locale=locale, key=key):
                        self.assertIn(key, catalog)
                        localized = catalog[key]
                        self.assertEqual(original.tag, localized.tag)
                        if original.tag == "string":
                            self.assertEqual(sorted(argument.findall(text(original))),
                                             sorted(argument.findall(text(localized))))
                        elif original.tag == "plurals":
                            # Arabic zero/one/two can state the count without a placeholder.
                            original_other = next(item for item in original if item.get("quantity") == "other")
                            localized_other = next(item for item in localized if item.get("quantity") == "other")
                            self.assertEqual(sorted(argument.findall(text(original_other))),
                                             sorted(argument.findall(text(localized_other))))
                        if key.startswith("language_name_"):
                            self.assertEqual(text(original), text(localized))


if __name__ == "__main__":
    unittest.main()
