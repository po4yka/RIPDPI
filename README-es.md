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

<p align="center"><a href="README.md">English</a> | <a href="README-ru.md">Русский</a> | <b>Español</b> | <a href="README-de.md">Deutsch</a> | <a href="README-fr.md">Français</a> | <a href="docs/fa/README.md">فارسی</a> | <a href="README-zh-CN.md">简体中文</a> | <a href="README-hi.md">हिन्दी</a></p>

> [!WARNING]
> **El proyecto se encuentra en una fase activa de desarrollo.** Se están añadiendo nuevas funcionalidades y se realizan refactorizaciones de gran alcance con frecuencia para mejorar la calidad del código base. En este trabajo se utilizan agentes de codificación de forma intensiva, por lo que actualmente en `main` son posibles **cambios disruptivos (breaking changes), migraciones de esquema y funcionalidades parcialmente rotas**. Si encuentras una regresión, por favor [abre una issue](https://github.com/po4yka/RIPDPI/issues) — tus comentarios ayudan a estabilizar el proyecto.

RIPDPI es un conjunto de herramientas de Android para el diagnóstico y la optimización de la ruta de red. Aplica estrategias de paquetes configurables en el dispositivo, puede conectarse a servidores de relevo que tú controlas y ejecuta diagnósticos por conexión para identificar por qué cada destino está fallando o degradándose. Las tres capacidades funcionan de forma independiente o combinada.

## Tres pilares

### Estrategias de paquetes en el dispositivo

Aplica transformaciones a nivel de paquete configurables en el dispositivo sin enrutar el tráfico a un servidor de relevo. No se requiere root para la ruta principal.

Técnicas soportadas: división y desordenamiento de segmentos TCP, inyección de paquetes falsos, OOB (puntero urgente), fragmentación de registros TLS, primer flight TLS falso, variación del handshake QUIC, variación del campo de longitud UDP, inserción de cabeceras de extensión IPv6, envíos raw de paquetes definidos en Lua y marcadores semánticos adaptativos que resuelven la posición contra `TCP_INFO` en vivo. Las cadenas de estrategia se construyen a partir de crates Rust de este repositorio, sin binario de estrategia externo.

Cuando no hay relevo configurado, el tráfico sale del dispositivo directamente: las mutaciones en el dispositivo son el único cambio en la ruta.

### Relevo VPN

Encadena el tráfico del proxy local o de la VPN a través de protocolos de relevo cifrados hacia un servidor que tú configuras:

> [!NOTE]
> La matriz de protocolos refleja los registros actuales del código fuente. La prosa traducida circundante puede no estar al día con `README.md` hasta la próxima revisión humana.

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

Snowflake intentionally remains an external Go binary; see the [Snowflake native Rust no-go decision](docs/architecture/snowflake-native-rust-decision.md). VLESS Reality does not use real ECH; see [ADR 0001](docs/adr/0001-reality-ech.md) for the GREASE-only policy.

WARP and AmneziaWG are separate VPN/tunnel profile surfaces, not `relay_kind` values in the relay-core registry.

Tanto el modo proxy local como el modo de redirección VPN de Android funcionan con o sin relevo configurado.

### Diagnósticos

Escanea cada destino de conexión de forma independiente y produce un veredicto tipado:

- `TRANSPARENT_WORKS` — la ruta directa funciona, no se requiere intervención
- `OWNED_STACK_ONLY` — funciona solo a través de la pila TLS propia de la aplicación
- `NO_DIRECT_SOLUTION` — las mutaciones en el dispositivo no pueden recuperar este destino; se requiere relevo
- `IP_BLOCK_SUSPECT` — bloqueo a nivel de IP detectado

Los veredictos se almacenan por huella de red y se reproducen automáticamente cuando se ve la misma red de nuevo. La pantalla de diagnósticos añade sondeo de estrategias TCP y QUIC desde las suites `ripdpi-diagnostics-candidates` (quick/full-matrix), detección de manipulación de DNS, recomendaciones de resolutores DoH/DoT/DNSCrypt/DoQ y archivos de diagnóstico exportables.

## Por qué RIPDPI

Las redes modernas de Android aplican habitualmente fingerprinting L7 (TLS JA3/JA4, QUIC), QoS agresivo en redes móviles y Wi-Fi públicas, desincronización de MTU y ECN y abortos de handshake TLS inducidos por middleboxes: esto provoca que algunos destinos fallen mientras que otros en la misma red funcionan bien. Un único ajuste global no puede abordar todos los casos.

Principio de diseño de RIPDPI: clasificar cada destino y cada red por separado, aplicar la corrección más ligera que funcione y recordarla.

1. **Respuesta por destino y por red** — no una política global única. Los diagnósticos clasifican cada autoridad y almacenan el veredicto indexado por un hash de huella de red.
2. **Mutar la ruta local cuando la red es el problema.** Marcadores semánticos, colocación adaptativa de splits, cadenas con payload falso, OOB/desordenamiento, registros TLS aleatorizados, variación de huellas QUIC — ensamblados a partir de crates Rust del propio repositorio.
3. **Recurrir a un relevo tunelizado cuando la ruta directa está degradada.** La matriz de relevo anterior distingue entre backends nativos del núcleo de relevo, subprocesos auxiliares, transportes conectables externos y superficies VPN/túnel separadas.
4. **Reporte honesto.** Los veredictos son tipados y se muestran; los resultados del clasificador de fallos se exponen en lugar de suprimirse; los paquetes de exportación de diagnósticos redactan los secretos.

## Capturas de pantalla

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

## Funcionalidades

- **Modo proxy**: proxy SOCKS5 local en el puerto localhost configurado.
- **Modo VPN**: enruta el tráfico del dispositivo Android a través de un puente local TUN-a-SOCKS mediante `VpnService`.
- **Importación de perfiles**: escaneo y generación de QR, más importación desde portapapeles y hoja de compartir. El análisis del portapapeles y la hoja de compartir usa el códec de URI de proxy, que acepta `vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `anytls://`, `tuic://`, `mieru://` y `ssh://`; el escaneo de QR funciona actualmente para `vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `tuic://` y `mieru://`. AmneziaWG utiliza el códec separado `amneziawg://`. Los filtros de intent de Android también exponen `ssh://` al trampolín de importación, y el códec de URI de proxy lo analiza y lo codifica en ambas direcciones.
- **Suscripciones**: formatos de suscripción base64, Clash / Clash.Meta YAML, sing-box JSON y WireGuard-INI con actualización automática en segundo plano, detección de perfiles duplicados, grupos selector/urltest y entrega multi-mirror.
- **DNS cifrado**: soporte de resolutores DoH, DoT, DNSCrypt y DoQ en las rutas relacionadas con VPN.
- **Controles de estrategia**: familias TCP split/disorder/fake, fragmentación de registros TLS y perfiles falsos, variación de handshake QUIC, variación del campo de longitud UDP, cabeceras de extensión IPv6, `rawsend` Lua, filtros de activación por paso, control de IPv4 ID e inyección OOB.
- **Memoria de política por red**: veredictos por autoridad validados e indexados por una huella de red; reproducidos automáticamente al reconectar.
- **Sondeo adaptativo**: sondeo automático de estrategias para redes vistas por primera vez; recomprobación `quick_v1` en segundo plano al cambiar de red.
- **Reinicio consciente del handover**: reevaluación en vivo de la política en transiciones entre Wi-Fi, red móvil y roaming.
- **RIPDPI Browser**: navegador propio de la aplicación para destinos HTTPS que requieren la pila TLS propia; ruta compartida `SecureHttpClient` para las solicitudes originadas por la aplicación.
- **Telemetría y logs en tiempo de ejecución**: ciclo de vida del proxy, decisiones de enrutamiento, eventos de failover de DNS, progreso de diagnósticos y eventos del entorno de ejecución nativo — disponibles como historial en la aplicación y paquete de exportación de soporte.
- **Asistente root opcional**: en dispositivos con root, habilita operaciones de socket raw (FakeRst, MultiDisorder, fragmentación IP, SeqOverlap completo, emisión de paquetes raw IPv4/IPv6) mediante un proceso ayudante privilegiado.

## Modos de ejecución

### Proxy

Proxy SOCKS5 en un puerto localhost configurado. Para aplicaciones que admiten configuración de proxy. Las mutaciones de estrategia y el encadenamiento de relevo se aplican a todo el tráfico que entra a través del proxy.

### VPN

Utiliza `VpnService` de Android para redirigir el tráfico del dispositivo a través del motor local de RIPDPI. Cuando no hay relevo configurado, el modo VPN aplica mutaciones en el dispositivo sin cambiar la IP de salida. Cuando hay un relevo configurado, el tráfico se reenvía cifrado al endpoint configurado.

## Privacidad

RIPDPI registra metadatos operativos para diagnósticos y resolución de problemas: instantáneas de la red, estado del resolutor, decisiones de ruta, resultados de escaneo, estado del servicio y eventos del runtime nativo.

En el funcionamiento normal RIPDPI no captura paquetes, no conserva las cargas útiles del tráfico ni registra secretos TLS. La captura avanzada de paquetes es una herramienta de diagnóstico que requiere activación explícita: almacena bytes de paquetes sin procesar localmente con retención limitada y solo los incluye en un archivo cuando el usuario decide compartirlo.

La privacidad del tráfico de relevo depende del endpoint de relevo y del perfil que tú configures.

## Compilación

Requisitos: JDK 17, Android SDK, Android NDK `29.0.14206865`, toolchain de Rust `1.96.0`, targets de Rust para Android para las ABI necesarias.

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

Las compilaciones locales utilizan por defecto `host` (`ripdpi.localNativeAbisDefault`): la ABI se deriva de la arquitectura del host (p. ej. `arm64-v8a` en Apple Silicon). Para emulador: `./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64`.

Los APK se generan en directorios específicos de variante, por ejemplo `app/build/outputs/apk/githubFull/debug/`; consulta [distribution.md](docs/distribution.md) para las tareas y rutas de release.

## Pruebas

```bash
./gradlew testDebugUnitTest
bash scripts/ci/run-rust-native-checks.sh
bash scripts/ci/run-rust-network-e2e.sh
python3 -m unittest scripts.tests.test_offline_analytics_pipeline
```

Detalles: [docs/testing.md](docs/testing.md)

## Documentación

- [Integración nativa y módulos](docs/native/README.md)
- [Runtime de estrategias de paquetes](docs/packet-strategy-runtime.md)
- [Motor proxy y superficie de estrategias](docs/native/proxy-engine.md)
- [Puente TUN-a-SOCKS](docs/native/tunnel.md)
- [Operaciones de strategy-pack y catálogo TLS](docs/strategy-pack-operations.md)
- [Ejemplos de perfiles de relevo](docs/relay-profile-examples.md)
- [Notas de arquitectura](docs/architecture/README.md)
- [Task board](docs/tasks/board.md)

## Traducir RIPDPI

Las traducciones son contribuciones de la comunidad mediante pull requests en GitHub. Consulta [docs/localization.md](docs/localization.md) para saber cómo añadir o mejorar un idioma. Cada cadena pasa por revisión humana antes de fusionarse; la traducción automática es solo un punto de partida, nunca el texto final.
