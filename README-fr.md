<p align="center">
  <a href="docs/screenshots/fr/feature-graphic.png"><img src="docs/screenshots/fr/feature-graphic.png" width="100%" alt="Visuel RIPDPI pour Google Play"/></a>
</p>

<p align="center">
  <a href="https://github.com/po4yka/RIPDPI/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/po4yka/RIPDPI/ci.yml?style=flat-square&label=CI" alt="CI"/></a>
  <a href="https://github.com/po4yka/RIPDPI/releases/latest"><img src="https://img.shields.io/github/v/release/po4yka/RIPDPI?style=flat-square" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/po4yka/RIPDPI?style=flat-square" alt="License"/></a>
  &nbsp;
  <img src="https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1+"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white" alt="Rust"/>
</p>

<p align="center"><a href="README.md">English</a> | <a href="README-ru.md">Русский</a> | <a href="README-es.md">Español</a> | <a href="README-de.md">Deutsch</a> | <b>Français</b> | <a href="docs/fa/README.md">فارسی</a> | <a href="README-zh-CN.md">简体中文</a> | <a href="README-hi.md">हिन्दी</a> | <a href="README-pt-BR.md">Português (Brasil)</a></p>

> [!WARNING]
> **Le projet est en phase active de développement.** De nouvelles fonctionnalités sont ajoutées et des refactorisations d'envergure sont fréquemment effectuées pour améliorer la qualité de la base de code. Des agents de codage sont intensivement utilisés pour ce travail, donc sur `main` sont actuellement possibles **des changements incompatibles (breaking changes), des migrations de schéma et des fonctionnalités partiellement cassées**. Si vous rencontrez une régression, [ouvrez une issue](https://github.com/po4yka/RIPDPI/issues) — vos retours aident à stabiliser le projet.

RIPDPI est une boîte à outils Android de diagnostic et d'optimisation du chemin réseau. Elle mesure pourquoi une cible échoue ou se dégrade, applique des stratégies de paquets configurables sur l'appareil et peut se connecter via des serveurs relais que vous contrôlez. Chaque capacité fonctionne seule ou en combinaison.

## Voir le chemin, pas seulement un interrupteur

<p align="center">
  <a href="docs/screenshots/fr/01-hero.png"><img src="docs/screenshots/fr/01-hero.png" width="380" alt="Contrôle de connexion — écran réel"/></a>
  <a href="docs/screenshots/fr/02-diagnostics.png"><img src="docs/screenshots/fr/02-diagnostics.png" width="380" alt="Diagnostic réseau — résultats réels"/></a>
</p>

<p align="center">
  <a href="docs/screenshots/fr/03-relays.png"><img src="docs/screenshots/fr/03-relays.png" width="380" alt="Choix du relais — paramètres réels"/></a>
  <a href="docs/screenshots/fr/04-dns.png"><img src="docs/screenshots/fr/04-dns.png" width="380" alt="DNS chiffré — paramètres réels"/></a>
</p>

<p align="center">
  <a href="docs/screenshots/fr/05-strategies.png"><img src="docs/screenshots/fr/05-strategies.png" width="380" alt="Stratégie des paquets — commandes réelles"/></a>
  <a href="docs/screenshots/fr/06-local-tools.png"><img src="docs/screenshots/fr/06-local-tools.png" width="380" alt="Sauvegarde et restauration — paramètres réels"/></a>
</p>

Visuels Google Play avec des écrans réels de l’application. Ouvrez une image en taille réelle. [Capture et export](play-store-screenshots/README.md).

Au lieu d'une politique globale unique, RIPDPI classe chaque cible et chaque réseau séparément, mémorise les résultats validés et rend visibles ses verdicts d'échec. Commencez en local ; n'ajoutez un relais que lorsque le chemin direct ne peut pas être rétabli.

## Démarrage rapide

