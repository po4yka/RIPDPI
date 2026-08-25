<p align="center">
  <img src="./assets/readme/hero.svg" width="100%" alt="RIPDPI: diagnóstico de caminho de rede no Android que mede o caminho direto, classifica a falha e aplica a correção mais leve que funciona ou um relay opcional"/>
</p>

<p align="center">
  <a href="https://github.com/po4yka/RIPDPI/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/po4yka/RIPDPI/ci.yml?style=flat-square&label=CI" alt="CI"/></a>
  <a href="https://github.com/po4yka/RIPDPI/releases/latest"><img src="https://img.shields.io/github/v/release/po4yka/RIPDPI?style=flat-square" alt="Versão"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/po4yka/RIPDPI?style=flat-square" alt="Licença"/></a>
  &nbsp;
  <img src="https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1+"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white" alt="Rust"/>
</p>

<p align="center"><a href="README.md">English</a> | <a href="README-ru.md">Русский</a> | <a href="README-es.md">Español</a> | <a href="README-de.md">Deutsch</a> | <a href="README-fr.md">Français</a> | <a href="docs/fa/README.md">فارسی</a> | <a href="README-zh-CN.md">简体中文</a> | <a href="README-hi.md">हिन्दी</a> | <b>Português (Brasil)</b></p>

