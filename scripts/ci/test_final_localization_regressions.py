"""Protect count-neutral templates and DNS answer-quality composition."""

import re
import unittest

from test_localization_regressions import FORMAT, LOCALES, resources, text


COUNT_KEYS = (
    "home_generated_tcp_probes",
    "home_generated_blocked_services",
    "home_generated_dns_bootstrap",
    "pause_remaining",
    "logs_entries_count",
    "vpn_pcap_viewer_meta_format",
)
DNS_QUALITY_KEYS = (
    "home_generated_dns_answers_matching",
    "home_generated_dns_answers_usable",
)
ENCRYPTED_ANSWERS = {
    "values": "encrypted answers",
    "values-ru": "зашифрованные ответы",
    "values-es": "respuestas cifradas",
    "values-de": "verschlüsselte Antworten",
    "values-fr": "réponses chiffrées",
    "values-fa": "پاسخ‌های رمزگذاری‌شده",
    "values-ar": "ردوداً مشفّرة",
    "values-zh-rCN": "加密应答",
    "values-hi": "एन्क्रिप्टेड उत्तर",
    "values-pt-rBR": "respostas criptografadas",
}


def compose(template, *arguments):
    """Substitute indexed scalar arguments; Android runtime tests cover rendering."""
    return re.sub(r"%(\d+)\$[sd]", lambda match: str(arguments[int(match[1]) - 1]), template)


class FinalLocalizationRegressionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalogs = {locale: resources("app", locale) for locale in LOCALES}

    def test_dns_quality_is_a_complete_object_without_a_second_answer_noun(self):
        for locale, catalog in self.catalogs.items():
            template = text(catalog["home_generated_dns_rationale"])
            for quality_key in DNS_QUALITY_KEYS:
                with self.subTest(locale=locale, quality=quality_key):
                    quality = text(catalog[quality_key])
                    self.assertIn(ENCRYPTED_ANSWERS[locale], quality)
                    self.assertNotIn(ENCRYPTED_ANSWERS[locale], template)
                    rendered = compose(template, "UDP", "dns_mismatch", "Resolver", quality, "DOH", "1", "33")
                    self.assertEqual(1, rendered.count(ENCRYPTED_ANSWERS[locale]))
                    for argument in ("UDP", "dns_mismatch", "Resolver", "DOH", "33"):
                        self.assertIn(argument, rendered)
                    self.assertNotRegex(rendered, r"%\d+\$")

    def test_scalar_counts_are_labeled_values_for_every_grammatical_boundary(self):
        for locale, catalog in self.catalogs.items():
            for key in COUNT_KEYS:
                template = text(catalog[key]).strip('"')
                with self.subTest(locale=locale, key=key):
                    self.assertRegex(template, r"[:：] %1\$[sd]|[:：]%1\$[sd]")
                    for quantity in (0, 1, 2, 3, 11, 21, 100):
                        rendered = compose(template, quantity)
                        self.assertEqual(1, rendered.count(str(quantity)))
                        self.assertNotRegex(rendered, r"%\d+\$")
            # This count is the sixth argument, separate from answer quality.
            self.assertRegex(text(catalog["home_generated_dns_rationale"]), r"[:：] ?%6\$s")

    def test_new_objects_preserve_resource_type_and_placeholder_contract(self):
        source = self.catalogs["values"]
        keys = COUNT_KEYS + DNS_QUALITY_KEYS + ("home_generated_dns_rationale",)
        for locale, catalog in self.catalogs.items():
            for key in keys:
                with self.subTest(locale=locale, key=key):
                    self.assertEqual("string", catalog[key].tag)
                    self.assertEqual(sorted(FORMAT.findall(text(source[key]))),
                                     sorted(FORMAT.findall(text(catalog[key]))))

    def test_comparison_groups_and_retries_keep_their_meaning(self):
        ar = self.catalogs["values-ar"]
        zh = self.catalogs["values-zh-rCN"]
        for key in ("diagnostics_profile_desc_throttling", "diagnostics_cidr_whitelist_summary_detected",
                    "diagnostics_rkn_complete", "diagnostics_rkn_group_control", "diagnostics_rkn_running"):
            with self.subTest(locale="ar", key=key):
                self.assertNotIn("تحكم", text(ar[key]))
            with self.subTest(locale="zh-CN", key=key):
                self.assertIn("对照", text(zh[key]))
                self.assertNotIn("控制", text(zh[key]))
        self.assertIn("مرجعية", text(ar["diagnostics_dns_integrity_idle_summary"]))
        self.assertIn("对照", text(zh["diagnostics_dns_integrity_running"]))
        for key in ("diagnostics_metric_retries", "data_transparency_performance_metrics"):
            self.assertIn("nouvelles tentatives", text(self.catalogs["values-fr"][key]).lower())
        self.assertEqual("Обновления", text(self.catalogs["values-ru"]["updates_section_title"]))
        self.assertEqual("Paquetes de estrategias", text(self.catalogs["values-es"]["strategy_pack_section_title"]))


if __name__ == "__main__":
    unittest.main()