Préparez les [prérequis de compilation](#build-requirements), y compris la chaîne d'outils de l'AAR libXray réel, avant d'exécuter ces commandes.

Compilez l'APK de débogage Android depuis les sources :

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
bash scripts/native/build-libxray.sh
bash scripts/native/verify-libxray-artifacts.sh
./gradlew assembleDebug
```

Consultez les [prérequis de compilation et les chemins de sortie](#build-requirements) avant de préparer un appareil ou une compilation de release.

## Trois capacités, une boucle de décision

### Stratégies de paquets sur l'appareil

Applique des transformations configurables au niveau paquet, sur l'appareil, sans router le trafic vers un serveur relais. Aucun accès root n'est requis pour le chemin principal.

Techniques prises en charge : découpage et désordre de segments TCP, injection de faux paquets, OOB (pointeur urgent), fragmentation d'enregistrements TLS, faux premier vol TLS, variation de handshake QUIC, variation du champ de longueur UDP, insertion d'en-têtes d'extension IPv6, envois de paquets bruts définis en Lua, et marqueurs sémantiques adaptatifs qui résolvent la position en fonction de `TCP_INFO` en direct. Les chaînes de stratégies sont construites à partir de crates Rust de ce dépôt, sans binaire de stratégie externe.

Lorsqu'aucun relais n'est configuré, le trafic quitte l'appareil directement — les mutations sur l'appareil sont le seul changement apporté au chemin.

### Diagnostics

Analyse chaque cible séparément et produit un résultat parmi trois variantes typées :

- `TRANSPARENT_WORKS` — un chemin transparent testé fonctionne, avec ou sans stratégies sur l'appareil
- `OWNED_STACK_ONLY` — fonctionne uniquement via la pile TLS détenue par l'application
- `NO_DIRECT_SOLUTION` — aucune solution directe testée n'a réussi dans le budget de cette exécution ; envisager un relais ou un autre test

Le blocage suspecté est enregistré séparément dans `transportClass`, par exemple `IP_BLOCK_SUSPECT`, `SNI_TLS_SUSPECT` ou `QUIC_BLOCK_SUSPECT`. Ces classes décrivent des indices d'une cause possible ; elles ne sont ni des variantes supplémentaires du résultat ni une preuve de blocage.

Les verdicts sont stockés par empreinte de réseau et rejoués automatiquement lorsque le même réseau est revu. L'écran de diagnostic ajoute le sondage des stratégies TCP et QUIC à partir des suites `ripdpi-diagnostics-candidates` (quick/full-matrix), la détection d'altération DNS, des recommandations de résolveurs DoH/DoT/DNSCrypt/DoQ et des archives de diagnostic exportables.

### Relais VPN optionnel

Chaîne le trafic du proxy local ou du VPN via des protocoles de relais chiffrés vers un serveur que vous configurez :

| Kind / protocol | Integration tier | Scope | Traffic |
| --- | --- | --- | --- |
| `vless_reality` / VLESS Reality TCP | Native relay-core backend (`ripdpi-vless`) | Client relay | TCP + UDP (XUDP) |
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
| `vless` / xHTTP transport | Native relay-core backend (`ripdpi-xhttp`), xHTTP/TLS | Client relay | TCP |

Le `vless` simple possède un backend natif xHTTP/TLS ; ce backend ne prend pas en charge les autres transports VLESS simples. VLESS Reality utilise XUDP uniquement si UDP est activé, le transport est `reality_tcp` et le flow est `xtls-rprx-vision` ou `xtls-rprx-vision-udp443`. xHTTP prend uniquement en charge TCP.

Snowflake reste volontairement un binaire Go externe ; voir la [décision de ne pas porter Snowflake en Rust natif](docs/architecture/snowflake-native-rust-decision.md). VLESS Reality n'utilise pas de véritable ECH ; voir l'[ADR 0001](docs/adr/0001-reality-ech.md) pour la politique GREASE uniquement.

WARP et AmneziaWG sont des surfaces de profils VPN/tunnel distinctes, et non des valeurs `relay_kind` du registre relay-core.

Le mode proxy local comme le mode de redirection VPN d'Android fonctionnent avec ou sans relais configuré.

## Pourquoi cette approche

Les réseaux Android modernes appliquent régulièrement un fingerprinting L7 (TLS JA3/JA4, QUIC), une QoS agressive sur les réseaux cellulaires et le Wi-Fi public, un désynchronisation MTU et ECN et des abandons de handshake TLS induits par les middleboxes — ce qui fait échouer certaines cibles tandis que d'autres fonctionnent très bien sur le même réseau. Un unique réglage global ne peut pas couvrir tous les cas.

Principe de conception de RIPDPI : classifier chaque cible et chaque réseau séparément, appliquer la solution la plus légère qui fonctionne et la mémoriser.

1. **Une réponse par cible et par réseau** — pas une politique globale unique. Les diagnostics classifient chaque autorité et stockent le verdict indexé sur un hash d'empreinte de réseau.
2. **Mutez le chemin local lorsque le réseau est le problème.** Marqueurs sémantiques, placement adaptatif des découpages, chaînes de faux payloads, OOB/désordre, enregistrements TLS aléatoires, variation d'empreinte QUIC — assemblés à partir de crates Rust internes au dépôt.
3. **Repliez-vous sur un relais tunnelé lorsque le chemin direct est dégradé.** La matrice de relais ci-dessus distingue native relay-core backends, helper subprocesses, external pluggable transports et surfaces VPN/tunnel séparées.
4. **Rapports honnêtes.** Les verdicts sont typés et affichés ; les résultats du classifieur d'échec sont mis en évidence plutôt que supprimés ; les paquets d'export de diagnostic masquent les secrets.

## Fonctionnalités

- **Mode proxy** : proxy SOCKS5 local sur le port localhost configuré.
- **Mode VPN** : achemine le trafic de l'appareil Android via un pont local TUN-vers-SOCKS grâce à `VpnService`.
- **Import de profil** : un classificateur commun sert le scanner QR (caméra ou image enregistrée), le presse-papiers et la feuille de partage Android (expérience complète). Il accepte les URI de proxy (`vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `anytls://`, `tuic://`, `mieru://`, `ssh://`), les liens d'abonnement (`https://` ainsi que les liens d'abonnement `ripdpi://`, `sing-box://`, NekoBox `sn://`, Clash, v2rayNG et Hiddify), le texte `.conf` WireGuard / AmneziaWG, les liens `amneziawg://` et les clés `vpn://` d'AmneziaVPN, y compris leur série de plusieurs QR codes. **Ajouter un profil** dans les paramètres VPN ouvre aussi un écran de lien d'abonnement et un sélecteur de fichiers `.conf`. Les profils peuvent aussi être partagés sous forme de QR code.
- **Liens de paramètres de support** : `ripdpi://support-config` et les liens HTTPS de support vérifiés peuvent afficher un aperçu puis, après confirmation de l'utilisateur, appliquer un correctif fourni par le support à n'importe quel paramètre persistant de l'application.
- **Abonnements** : formats d'abonnement base64, Clash / Clash.Meta YAML, sing-box JSON et WireGuard-INI avec mise à jour automatique en arrière-plan, détection des profils en double, groupes selector/urltest et livraison multi-miroir.
- **Reçus de migration de serveur** : un abonnement de remplacement peut conserver une identité de livraison exacte. Dans l'état de l'abonnement, testez explicitement un chemin de remplacement déclaré et partagez un reçu JSON local. Reality/Vision, XHTTP et Hysteria2 utilisent des sondes de candidats isolées ; AmneziaWG exige de connecter d'abord le profil de remplacement importé. Les reçus « reçu seulement » et « testé » sont distincts ; aucun n'est envoyé automatiquement.
- **DNS chiffré** : prise en charge des résolveurs DoH, DoT, DNSCrypt et DoQ dans les chemins liés au VPN.
- **Contrôles de stratégie** : familles TCP split/disorder/fake, fragmentation d'enregistrements TLS et faux profils, variation de handshake QUIC, variation du champ de longueur UDP, en-têtes d'extension IPv6, `rawsend` Lua, filtres d'activation par étape, contrôle de l'ID IPv4 et injection OOB.
- **Mémoire de politique par réseau** : verdicts validés par autorité indexés sur une empreinte de réseau ; rejoués automatiquement à la reconnexion.
- **Sondage adaptatif** : sondage automatique des stratégies pour les réseaux vus pour la première fois ; revérification `quick_v1` en arrière-plan lors d'un handover réseau.
- **Redémarrage conscient du handover** : réévaluation en direct de la politique lors des transitions entre Wi-Fi, cellulaire et roaming.
- **Navigateur RIPDPI** : navigateur détenu par l'application pour les cibles HTTPS qui nécessitent la pile TLS détenue ; chemin `SecureHttpClient` partagé pour les requêtes émises par l'application.
- **Télémétrie et journaux d'exécution** : cycle de vie du proxy, décisions de route, événements de bascule DNS, progression des diagnostics et événements du moteur natif — disponibles en historique intégré à l'application et en export d'assistance.
- **Helper root optionnel** : sur les appareils rootés, débloque les opérations sur sockets bruts (FakeRst, MultiDisorder, fragmentation IP, SeqOverlap complet, émission de paquets IPv4/IPv6 bruts) via un processus auxiliaire privilégié.