> [!WARNING]
> **O projeto está em uma fase ativa de desenvolvimento.** Novos recursos estão sendo adicionados e grandes refatorações são realizadas com frequência para melhorar a qualidade da base de código. Agentes de codificação são usados intensivamente nesse trabalho, portanto **mudanças que quebram compatibilidade (breaking changes), migrações de schema e funcionalidades parcialmente indisponíveis são atualmente possíveis no `main`**. Se você encontrar uma regressão, por favor [abra uma issue](https://github.com/po4yka/RIPDPI/issues) — seu feedback ajuda a estabilizar o projeto.

RIPDPI é um kit de ferramentas Android de diagnóstico e otimização do caminho de rede. Ele mede por que um destino está falhando ou sofrendo degradação, aplica estratégias configuráveis de pacotes no próprio dispositivo e pode se conectar por meio de servidores relay sob o seu controle. Cada recurso funciona de forma independente ou em combinação.

## Veja o caminho, não apenas um interruptor

<p align="center">
  <img src="docs/screenshots/01-hero.png" width="29%" alt="Tela inicial do RIPDPI com estratégia de caminho local, caminho de relay e controles de varredura de diagnóstico"/>
  &nbsp;
  <img src="docs/screenshots/05-diagnostics.png" width="29%" alt="Tela de diagnóstico do RIPDPI com resultados de rede por destino"/>
  &nbsp;
  <img src="docs/screenshots/03-relays.png" width="29%" alt="Tela de configuração do caminho de relay do RIPDPI"/>
</p>

Em vez de uma única política global, o RIPDPI classifica cada destino e cada rede separadamente, lembra os resultados validados e torna seus vereditos de falha visíveis. Comece localmente; introduza um relay somente quando o caminho direto não puder ser recuperado.

## Início rápido

Compile o APK de depuração do Android a partir do código-fonte:

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

Consulte [requisitos de build e caminhos de saída](#requisitos-de-build) antes de preparar um dispositivo ou uma build de release.

## Três capacidades, um ciclo de decisão

### Estratégias de pacotes no dispositivo

Aplica transformações configuráveis em nível de pacote no próprio dispositivo, sem rotear o tráfego para um servidor relay. Nenhum root é necessário para o caminho principal.

Técnicas suportadas: divisão e desordenação de segmentos TCP, injeção de pacotes falsos, OOB (ponteiro urgente), fragmentação de registros TLS, primeiro voo TLS falso (fake TLS first-flight), variação do handshake QUIC, variação do campo de comprimento UDP, inserção de cabeçalhos de extensão IPv6, envio de pacotes brutos definidos em Lua e marcadores semânticos adaptativos que resolvem a posição contra o `TCP_INFO` ao vivo. Cadeias de estratégias são construídas a partir de crates Rust deste repositório, sem binário externo de estratégia.

Quando nenhum relay está configurado, o tráfego sai do dispositivo diretamente — as mutações no dispositivo são a única mudança no caminho.

### Diagnóstico

Varre cada destino de conexão de forma independente e produz um veredito tipado:

- `TRANSPARENT_WORKS` — o caminho bruto funciona, nenhuma intervenção é necessária
- `OWNED_STACK_ONLY` — funciona apenas pela pilha TLS própria do aplicativo
- `NO_DIRECT_SOLUTION` — as mutações no dispositivo não conseguem recuperar este destino; é necessário um relay
- `IP_BLOCK_SUSPECT` — bloqueio em nível de IP detectado

Os vereditos são armazenados por fingerprint de rede e reproduzidos automaticamente quando a mesma rede é vista novamente. A tela de diagnóstico adiciona sondagem de estratégias TCP e QUIC das suítes quick/full-matrix do `ripdpi-diagnostics-candidates`, detecção de adulteração de DNS, recomendações de resolvedores DoH/DoT/DNSCrypt/DoQ e arquivos de diagnóstico exportáveis.

### Relay VPN opcional

Encadeia o tráfego do proxy local ou da VPN por protocolos de relay criptografados até um servidor que você configura:

| Tipo / protocolo | Camada de integração | Escopo | Tráfego |
| --- | --- | --- | --- |
| `vless_reality` / VLESS Reality TCP | Backend nativo do relay-core (`ripdpi-vless`) | Relay do cliente | TCP |
| `vless_reality` / transporte xHTTP | Backend nativo do relay-core (`ripdpi-xhttp`) | Relay do cliente | TCP |
| `cloudflare_tunnel` | Caminho de relay xHTTP nativo mais runtime opcional de publicação do Cloudflare | Relay do cliente / publicação de origem local | TCP |
| `hysteria2` | Backend nativo do relay-core (`ripdpi-hysteria2`) | Relay do cliente | TCP + UDP |
| `tuic_v5` | Backend nativo do relay-core (`ripdpi-tuic`) | Relay do cliente | TCP + UDP |
| `masque` | Backend nativo do relay-core (`ripdpi-masque`): CONNECT clássico HTTP/2 para TCP, CONNECT-UDP HTTP/3 para UDP | Relay do cliente | TCP + UDP |
| `shadowtls_v3` | Backend nativo do relay-core (`ripdpi-shadowtls`) com um relay interno baseado em perfil | Relay do cliente | TCP |
| `trojan` | Backend nativo do relay-core (`ripdpi-trojan`) | Relay do cliente | TCP + UDP |
| `anytls` | Backend nativo do relay-core (`ripdpi-anytls`) | Relay do cliente | TCP + UDP |
| `shadowsocks` | Backend nativo do relay-core (`ripdpi-shadowsocks`) | Relay do cliente | TCP + UDP |
| `tor` | Backend nativo do relay-core baseado no Arti (`ripdpi-tor`) com bootstrap de bridge/PT | Relay de anonimato do cliente por opt-in | TCP |
| `naiveproxy` | Processo auxiliar externo (`ripdpi-naiveproxy`) supervisionado pelo código de serviço do Android | Relay do cliente | TCP |
| `google_apps_script` | Runtime de relay Apps Script em Rust dentro do repositório (`ripdpi-apps-script-core`) selecionado pelo `libripdpi-relay.so` | Caminho de relay do cliente | TCP |
| `snowflake` | Binário externo de pluggable transport em Go (`ripdpi-snowflake`) | Relay PT do cliente | TCP |
| `webtunnel` | Binário auxiliar de pluggable transport em Rust dentro do repositório (`ripdpi-webtunnel`) | Relay PT do cliente | TCP |
| `obfs4` | Binário externo de pluggable transport (`ripdpi-obfs4`) | Relay PT do cliente | TCP |
| `chain_relay` | Composição nativa do relay-core sobre perfis de relay referenciados | Relay do cliente ordenado de 2-4 saltos | TCP |
| `mieru` | Backend nativo do relay-core (`ripdpi-mieru`); relay UDP mantido desativado até que o engine UDP/TCP personalizado fique pronto | Relay do cliente | TCP |
| `ssh` | Backend nativo do relay-core (`ripdpi-ssh`) | Relay do cliente | TCP |
| `vless` | Tipo de compatibilidade reconhecido para perfis/configurações; não é um backend respaldado por descritores do relay-core | Compatibilidade de importação/configuração | TCP |

O Snowflake permanece intencionalmente como um binário externo em Go; veja a [decisão de não portar o Snowflake para Rust nativo](docs/architecture/snowflake-native-rust-decision.md). O VLESS Reality não usa ECH real; veja o [ADR 0001](docs/adr/0001-reality-ech.md) para conhecer a política somente GREASE.

WARP e AmneziaWG são superfícies separadas de perfis de VPN/túnel, não valores de `relay_kind` no registro do relay-core.

Tanto o modo proxy local quanto o modo de redirecionamento VPN do Android funcionam com ou sem um relay configurado.

## Por que essa abordagem

As redes modernas do Android aplicam regularmente fingerprinting L7 (JA3/JA4 de TLS, QUIC), QoS agressivo em redes móveis e Wi-Fi público, dessincronização de MTU e ECN e abortos de handshake TLS induzidos por middleboxes — fazendo com que alguns destinos falhem enquanto outros na mesma rede funcionam normalmente. Uma única configuração global não consegue tratar todos os casos.

Princípio de design do RIPDPI: classificar cada destino e cada rede separadamente, aplicar a correção mais leve que funciona e lembrar dela.

1. **Resposta por destino e por rede** — não uma única política global. O diagnóstico classifica cada autoridade e armazena o veredito indexado pelo hash do fingerprint da rede.
2. **Mutar o caminho local quando a rede é o problema.** Marcadores semânticos, posicionamento adaptativo das divisões, cadeias de payloads falsos, OOB/desordem, registros TLS randomizados, variação do fingerprint QUIC — montados a partir de crates Rust do próprio repositório.
3. **Recorrer a um relay tunelado quando o caminho direto está degradado.** A matriz de relays acima distingue backends nativos do relay-core, subprocessos auxiliares, pluggable transports externos e superfícies separadas de perfis de VPN/túnel, para que caminhos não suportados ou opt-in não fiquem escondidos atrás de um único rótulo de recurso.
4. **Relatos honestos.** Os vereditos são tipados e exibidos; os resultados do classificador de falhas são expostos em vez de suprimidos; os pacotes de exportação de diagnóstico ocultam segredos.

## Mais da interface

<p align="center">
  <img src="docs/screenshots/02-no-root.png" width="200" alt="RIPDPI sem root"/>
  &nbsp;
  <img src="docs/screenshots/04-controls.png" width="200" alt="Controles do RIPDPI"/>
  &nbsp;
  <img src="docs/screenshots/06-more-features.png" width="200" alt="Visão geral dos recursos do RIPDPI"/>
</p>

## Recursos

- **Modo proxy**: proxy SOCKS5 local na porta localhost configurada.
- **Modo VPN**: roteia o tráfego do dispositivo Android por uma ponte local TUN-to-SOCKS via `VpnService`.
- **Importação de perfis**: leitura e geração de códigos QR, além de importação via área de transferência e share sheet. A análise da área de transferência e do share sheet usa o codec de URI de proxy, que aceita `vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `anytls://`, `tuic://`, `mieru://` e `ssh://`; a leitura de QR atualmente tem sucesso para `vless://`, `ss://`, `trojan://`, `hysteria2://`, `hy2://`, `tuic://` e `mieru://`. O AmneziaWG usa o codec separado `amneziawg://`. Os intent filters do Android também expõem `ssh://` ao trampoline de importação, e o codec de URI de proxy faz a análise e a ida e volta (round-trip) dele.
- **Links de configurações de suporte**: `ripdpi://support-config` e links HTTPS de suporte verificados podem pré-visualizar e aplicar um patch fornecido pelo suporte para qualquer configuração persistida do aplicativo, após confirmação do usuário.
- **Assinaturas**: formatos de assinatura base64, Clash / Clash.Meta YAML, sing-box JSON e WireGuard-INI, com atualização automática em segundo plano, detecção de perfis duplicados, grupos selector/urltest e entrega por múltiplos espelhos.
- **DNS criptografado**: suporte aos resolvedores DoH, DoT, DNSCrypt e DoQ nos caminhos relacionados à VPN.
- **Controles de estratégia**: famílias TCP split/disorder/fake, fragmentação de registros TLS e perfis fake, variação do handshake QUIC, variação do campo de comprimento UDP, cabeçalhos de extensão IPv6, `rawsend` em Lua, filtros de ativação por etapa, controle do ID IPv4 e injeção OOB.
- **Memória de política por rede**: vereditos validados por autoridade, indexados a um fingerprint de rede; reproduzidos automaticamente na reconexão.
- **Sondagem adaptativa**: sondagem automática de estratégias para redes vistas pela primeira vez; reverificação em segundo plano com `quick_v1` na troca de rede.
- **Reinicialização consciente da troca de rede**: reavaliação da política em tempo real nas transições entre Wi-Fi, rede móvel e roaming.
- **Navegador RIPDPI**: navegador próprio do aplicativo para destinos HTTPS que exigem a pilha TLS própria; caminho compartilhado do `SecureHttpClient` para requisições originadas no aplicativo.
- **Telemetria e logs de runtime**: ciclo de vida do proxy, decisões de rota, eventos de failover de DNS, progresso do diagnóstico e eventos nativos de runtime — disponíveis como histórico dentro do aplicativo e exportação de suporte.
- **Auxiliar de root opcional**: em dispositivos com root, libera operações de raw socket (FakeRst, MultiDisorder, fragmentação IP, SeqOverlap completo, emissão bruta de pacotes IPv4/IPv6) por meio de um processo auxiliar privilegiado.

## Modos de execução

### Proxy

Proxy SOCKS5 em uma porta localhost configurada. Para aplicativos que oferecem suporte à configuração de proxy. As mutações de estratégia e o encadeamento de relay se aplicam a todo o tráfego que entra pelo proxy.

### VPN

Usa o `VpnService` do Android para redirecionar o tráfego do dispositivo pelo engine local do RIPDPI. Quando nenhum relay está configurado, o modo VPN aplica as mutações no dispositivo sem alterar o IP de saída. Quando um relay está configurado, o tráfego é encaminhado criptografado para o endpoint configurado.

## Privacidade

O RIPDPI registra metadados operacionais para diagnóstico e solução de problemas: instantâneos de rede, status dos resolvedores, decisões de rota, resultados de varreduras, estado dos serviços e eventos nativos de runtime.

A operação normal não captura pacotes, não persiste payloads de tráfego nem registra segredos TLS. A captura avançada de pacotes é uma ferramenta de diagnóstico explícita por opt-in: ela armazena bytes brutos de pacotes localmente, com retenção limitada, e os inclui em um arquivo apenas quando o usuário compartilha deliberadamente esse arquivo.

A privacidade do tráfego de relay depende do endpoint e do perfil de relay que você configura.

Cadeias de relay multissalto carregam uma lista ordenada de 2-4 saltos TCP (entrada, intermediários opcionais, saída). O modelo de perfil armazenado, o schema de wire nativo (`chainHops`), a telemetria por salto e o editor de cadeias carregam todos a lista ordenada de saltos, com o formato legado de dois saltos `chainEntry`/`chainExit` preservado como um espelho retrocompatível (hop[0]/hop[last]), para que as configurações existentes de dois saltos migrem de forma limpa. O limite de 2-4 é imposto como um erro de validação tipado, não como um truncamento silencioso. UDP através de uma cadeia é intencionalmente não suportado (`udpCapable=false`). Uma cadeia só melhora a anticorrelação quando os saltos estão em domínios de confiança diferentes; reutilizar o mesmo operador ou jurisdição entre saltos pode criar uma falsa sensação de segurança e é sinalizado como uma condição de aviso na interface.

## Requisitos de build

Requisitos: JDK 17, Android SDK, Android NDK `29.0.14206865`, toolchain Rust `1.96.0`, targets Rust para Android das ABIs necessárias, [`just`](https://just.systems) (executor de tarefas; as receitas do `justfile` espelham a CI) e [`lefthook`](https://github.com/evilmartians/lefthook) (execute `lefthook install` uma vez para conectar os gates de pre-commit).

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

Builds locais usam `host` como padrão (`ripdpi.localNativeAbisDefault`), que corresponde à arquitetura da máquina hospedeira (por exemplo, `arm64-v8a` no Apple Silicon). Para emulador: `./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64`.

A saída do APK é específica por flavor, por exemplo `app/build/outputs/apk/githubFull/debug/`; consulte [distribution.md](docs/distribution.md) para conhecer as tarefas e os caminhos de release.

## Testes

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

Detalhes: [docs/testing.md](docs/testing.md)

## Documentação

**Novo no RIPDPI?** Caminho de leitura recomendado:
[Visão geral da arquitetura](docs/architecture/ARCHITECTURE.md) →
[modos de execução](docs/architecture/RUNTIME_MODES.md) →
[workspace Rust nativo](docs/architecture/NATIVE_RUST.md) →
[contrato JNI Kotlin/Rust](docs/architecture/JNI_CONTRACT.md) →
[contratos de configuração](docs/architecture/CONFIG_CONTRACTS.md) →
[guia de extensão de recursos](docs/architecture/FEATURE_EXTENSION_GUIDE.md).

- [Integração nativa e módulos](docs/native/README.md)
- [Runtime de estratégias de pacotes](docs/packet-strategy-runtime.md)
- [Engine de proxy e superfície de estratégias](docs/native/proxy-engine.md)
- [Ponte TUN-to-SOCKS](docs/native/tunnel.md)
- [Operações de strategy-pack e catálogo TLS](docs/strategy-pack-operations.md)
- [Exemplos de perfis de relay](docs/relay-profile-examples.md)
- [Importando uma configuração de servidor](docs/server-integration.md)
- [Laboratório de teste de rede local](test-lab/README.md)
- [Automação de UI externa](docs/automation/README.md)
- [Notas de arquitetura](docs/architecture/README.md)
- [Roadmap](ROADMAP.md)

## Traduza o RIPDPI

As traduções são contribuições da comunidade por meio de pull requests no GitHub. Consulte [docs/localization.md](docs/localization.md) para saber como adicionar ou melhorar um idioma e [o registro de proveniência](docs/localization-provenance.md) para o status de tradução automática e revisão de cada idioma.
