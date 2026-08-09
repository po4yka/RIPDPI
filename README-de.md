<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="120" alt="RIPDPI Logo"/>
</p>

<h1 align="center">RIPDPI</h1>
<p align="center"><b>Routing & Internet Performance Diagnostics Platform Interface</b></p>

<p align="center">
  <a href="https://github.com/po4yka/RIPDPI/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/po4yka/RIPDPI/ci.yml?style=flat-square&label=CI" alt="CI"/></a>
  <a href="https://github.com/po4yka/RIPDPI/releases/latest"><img src="https://img.shields.io/github/v/release/po4yka/RIPDPI?style=flat-square" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/po4yka/RIPDPI?style=flat-square" alt="License"/></a>
  &nbsp;
  <img src="https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1+"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white" alt="Rust"/>
</p>

<p align="center"><a href="README.md">English</a> | <a href="README-ru.md">Русский</a> | <a href="README-es.md">Español</a> | <b>Deutsch</b> | <a href="README-fr.md">Français</a> | <a href="docs/fa/README.md">فارسی</a> | <a href="README-zh-CN.md">简体中文</a> | <a href="README-hi.md">हिन्दी</a></p>

> [!WARNING]
> **Das Projekt befindet sich in einer aktiven Entwicklungsphase.** Neue Funktionen werden hinzugefügt und häufig werden umfangreiche Refactorings durchgeführt, um die Qualität der Codebasis zu verbessern. Dabei werden Coding-Agenten intensiv eingesetzt, daher sind auf `main` aktuell **Breaking Changes, Schema-Migrationen und teilweise fehlerhafte Funktionalität möglich**. Wenn Sie auf eine Regression stoßen, [öffnen Sie bitte ein Issue](https://github.com/po4yka/RIPDPI/issues) — Ihr Feedback hilft, das Projekt zu stabilisieren.

RIPDPI ist ein Toolkit zur Diagnose und Optimierung von Netzwerkpfaden für Android. Es wendet konfigurierbare Paketstrategien direkt auf dem Gerät an, kann eine Verbindung zu von Ihnen kontrollierten Relay-Servern herstellen und führt verbindungsbezogene Diagnosen durch, um zu ermitteln, warum ein bestimmtes Ziel ausfällt oder beeinträchtigt ist. Die drei Funktionen arbeiten unabhängig voneinander oder in Kombination.

## Drei Säulen

### Paketstrategien auf dem Gerät

Wendet konfigurierbare Transformationen auf Paketebene direkt auf dem Gerät an, ohne den Datenverkehr über einen Relay-Server zu leiten. Für den Kernpfad sind keine Root-Rechte erforderlich.

Unterstützte Techniken: TCP-Segmentaufteilung und -Unordnung, Einschleusung gefälschter Pakete, OOB (Urgent Pointer), TLS-Record-Fragmentierung, gefälschter TLS-First-Flight, QUIC-Handshake-Variation, Variation des UDP-Längenfelds, Einfügen von IPv6-Erweiterungsheadern, Lua-definierter Rohpaketversand sowie adaptive semantische Marker, die ihre Position anhand des Live-`TCP_INFO` auflösen. Strategieketten werden aus Rust-Crates in diesem Repository erstellt, ohne externe Strategie-Binärdatei.

Wenn kein Relay konfiguriert ist, verlässt der Datenverkehr das Gerät direkt – die einzigen Änderungen am Pfad sind die Mutationen auf dem Gerät.

### VPN-Relay

Leitet lokalen Proxy- oder VPN-Datenverkehr verschlüsselt über Relay-Protokolle an einen von Ihnen konfigurierten Server weiter:

> [!NOTE]
> Die Protokollmatrix spiegelt die aktuellen Quellcode-Registries wider. Umgebende übersetzte Prosa kann hinter `README.md` zurückbleiben, bis eine menschliche Überprüfung erfolgt.

| Kind / protocol | Integration tier | Scope | Traffic |
| --- | --- | --- | --- |
| `vless_reality` / VLESS Reality TCP | Native relay-core backend (`ripdpi-vless`) | Client relay | TCP |
| `vless_reality` / xHTTP transport | Native relay-core backend (`ripdpi-xhttp`) | Client relay | TCP |
| `cloudflare_tunnel` | Native xHTTP relay path plus optional Cloudflare publish runtime | Client relay / local-origin publish | TCP |
| `hysteria2` | Native relay-core backend (`ripdpi-hysteria2`) | Client relay | TCP + UDP |
| `tuic_v5` | Native relay-core backend (`ripdpi-tuic`) | Client relay | TCP + UDP |
| `masque` | Native relay-core backend (`ripdpi-masque`): HTTP/2 classic CONNECT for TCP, HTTP/3 CONNECT-UDP for UDP | Client relay | TCP + UDP |
| `shadowtls_v3` | Native relay-core backend (`ripdpi-shadowtls`) with a profile-backed inner relay | Client relay | TCP |
| `trojan` | Native relay-core backend (`ripdpi-trojan`) | Client relay | TCP + UDP |
| `anytls` | Native relay-core backend (`ripdpi-anytls`) | Client relay | TCP + UDP |
| `shadowsocks` | Native relay-core backend (`ripdpi-shadowsocks`) | Client relay | TCP + UDP |
| `tor` | Native Arti-backed relay-core backend (`ripdpi-tor`) with bridge/PT bootstrap | Opt-in client anonymity relay | TCP |
| `naiveproxy` | External helper process (`ripdpi-naiveproxy`) supervised by Android service code | Client relay | TCP |
| `google_apps_script` | In-repository Rust Apps Script relay runtime (`ripdpi-apps-script-core`) selected by `libripdpi-relay.so` | Client relay path | TCP |
| `snowflake` | External Go pluggable-transport binary (`ripdpi-snowflake`) | Client PT relay | TCP |
| `webtunnel` | In-repository Rust pluggable-transport helper binary (`ripdpi-webtunnel`) | Client PT relay | TCP |
| `obfs4` | External pluggable-transport binary (`ripdpi-obfs4`) | Client PT relay | TCP |
| `chain_relay` | Native relay-core composition over referenced relay profiles | Ordered 2-4 hop client relay | TCP |
| `mieru` | Native relay-core backend (`ripdpi-mieru`); UDP relay gated off pending the custom UDP/TCP wire engine | Client relay | TCP |
| `ssh` | Native relay-core backend (`ripdpi-ssh`) | Client relay | TCP |
| `vless` | Recognized profile/settings compatibility kind; not a relay-core descriptor-backed backend | Import/config compatibility | TCP |

Snowflake verbleibt absichtlich als externe Go-Binärdatei; siehe die [Entscheidung gegen Snowflake in nativem Rust](docs/architecture/snowflake-native-rust-decision.md). VLESS Reality verwendet kein echtes ECH; siehe [ADR 0001](docs/adr/0001-reality-ech.md) für die ausschließliche GREASE-Richtlinie.

WARP und AmneziaWG sind separate VPN/Tunnel-Profiloberflächen und keine `relay_kind`-Werte in der Relay-Core-Registry.

Sowohl der lokale Proxy-Modus als auch der Android-VPN-Weiterleitungsmodus funktionieren mit oder ohne konfiguriertes Relay.

### Diagnose

Scannt jedes Verbindungsziel unabhängig und liefert ein typisiertes Urteil:

- `TRANSPARENT_WORKS` – der rohe Pfad funktioniert, keine Intervention erforderlich
- `OWNED_STACK_ONLY` – funktioniert nur über den app-eigenen TLS-Stack
- `NO_DIRECT_SOLUTION` – Mutationen auf dem Gerät können dieses Ziel nicht wiederherstellen; ein Relay ist erforderlich
- `IP_BLOCK_SUSPECT` – Blockierung auf IP-Ebene erkannt

Urteile werden pro Netzwerk-Fingerabdruck gespeichert und automatisch wiedergegeben, sobald dasselbe Netzwerk erneut erkannt wird. Der Diagnosebildschirm ergänzt TCP- und QUIC-Strategie-Probing aus den Suites `ripdpi-diagnostics-candidates` (quick/full-matrix), die Erkennung von DNS-Manipulationen, Empfehlungen für DoH-/DoT-/DNSCrypt-/DoQ-Resolver sowie exportierbare Diagnosearchive.

## Warum RIPDPI

Moderne Android-Netzwerke wenden regelmäßig L7-Fingerprinting (TLS JA3/JA4, QUIC), aggressives QoS in Mobilfunk- und öffentlichen WLAN-Netzen, MTU- und ECN-Desync sowie durch Middleboxen verursachte TLS-Handshake-Abbrüche an – was dazu führt, dass einige Ziele ausfallen, während andere im selben Netzwerk einwandfrei funktionieren. Eine einzelne globale Einstellung kann nicht alle Fälle abdecken.

Das Designprinzip von RIPDPI: Jedes Ziel und jedes Netzwerk separat klassifizieren, die leichteste funktionierende Lösung anwenden und sie sich merken.

1. **Antwort pro Ziel, pro Netzwerk** – keine einheitliche globale Richtlinie. Die Diagnose klassifiziert jede Autorität und speichert das Urteil mit einem Netzwerk-Fingerabdruck-Hash als Schlüssel.
2. **Den lokalen Pfad mutieren, wenn das Netzwerk das Problem ist.** Semantische Marker, adaptive Split-Platzierung, Fake-Payload-Ketten, OOB/Unordnung, randomisierte TLS-Records, QUIC-Fingerabdruck-Variation – zusammengestellt aus Rust-Crates im Repository.
3. **Auf ein getunneltes Relay zurückgreifen, wenn der direkte Pfad beeinträchtigt ist.** Die Relay-Matrix oben unterscheidet native relay-core backends, helper subprocesses, external pluggable transports und separate VPN/tunnel profile surfaces.
4. **Ehrliche Berichterstattung.** Urteile sind typisiert und werden angezeigt; Ergebnisse des Fehlerklassifizierers werden offengelegt, statt unterdrückt; exportierte Diagnose-Bundles entfernen Geheimnisse.

## Screenshots

<p align="center">
  <img src="docs/screenshots/01-hero.png" width="200" alt="RIPDPI home screen"/>
  &nbsp;
  <img src="docs/screenshots/02-no-root.png" width="200" alt="RIPDPI without root"/>
  &nbsp;
  <img src="docs/screenshots/03-relays.png" width="200" alt="RIPDPI remote relays"/>
  &nbsp;
  <img src="docs/screenshots/04-controls.png" width="200" alt="RIPDPI controls"/>
</p>
<p align="center">
  <img src="docs/screenshots/05-diagnostics.png" width="200" alt="RIPDPI diagnostics"/>
  &nbsp;
  <img src="docs/screenshots/06-more-features.png" width="200" alt="RIPDPI feature overview"/>
</p>

## Funktionen

- **Proxy-Modus**: lokaler SOCKS5-Proxy auf dem konfigurierten Localhost-Port.
- **VPN-Modus**: leitet den Android-Datenverkehr des Geräts über eine lokale TUN-zu-SOCKS-Brücke mittels `VpnService` weiter.
- **Profilimport**: QR-Code-Scan und -Generierung sowie Import über die Zwischenablage und das Teilen-Menü. Das Parsen von Zwischenablage- und Teilen-Menü-Inhalten erfolgt über den Proxy-URI-Codec, der `vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `anytls://`, `tuic://`, `mieru://` und `ssh://` akzeptiert; der QR-Scan gelingt derzeit für `vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `tuic://` und `mieru://`. AmneziaWG verwendet den separaten `amneziawg://`-Codec. Android-Intent-Filter legen außerdem `ssh://` am Import-Trampolin offen, und der Proxy-URI-Codec parst und kodiert es in beide Richtungen.
- **Abonnements**: base64-, Clash / Clash.Meta-YAML-, sing-box-JSON- und WireGuard-INI-Abonnementformate mit automatischer Hintergrundaktualisierung, Erkennung doppelter Profile, Selector-/urltest-Gruppen und Multi-Mirror-Auslieferung.
- **Verschlüsseltes DNS**: Unterstützung für DoH-, DoT-, DNSCrypt- und DoQ-Resolver in VPN-bezogenen Pfaden.
- **Strategie-Steuerung**: TCP-Familien für Split/Unordnung/Fake, TLS-Record-Fragmentierung und Fake-Profile, QUIC-Handshake-Variation, Variation des UDP-Längenfelds, IPv6-Erweiterungsheader, Lua-`rawsend`, schrittweise Aktivierungsfilter, IPv4-ID-Steuerung und OOB-Einschleusung.
- **Netzwerkbezogenes Richtlinien-Gedächtnis**: validierte autoritätsspezifische Urteile, mit einem Netzwerk-Fingerabdruck verschlüsselt; werden bei erneuter Verbindung automatisch wiedergegeben.
- **Adaptives Probing**: automatisches Strategie-Probing für erstmals gesehene Netzwerke; Hintergrund-`quick_v1`-Neuprüfung bei Netzwerkwechsel.
- **Handover-bewusster Neustart**: dynamische Neubewertung der Richtlinien bei Übergängen zwischen WLAN, Mobilfunk und Roaming.
- **RIPDPI Browser**: app-eigener Browser für HTTPS-Ziele, die den app-eigenen TLS-Stack erfordern; gemeinsamer `SecureHttpClient`-Pfad für von der App ausgehende Anfragen.
- **Laufzeit-Telemetrie und Protokolle**: Proxy-Lebenszyklus, Routenentscheidungen, DNS-Failover-Ereignisse, Diagnosefortschritt und native Laufzeitereignisse – verfügbar als In-App-Verlauf und Support-Export.
- **Optionaler Root-Helfer**: schaltet auf gerooteten Geräten Operationen mit rohen Sockets frei (FakeRst, MultiDisorder, IP-Fragmentierung, vollständiges SeqOverlap, Versand roher IPv4-/IPv6-Pakete) über einen privilegierten Helferprozess.

## Laufzeitmodi

### Proxy

SOCKS5-Proxy auf einem konfigurierten Localhost-Port. Für Anwendungen, die Proxy-Konfiguration unterstützen. Strategie-Mutationen und Relay-Verkettung werden auf den gesamten Datenverkehr angewendet, der über den Proxy eingeht.

### VPN

Verwendet den Android-`VpnService`, um den Gerätedatenverkehr durch die lokale Engine von RIPDPI umzuleiten. Wenn kein Relay konfiguriert ist, wendet der VPN-Modus Mutationen auf dem Gerät an, ohne die Ausgangs-IP zu ändern. Wenn ein Relay konfiguriert ist, wird der Datenverkehr verschlüsselt an den konfigurierten Endpunkt weitergeleitet.

## Datenschutz

RIPDPI zeichnet betriebliche Metadaten zur Diagnose und Fehlerbehebung auf: Netzwerk-Snapshots, Resolver-Status, Routenentscheidungen, Scan-Ergebnisse, Dienststatus und native Laufzeitereignisse.

Im normalen Betrieb zeichnet RIPDPI keine Pakete auf, speichert keine Datenverkehrs-Nutzdaten und protokolliert keine TLS-Geheimnisse. Die erweiterte Paketerfassung ist ein ausdrücklich zu aktivierendes Diagnosewerkzeug: Rohe Paketbytes werden lokal mit begrenzter Aufbewahrung gespeichert und nur dann in ein Archiv aufgenommen, wenn der Benutzer dieses Archiv bewusst teilt.

Der Datenschutz des Relay-Verkehrs hängt von dem von Ihnen konfigurierten Relay-Endpunkt und -Profil ab.

## Build

Voraussetzungen: JDK 17, Android SDK, Android NDK `29.0.14206865`, Rust-Toolchain `1.96.0`, Android-Rust-Targets für die benötigten ABIs.

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

Lokale Builds verwenden standardmäßig `host` (`ripdpi.localNativeAbisDefault`) — die ABI wird aus der Host-Architektur abgeleitet (z. B. `arm64-v8a` auf Apple Silicon). Für den Emulator: `./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64`.

APK-Ausgaben liegen in variantenspezifischen Verzeichnissen, zum Beispiel `app/build/outputs/apk/githubFull/debug/`; Release-Aufgaben und -Pfade stehen in [distribution.md](docs/distribution.md).

## Tests

```bash
./gradlew testDebugUnitTest
bash scripts/ci/run-rust-native-checks.sh
bash scripts/ci/run-rust-network-e2e.sh
python3 -m unittest scripts.tests.test_offline_analytics_pipeline
```

Details: [docs/testing.md](docs/testing.md)

## Dokumentation

- [Native Integration und Module](docs/native/README.md)
- [Laufzeitumgebung der Paketstrategien](docs/packet-strategy-runtime.md)
- [Proxy-Engine und Strategie-Schnittstelle](docs/native/proxy-engine.md)
- [TUN-zu-SOCKS-Brücke](docs/native/tunnel.md)
- [Strategie-Pack- und TLS-Katalog-Operationen](docs/strategy-pack-operations.md)
- [Beispiele für Relay-Profile](docs/relay-profile-examples.md)
- [Architektur-Notizen](docs/architecture/README.md)
- [Task board](docs/tasks/board.md)

## RIPDPI übersetzen

Übersetzungen werden von der Community über GitHub-Pull-Requests beigetragen. Wie du eine Sprache hinzufügst oder verbesserst, beschreibt [docs/localization.md](docs/localization.md). Jede Zeichenkette wird vor dem Merge von einem Menschen geprüft; maschinelle Übersetzung ist nur ein Ausgangspunkt, niemals der endgültige Text.