## Modes d'exécution

### Proxy

Proxy SOCKS5 sur un port localhost configuré. Pour les applications qui prennent en charge la configuration de proxy. Les mutations de stratégie et le chaînage de relais s'appliquent à tout le trafic qui entre par le proxy.

### VPN

Utilise `VpnService` Android pour rediriger le trafic de l'appareil via le moteur local de RIPDPI. Lorsqu'aucun relais n'est configuré, le mode VPN applique les mutations sur l'appareil sans changer l'IP de sortie. Lorsqu'un relais est configuré, le trafic est transféré chiffré vers le point de terminaison configuré.

## Confidentialité

RIPDPI enregistre des métadonnées opérationnelles pour le diagnostic et le dépannage : instantanés réseau, état du résolveur, décisions de routage, résultats d'analyse, état du service et événements du runtime natif.

En fonctionnement normal, RIPDPI ne capture pas de paquets, ne conserve pas de charges utiles de trafic et n'enregistre pas de secrets TLS. La capture avancée de paquets est un outil de diagnostic à activer explicitement : elle stocke les octets bruts localement avec une rétention limitée et ne les inclut dans une archive que lorsque l'utilisateur partage délibérément cette archive.

La confidentialité du trafic relayé dépend du point de terminaison du relais et du profil que vous configurez.

