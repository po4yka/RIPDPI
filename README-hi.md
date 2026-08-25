<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="120" alt="RIPDPI लोगो"/>
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

<p align="center"><a href="README.md">English</a> | <a href="README-ru.md">Русский</a> | <a href="README-es.md">Español</a> | <a href="README-de.md">Deutsch</a> | <a href="README-fr.md">Français</a> | <a href="docs/fa/README.md">فارسی</a> | <a href="README-zh-CN.md">简体中文</a> | <b>हिन्दी</b> | <a href="README-pt-BR.md">Português (Brasil)</a></p>

> [!WARNING]
> **यह प्रोजेक्ट विकास के एक सक्रिय चरण में है।** नई सुविधाएँ जोड़ी जा रही हैं और कोड बेस की गुणवत्ता सुधारने के लिए अक्सर बड़े रीफ़ैक्टरिंग किए जाते हैं। इस काम के लिए कोडिंग एजेंट्स का भारी उपयोग होता है, इसलिए **फ़िलहाल `main` पर ब्रेकिंग बदलाव, स्कीमा माइग्रेशन और आंशिक रूप से टूटी हुई कार्यक्षमता संभव है**। यदि आपको कोई रिग्रेशन मिले, तो कृपया [एक issue खोलें](https://github.com/po4yka/RIPDPI/issues) — आपकी प्रतिक्रिया प्रोजेक्ट को स्थिर करने में मदद करती है।

RIPDPI एक Android नेटवर्क-पाथ डायग्नोस्टिक्स और ऑप्टिमाइज़ेशन टूलकिट है। यह डिवाइस पर ही कॉन्फ़िगर करने योग्य पैकेट रणनीतियाँ लागू करता है, आपके द्वारा नियंत्रित relay सर्वरों से जुड़ सकता है, और यह पहचानने के लिए प्रति-कनेक्शन डायग्नोस्टिक्स चलाता है कि प्रत्येक लक्ष्य क्यों विफल हो रहा है या ख़राब हो रहा है। ये तीनों क्षमताएँ स्वतंत्र रूप से या संयोजन में काम करती हैं।

## तीन स्तंभ

### डिवाइस पर पैकेट रणनीतियाँ

ट्रैफ़िक को किसी relay सर्वर पर भेजे बिना डिवाइस पर ही कॉन्फ़िगर करने योग्य पैकेट-स्तरीय रूपांतरण लागू करता है। मुख्य पाथ के लिए root की आवश्यकता नहीं है।

समर्थित तकनीकें: TCP सेगमेंट स्प्लिटिंग और डिसऑर्डर, फ़र्ज़ी पैकेट इंजेक्शन, OOB (urgent pointer), TLS record फ़्रैगमेंटेशन, फ़र्ज़ी TLS first-flight, QUIC handshake विविधता, UDP length-field विविधता, IPv6 extension-header प्रविष्टि, Lua-परिभाषित raw पैकेट प्रेषण, और अनुकूली semantic मार्कर जो लाइव `TCP_INFO` के विरुद्ध स्थिति निर्धारित करते हैं। रणनीति श्रृंखलाएँ इस रिपॉज़िटरी के Rust crates से बनाई जाती हैं, किसी बाहरी रणनीति बाइनरी के बिना।

जब कोई relay कॉन्फ़िगर नहीं होता, तो ट्रैफ़िक सीधे डिवाइस से बाहर निकलता है — डिवाइस पर किए गए रूपांतरण ही पाथ में एकमात्र बदलाव होते हैं।

### VPN relay

स्थानीय proxy या VPN ट्रैफ़िक को एन्क्रिप्टेड relay प्रोटोकॉल के माध्यम से आपके द्वारा कॉन्फ़िगर किए गए सर्वर तक चेन करता है:

| प्रकार / प्रोटोकॉल | इंटीग्रेशन स्तर | दायरा | ट्रैफ़िक |
| --- | --- | --- | --- |
| `vless_reality` / VLESS Reality TCP | Native relay-core बैकएंड (`ripdpi-vless`) | Client relay | TCP |
| `vless_reality` / xHTTP transport | Native relay-core बैकएंड (`ripdpi-xhttp`) | Client relay | TCP |
| `cloudflare_tunnel` | Native xHTTP relay पाथ साथ ही वैकल्पिक Cloudflare publish रनटाइम | Client relay / local-origin publish | TCP |
| `hysteria2` | Native relay-core बैकएंड (`ripdpi-hysteria2`) | Client relay | TCP + UDP |
| `tuic_v5` | Native relay-core बैकएंड (`ripdpi-tuic`) | Client relay | TCP + UDP |
| `masque` | Native relay-core बैकएंड (`ripdpi-masque`): TCP के लिए HTTP/2 classic CONNECT, UDP के लिए HTTP/3 CONNECT-UDP | Client relay | TCP + UDP |
| `shadowtls_v3` | profile-समर्थित आंतरिक relay के साथ Native relay-core बैकएंड (`ripdpi-shadowtls`) | Client relay | TCP |
| `trojan` | Native relay-core बैकएंड (`ripdpi-trojan`) | Client relay | TCP + UDP |
| `anytls` | Native relay-core बैकएंड (`ripdpi-anytls`) | Client relay | TCP + UDP |
| `shadowsocks` | Native relay-core बैकएंड (`ripdpi-shadowsocks`) | Client relay | TCP + UDP |
| `tor` | bridge/PT bootstrap के साथ Native Arti-समर्थित relay-core बैकएंड (`ripdpi-tor`) | वैकल्पिक client गुमनामी relay | TCP |
| `naiveproxy` | Android service कोड द्वारा निगरानी किया जाने वाला बाहरी helper प्रोसेस (`ripdpi-naiveproxy`) | Client relay | TCP |
| `google_apps_script` | `libripdpi-relay.so` द्वारा चयनित रिपॉज़िटरी-आंतरिक Rust Apps Script relay रनटाइम (`ripdpi-apps-script-core`) | Client relay पाथ | TCP |
| `snowflake` | बाहरी Go pluggable-transport बाइनरी (`ripdpi-snowflake`) | Client PT relay | TCP |
| `webtunnel` | रिपॉज़िटरी-आंतरिक Rust pluggable-transport helper बाइनरी (`ripdpi-webtunnel`) | Client PT relay | TCP |
| `obfs4` | बाहरी pluggable-transport बाइनरी (`ripdpi-obfs4`) | Client PT relay | TCP |
| `chain_relay` | संदर्भित relay profiles पर Native relay-core संरचना | क्रमबद्ध 2-4 hop client relay | TCP |
| `mieru` | Native relay-core बैकएंड (`ripdpi-mieru`); कस्टम UDP/TCP wire engine लंबित रहने तक UDP relay बंद रखा गया | Client relay | TCP |
| `ssh` | Native relay-core बैकएंड (`ripdpi-ssh`) | Client relay | TCP |
| `vless` | मान्यता प्राप्त profile/settings अनुकूलता प्रकार; relay-core descriptor-समर्थित बैकएंड नहीं | Import/config अनुकूलता | TCP |

Snowflake जानबूझकर एक बाहरी Go बाइनरी बना रहता है; देखें [Snowflake native Rust no-go निर्णय](docs/architecture/snowflake-native-rust-decision.md)। VLESS Reality असली ECH का उपयोग नहीं करता; GREASE-only नीति के लिए [ADR 0001](docs/adr/0001-reality-ech.md) देखें।

WARP और AmneziaWG अलग VPN/tunnel profile सतहें हैं, relay-core रजिस्ट्री में `relay_kind` मान नहीं।

स्थानीय proxy मोड और Android VPN पुनर्निर्देशन मोड दोनों relay कॉन्फ़िगर किए जाने या न किए जाने के साथ काम करते हैं।

### डायग्नोस्टिक्स

प्रत्येक कनेक्शन लक्ष्य को स्वतंत्र रूप से स्कैन करता है और एक टाइप्ड निर्णय उत्पन्न करता है:

- `TRANSPARENT_WORKS` — raw पाथ काम करता है, किसी हस्तक्षेप की आवश्यकता नहीं
- `OWNED_STACK_ONLY` — केवल ऐप के स्वामित्व वाले TLS stack के माध्यम से काम करता है
- `NO_DIRECT_SOLUTION` — डिवाइस पर किए गए रूपांतरण इस लक्ष्य को रिकवर नहीं कर सकते; relay आवश्यक है
- `IP_BLOCK_SUSPECT` — IP-स्तरीय ब्लॉक का पता चला

निर्णय प्रति नेटवर्क फ़िंगरप्रिंट संग्रहीत किए जाते हैं और जब वही नेटवर्क फिर से देखा जाता है तो स्वचालित रूप से दोहराए जाते हैं। डायग्नोस्टिक्स स्क्रीन में `ripdpi-diagnostics-candidates` quick/full-matrix सूट से TCP और QUIC रणनीति probing, DNS छेड़छाड़ का पता लगाना, DoH/DoT/DNSCrypt/DoQ resolver सिफ़ारिशें, और निर्यात योग्य डायग्नोस्टिक आर्काइव जोड़े जाते हैं।

## RIPDPI क्यों

आधुनिक Android नेटवर्क नियमित रूप से L7 फ़िंगरप्रिंटिंग (TLS JA3/JA4, QUIC), सेल्युलर और सार्वजनिक Wi-Fi पर आक्रामक QoS, MTU और ECN desync, और middlebox-प्रेरित TLS handshake aborts लागू करते हैं — जिससे कुछ लक्ष्य विफल हो जाते हैं जबकि उसी नेटवर्क पर अन्य ठीक काम करते हैं। एक एकल वैश्विक सेटिंग सभी मामलों को संबोधित नहीं कर सकती।

RIPDPI का डिज़ाइन सिद्धांत: प्रत्येक लक्ष्य और प्रत्येक नेटवर्क को अलग-अलग वर्गीकृत करें, सबसे हल्का फ़िक्स लागू करें जो काम करे, और उसे याद रखें।

1. **प्रति-लक्ष्य, प्रति-नेटवर्क उत्तर** — एक वैश्विक नीति नहीं। डायग्नोस्टिक्स प्रत्येक authority को वर्गीकृत करते हैं और निर्णय को एक नेटवर्क फ़िंगरप्रिंट हैश से जोड़कर संग्रहीत करते हैं।
2. **जब नेटवर्क समस्या हो तो स्थानीय पाथ को रूपांतरित करें।** Semantic मार्कर, अनुकूली split प्लेसमेंट, फ़र्ज़ी-payload श्रृंखलाएँ, OOB/disorder, यादृच्छिक TLS records, QUIC फ़िंगरप्रिंट विविधता — रिपॉज़िटरी-आंतरिक Rust crates से इकट्ठा किया गया।
3. **जब सीधा पाथ ख़राब हो तो tunneled relay पर फ़ॉलबैक करें।** ऊपर दिया गया relay मैट्रिक्स native relay-core बैकएंड, helper subprocesses, बाहरी pluggable transports, और अलग VPN/tunnel profile सतहों के बीच अंतर करता है ताकि असमर्थित या वैकल्पिक पाथ एक ही सुविधा लेबल के पीछे छिपे न रहें।
4. **ईमानदार रिपोर्टिंग।** निर्णय टाइप्ड होते हैं और प्रदर्शित किए जाते हैं; failure classifier परिणामों को दबाने के बजाय सामने लाया जाता है; डायग्नोस्टिक निर्यात बंडल रहस्यों को रिडैक्ट करते हैं।

## स्क्रीनशॉट

<p align="center">
  <img src="docs/screenshots/01-hero.png" width="200" alt="RIPDPI होम स्क्रीन"/>
  &nbsp;
  <img src="docs/screenshots/02-no-root.png" width="200" alt="root के बिना RIPDPI"/>
  &nbsp;
  <img src="docs/screenshots/03-relays.png" width="200" alt="RIPDPI रिमोट रिले"/>
  &nbsp;
  <img src="docs/screenshots/04-controls.png" width="200" alt="RIPDPI नियंत्रण"/>
</p>
<p align="center">
  <img src="docs/screenshots/05-diagnostics.png" width="200" alt="RIPDPI डायग्नोस्टिक्स"/>
  &nbsp;
  <img src="docs/screenshots/06-more-features.png" width="200" alt="RIPDPI सुविधा अवलोकन"/>
</p>

## सुविधाएँ

- **Proxy मोड**: कॉन्फ़िगर किए गए localhost पोर्ट पर स्थानीय SOCKS5 proxy।
- **VPN मोड**: `VpnService` के माध्यम से स्थानीय TUN-to-SOCKS bridge से Android डिवाइस ट्रैफ़िक को रूट करता है।
- **Profile import**: QR-कोड स्कैन और जनरेशन, साथ ही clipboard और share-sheet import। Clipboard/share-sheet पार्सिंग proxy URI codec का उपयोग करती है, जो `vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `anytls://`, `tuic://`, `mieru://`, और `ssh://` स्वीकार करती है; QR स्कैनिंग वर्तमान में `vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `tuic://`, और `mieru://` के लिए सफल होती है। AmneziaWG अलग `amneziawg://` codec का उपयोग करता है। Android intent filters भी import trampoline को `ssh://` उजागर करते हैं, और proxy URI codec इसे पार्स तथा राउंड-ट्रिप करता है।
- **समर्थन सेटिंग्स लिंक**: `ripdpi://support-config` और सत्यापित HTTPS समर्थन लिंक उपयोगकर्ता की पुष्टि के बाद किसी भी संग्रहीत ऐप सेटिंग के लिए समर्थन-प्रदत्त पैच का पूर्वावलोकन और उसे लागू कर सकते हैं।
- **सदस्यताएँ (Subscriptions)**: पृष्ठभूमि स्वचालित-अद्यतन, डुप्लिकेट-profile पहचान, selector/urltest समूह, और बहु-mirror वितरण के साथ base64, Clash / Clash.Meta YAML, sing-box JSON, और WireGuard-INI सदस्यता प्रारूप।
- **एन्क्रिप्टेड DNS**: VPN-संबंधी पाथों में DoH, DoT, DNSCrypt, और DoQ resolver समर्थन।
- **रणनीति नियंत्रण**: TCP split/disorder/fake परिवार, TLS record फ़्रैगमेंटेशन और fake प्रोफ़ाइल, QUIC handshake विविधता, UDP length-field विविधता, IPv6 extension headers, Lua `rawsend`, प्रति-step सक्रियण फ़िल्टर, IPv4 ID नियंत्रण, और OOB इंजेक्शन।
- **प्रति-नेटवर्क नीति स्मृति**: एक नेटवर्क फ़िंगरप्रिंट से जुड़े सत्यापित प्रति-authority निर्णय; पुनः कनेक्ट होने पर स्वचालित रूप से दोहराए जाते हैं।
- **अनुकूली probing**: पहली बार देखे गए नेटवर्क के लिए स्वचालित रणनीति probing; नेटवर्क handover पर पृष्ठभूमि `quick_v1` पुनः-जाँच।
- **Handover-जागरूक पुनरारंभ**: Wi-Fi, सेल्युलर, और रोमिंग के बीच संक्रमण पर लाइव नीति पुनर्मूल्यांकन।
- **RIPDPI Browser**: उन HTTPS लक्ष्यों के लिए ऐप-स्वामित्व वाला browser जिन्हें स्वामित्व वाले TLS stack की आवश्यकता है; ऐप-उत्पन्न अनुरोधों के लिए साझा `SecureHttpClient` पाथ।
- **रनटाइम टेलीमेट्री और लॉग**: proxy जीवनचक्र, route निर्णय, DNS failover घटनाएँ, डायग्नोस्टिक्स प्रगति, और native रनटाइम घटनाएँ — ऐप-आंतरिक इतिहास और समर्थन निर्यात के रूप में उपलब्ध।
- **वैकल्पिक root helper**: rooted डिवाइसों पर, एक विशेषाधिकार प्राप्त helper प्रोसेस के माध्यम से raw-socket संचालन (FakeRst, MultiDisorder, IP फ़्रैगमेंटेशन, पूर्ण SeqOverlap, raw IPv4/IPv6 पैकेट उत्सर्जन) अनलॉक करता है।

## रनटाइम मोड

### Proxy

कॉन्फ़िगर किए गए localhost पोर्ट पर SOCKS5 proxy। उन ऐप्स के लिए जो proxy कॉन्फ़िगरेशन का समर्थन करते हैं। रणनीति रूपांतरण और relay चेनिंग proxy के माध्यम से प्रवेश करने वाले सभी ट्रैफ़िक पर लागू होते हैं।

### VPN

Android `VpnService` का उपयोग करके डिवाइस ट्रैफ़िक को RIPDPI के स्थानीय engine के माध्यम से पुनर्निर्देशित करता है। जब कोई relay कॉन्फ़िगर नहीं होता, तो VPN मोड egress IP बदले बिना डिवाइस पर रूपांतरण लागू करता है। जब relay कॉन्फ़िगर होता है, तो ट्रैफ़िक एन्क्रिप्टेड रूप में कॉन्फ़िगर किए गए endpoint पर अग्रेषित किया जाता है।

## गोपनीयता

RIPDPI डायग्नोस्टिक्स और समस्या निवारण के लिए परिचालन मेटाडेटा रिकॉर्ड करता है: नेटवर्क स्नैपशॉट, resolver स्थिति, route निर्णय, स्कैन परिणाम, service स्थिति, और native रनटाइम घटनाएँ।

सामान्य संचालन में RIPDPI पैकेट कैप्चर नहीं करता, ट्रैफ़िक payload को स्थायी रूप से नहीं रखता और TLS रहस्य रिकॉर्ड नहीं करता। उन्नत पैकेट कैप्चर एक स्पष्ट रूप से opt-in डायग्नोस्टिक टूल है: raw पैकेट bytes सीमित अवधि के लिए स्थानीय रूप से रखे जाते हैं और केवल तभी archive में शामिल होते हैं जब उपयोगकर्ता जानबूझकर उस archive को साझा करता है।

Relay ट्रैफ़िक गोपनीयता आपके द्वारा कॉन्फ़िगर किए गए relay endpoint और profile पर निर्भर करती है।

बहु-hop relay श्रृंखलाएँ 2-4 TCP hops की एक क्रमबद्ध सूची ले जाती हैं (entry, वैकल्पिक मध्यवर्ती, exit)। संग्रहीत profile मॉडल, native wire schema (`chainHops`), प्रति-hop टेलीमेट्री, और chain editor सभी क्रमबद्ध hop सूची ले जाते हैं, जबकि पुराने दो-hop `chainEntry`/`chainExit` आकार को पश्च-संगत मिरर (hop[0]/hop[last]) के रूप में संरक्षित रखा गया है ताकि मौजूदा दो-hop कॉन्फ़िगरेशन साफ़-सुथरे ढंग से माइग्रेट हों। 2-4 की सीमा को एक टाइप्ड validation त्रुटि के रूप में लागू किया जाता है, न कि चुपचाप काट देने के रूप में। chain के माध्यम से UDP जानबूझकर असमर्थित है (`udpCapable=false`)। एक chain anti-correlation को केवल तभी बेहतर बनाती है जब hops विभिन्न trust domains में हों; hops पर समान operator या अधिकार-क्षेत्र का पुनः उपयोग झूठा विश्वास पैदा कर सकता है और इसे UX में एक चेतावनी स्थिति के रूप में सामने लाया जाता है।

## Build

आवश्यकताएँ: JDK 17, Android SDK, Android NDK `29.0.14206865`, Rust toolchain `1.96.0`, आवश्यक ABIs के लिए Android Rust targets, [`just`](https://just.systems) (task runner; `justfile` recipes CI को प्रतिबिंबित करती हैं), और [`lefthook`](https://github.com/evilmartians/lefthook) (pre-commit gates को वायर करने के लिए एक बार `lefthook install` चलाएँ)।

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

स्थानीय builds डिफ़ॉल्ट रूप से `host` (`ripdpi.localNativeAbisDefault`) पर सेट होते हैं, जो host आर्किटेक्चर पर रिज़ॉल्व होता है (जैसे Apple Silicon पर `arm64-v8a`)। एमुलेटर के लिए: `./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64`।

APK आउटपुट variant-विशिष्ट directories में बनते हैं, उदाहरण के लिए `app/build/outputs/apk/githubFull/debug/`; release tasks और paths के लिए [distribution.md](docs/distribution.md) देखें।

## Testing

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

विवरण: [docs/testing.md](docs/testing.md)

## दस्तावेज़ीकरण

**RIPDPI में नए हैं?** अनुशंसित पठन पाथ:
[आर्किटेक्चर अवलोकन](docs/architecture/ARCHITECTURE.md) →
[रनटाइम मोड](docs/architecture/RUNTIME_MODES.md) →
[native Rust workspace](docs/architecture/NATIVE_RUST.md) →
[Kotlin/Rust JNI contract](docs/architecture/JNI_CONTRACT.md) →
[config contracts](docs/architecture/CONFIG_CONTRACTS.md) →
[feature extension guide](docs/architecture/FEATURE_EXTENSION_GUIDE.md)।

- [Native इंटीग्रेशन और मॉड्यूल](docs/native/README.md)
- [पैकेट रणनीति रनटाइम](docs/packet-strategy-runtime.md)
- [Proxy engine और रणनीति सतह](docs/native/proxy-engine.md)
- [TUN-to-SOCKS bridge](docs/native/tunnel.md)
- [Strategy-pack और TLS catalog संचालन](docs/strategy-pack-operations.md)
- [Relay profile उदाहरण](docs/relay-profile-examples.md)
- [एक सर्वर कॉन्फ़िगरेशन import करना](docs/server-integration.md)
- [स्थानीय नेटवर्क test lab](test-lab/README.md)
- [बाहरी UI स्वचालन](docs/automation/README.md)
- [आर्किटेक्चर नोट्स](docs/architecture/README.md)
- [Roadmap](ROADMAP.md)

## RIPDPI का अनुवाद करें

अनुवाद GitHub pull requests के माध्यम से समुदाय द्वारा योगदान किए जाते हैं। किसी locale को जोड़ने या सुधारने के तरीके के लिए [docs/localization.md](docs/localization.md) देखें। प्रत्येक string को मर्ज होने से पहले एक मनुष्य द्वारा समीक्षा की जाती है; मशीनी अनुवाद केवल एक शुरुआती बिंदु है, कभी भी अंतिम प्रति नहीं।
