"""Check the translated display labels and exact values of settings arrays."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EXACT_LABELS = {
    "http_fake_profiles": {"iana_get": "IANA GET", "cloudflare_get": "Cloudflare GET"},
    "ip_id_modes": {"seqgroup": "Seqgroup"},
    "fake_order_modes": {str(i): f"Altorder {i}" for i in range(4)},
    "tls_fake_profiles": {
        "iana_firefox": "IANA Firefox", "google_chrome": "Google Chrome",
        "vk_chrome": "VK Chrome", "sberbank_chrome": "Sberbank Chrome",
        "rutracker_kyber": "Rutracker Kyber",
    },
    "udp_fake_profiles": {"dht_get_peers": "DHT get_peers"},
}


def locale_directories(resource_root):
    config = ET.parse(resource_root / "xml/locales_config.xml").getroot()
    for entry in config:
        locale = entry.get("{http://schemas.android.com/apk/res/android}name")
        if locale == "en":
            yield "values"
        elif "-" in locale:
            language, region = locale.split("-", 1)
            yield f"values-{language}-r{region}"
        else:
            yield f"values-{locale}"


def strings(directory):
    result = {}
    for file in directory.glob("*.xml"):
        for entry in ET.parse(file).getroot():
            if entry.tag == "string":
                result[entry.get("name")] = "".join(entry.itertext()).strip()
    return result


def resolve(reference, catalog):
    seen = set()
    while reference.startswith("@string/"):
        key = reference.removeprefix("@string/")
        if key in seen:
            raise ValueError(f"Cyclic string reference: {key}")
        seen.add(key)
        if key not in catalog:
            raise ValueError(f"Missing translated string: {key}")
        reference = catalog[key]
    if not reference:
        raise ValueError("Empty display label")
    return reference


def check_options(resource_root):
    arrays = {entry.get("name"): entry for entry in ET.parse(
        resource_root / "values/arrays.xml").getroot() if entry.tag == "array"}
    catalogs = {locale: strings(resource_root / locale)
                for locale in locale_directories(resource_root)}
    errors = []
    for name, array in arrays.items():
        if name.endswith("_entries"):
            continue
        entries = arrays.get(f"{name}_entries")
        if entries is None or len(entries) != len(array):
            errors.append(f"{name}: display/value count mismatch")
            continue
        for label, value in zip(array, entries):
            item = label.get("name")
            text = "".join(label.itertext()).strip()
            if item != value.get("name"):
                errors.append(f"{name}: display/value order mismatch for {item}")
            exact = EXACT_LABELS.get(name, {}).get(item)
            if exact is not None:
                if text != exact:
                    errors.append(f"{name}/{item}: technical label changed")
                continue
            if not text.startswith("@string/"):
                errors.append(f"{name}/{item}: untranslated display label {text!r}")
                continue
            for locale, catalog in catalogs.items():
                try:
                    resolve(text, catalog)
                except ValueError as error:
                    errors.append(f"{locale}/{name}/{item}: {error}")
    return errors


if __name__ == "__main__":
    failures = check_options(ROOT / "app/src/main/res")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        sys.exit(1)
    print("OK: settings display labels resolve in every locale; option order matches.")