Les chaînes de relais multi-sauts transportent une liste ordonnée de 2 à 4 sauts TCP (entrée, intermédiaires facultatifs, sortie). Le modèle de profil stocké, le schéma de transmission natif (`chainHops`), la télémétrie par saut et l'éditeur de chaîne portent tous la liste ordonnée des sauts, et l'ancienne forme à deux sauts `chainEntry`/`chainExit` est conservée comme miroir rétrocompatible (hop[0]/hop[last]) afin que les configurations à deux sauts existantes migrent sans problème. La limite de 2 à 4 est appliquée par une erreur de validation typée, et non par une troncature silencieuse. L'UDP à travers une chaîne n'est volontairement pas pris en charge (`udpCapable=false`). Une chaîne n'améliore l'anti-corrélation que si les sauts relèvent de domaines de confiance différents ; réutiliser le même opérateur ou la même juridiction sur plusieurs sauts peut donner une fausse impression de sécurité et est signalé par un avertissement dans l'interface.

## Prérequis de compilation

Prérequis : JDK 17, Android SDK (plateforme `37.1`, CMake `3.31.6`), Android NDK `29.0.14206865`, toolchain Rust `1.98.1`, cibles Android Rust pour les ABI nécessaires, [`just`](https://just.systems) (exécuteur de tâches ; les recettes du `justfile` reproduisent la CI) et [`lefthook`](https://github.com/evilmartians/lefthook) (exécutez `lefthook install` une fois pour activer les contrôles pre-commit).

Un checkout neuf nécessite aussi le vrai AAR libXray avec les correctifs du projet avant de créer l'APK. Préparez Go amd64, les versions fixées de `gomobile` et `gobind`, ainsi que `ANDROID_NDK_HOME` selon [la procédure libXray](docs/native/libxray-packaging.md#build). Le producteur nécessite un dossier `native/xray/artifacts/` neuf ou vide et un accès aux sources fixées. Pour réutiliser un dossier vérifié conforme aux versions actuelles, passez `-Pripdpi.prebuiltXrayAarDir=/absolute/path/to/artifacts` à Gradle ; la vérification avant empaquetage reste active.

Installez les paquets SDK requis avec Android CLI 1.0+ :

```bash
android sdk install platforms/android-37.1 ndk/29.0.14206865 cmake/3.31.6
```

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
bash scripts/native/build-libxray.sh
bash scripts/native/verify-libxray-artifacts.sh
./gradlew assembleDebug
```

Les compilations locales utilisent par défaut `host` (`ripdpi.localNativeAbisDefault`) : l'ABI est dérivée de l'architecture de l'hôte (par ex. `arm64-v8a` sur Apple Silicon). Pour l'émulateur : `./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64`.

Les APK sont produits dans des répertoires propres aux variantes, par exemple `app/build/outputs/apk/githubFull/debug/` ; consultez [distribution.md](docs/distribution.md) pour les tâches et chemins de release.

Les builds debug locaux utilisent des lanceurs stub pour Snowflake, obfs4, WebTunnel et le helper de publication Cloudflare. Ces helpers ne transmettent pas de trafic dans cet APK debug. Pour produire les vrais helpers, installez Go et Git en plus des outils Rust/NDK ci-dessus ; il faut accéder aux dépôts fixés et aux modules Go. Les versions de Go sont dans [le manifeste des sources](native/pluggable-transports/sources.json). Activez les erreurs strictes pour arrêter le build si un helper ne peut pas être compilé :

```bash
./gradlew assembleDebug \
  -Pripdpi.pluggableTransportAssetsMode=source \
  -Pripdpi.pluggableTransportAssetsStrictFailures=true
```

## Tests

```bash
./gradlew testDebugUnitTest
bash scripts/ci/run-rust-native-checks.sh
bash scripts/ci/run-rust-network-e2e.sh
./test-lab/scripts/start-lab.sh --profile emulator
./test-lab/scripts/adb-install-debug.sh
./test-lab/scripts/adb-run-probe-emulator.sh --mode diagnostics
./test-lab/scripts/stop-lab.sh
python3 -m unittest scripts.tests.test_offline_analytics_pipeline
```

Détails : [docs/testing.md](docs/testing.md)

## Documentation

**Nouveau sur RIPDPI ?** Parcours de lecture recommandé :
[Vue d'ensemble de l'architecture](docs/architecture/ARCHITECTURE.md) →
[modes d'exécution](docs/architecture/RUNTIME_MODES.md) →
[workspace Rust natif](docs/architecture/NATIVE_RUST.md) →
[contrat JNI Kotlin/Rust](docs/architecture/JNI_CONTRACT.md) →
[contrats de configuration](docs/architecture/CONFIG_CONTRACTS.md) →
[guide d'extension des fonctionnalités](docs/architecture/FEATURE_EXTENSION_GUIDE.md).

- [Intégration native et modules](docs/native/README.md)
- [Runtime des stratégies de paquets](docs/packet-strategy-runtime.md)
- [Moteur de proxy et surface des stratégies](docs/native/proxy-engine.md)
- [Pont TUN-vers-SOCKS](docs/native/tunnel.md)
- [Opérations du pack de stratégies et du catalogue TLS](docs/strategy-pack-operations.md)
- [Exemples de profils de relais](docs/relay-profile-examples.md)
- [Importer une configuration de serveur](docs/server-integration.md)
- [Laboratoire local de tests réseau](test-lab/README.md)
- [Automatisation externe de l'interface](docs/automation/README.md)
- [Notes d'architecture](docs/architecture/README.md)
- [Feuille de route](ROADMAP.md)

## Traduire RIPDPI

Les traductions sont des contributions de la communauté via des pull requests GitHub. Consultez [docs/localization.md](docs/localization.md) pour ajouter ou améliorer une langue et [le registre de provenance](docs/localization-provenance.md) pour les statuts et dates de révision. Les contrôles structurels ne prouvent pas la justesse linguistique ; l'arabe, le hindi et le portugais brésilien attendent encore une révision par des locuteurs natifs.
