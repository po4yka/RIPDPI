/* ui_kits/android/RipDpiScreens.jsx
   Five screens: Home, Diagnostics, Settings (Strategy / Advanced),
   Logs, Onboarding. Wired with simple in-memory click-through state. */

// ─────────────────────────────────────────────────────────────
// HomeScreen — recreates ui/screens/home/HomeScreen.kt
// ─────────────────────────────────────────────────────────────
function HomeScreen({ state, dispatch }) {
  const { c } = useTheme();
  const { bypass, vpn, diagnostic, errorBanner } = state;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: '0 20px 24px' }}>
      <RipDpiTopAppBar title="RIPDPI" subtitle={vpn.connected || bypass.active ? `CONNECTED · ${state.uptime}` : 'IDLE'} />

      {errorBanner && (
        <WarningBanner
          title="Connection failed"
          message="The service stopped before the tunnel could be established. Retry to restart the strategy."
          tone="error"
          onDismiss={() => dispatch({ type: 'dismissError' })}
        />
      )}

      <ConnectionActuator
        connected={vpn.connected || bypass.active}
        onChange={(c) => dispatch({ type: c ? 'connectVpn' : 'disconnectAll' })}
      />

      <HomeModeCard
        title="Local DPI bypass"
        primaryLabel="tlsrec_split_host · AdGuard DoH"
        secondaryLabel={bypass.active ? `Active ${state.uptime}` : null}
        status={bypass.active ? 'active' : 'idle'}
        statusLabel={bypass.active ? `Active ${state.uptime}` : 'Inactive'}
        actionLabel={bypass.active ? 'Disable' : 'Enable'}
        onAction={() => dispatch({ type: bypass.active ? 'disableBypass' : 'enableBypass' })}
        onConfigure={() => dispatch({ type: 'nav', to: 'strategy' })}
      />

      <HomeModeCard
        title="VPN with remote server"
        primaryLabel="relay.example:443"
        secondaryLabel={vpn.connected ? `Connected ${state.uptime}` : null}
        status={vpn.connecting ? 'warning' : vpn.connected ? 'active' : 'idle'}
        statusLabel={vpn.connecting ? `Connecting…` : (vpn.connected ? `Connected ${state.uptime}` : 'Inactive')}
        actionLabel={vpn.connected ? 'Disable' : 'Enable'}
        loading={vpn.connecting}
        onAction={() => dispatch({ type: vpn.connected ? 'disconnectVpn' : 'connectVpn' })}
        onConfigure={() => dispatch({ type: 'nav', to: 'vpnConfig' })}
      />

      <HomeModeCard
        title="Network diagnostic"
        primaryLabel={diagnostic.running ? `Stage ${diagnostic.stage} of 4 — Testing TCP` : 'No analysis yet'}
        status={diagnostic.running ? 'warning' : 'idle'}
        statusLabel={diagnostic.running ? 'Running' : 'Inactive'}
        actionLabel="Run scan"
        loading={diagnostic.running}
        onAction={() => dispatch({ type: 'runDiagnostic' })}
        onConfigure={() => dispatch({ type: 'nav', to: 'diagnostics' })}
      />

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 4 }}>
        <MetricPill label={`RTT ${state.rtt} ms`} />
        <MetricPill label={`UP ${state.uptimePct}%`} tone="success" />
        <MetricPill label="MTU 1500" />
        <MetricPill label={`↓ ${state.rx}`} />
        <MetricPill label={`↑ ${state.tx}`} />
      </div>
    </div>
  );
}

function HomeModeCard({ title, primaryLabel, secondaryLabel, status, statusLabel, actionLabel, loading, onAction, onConfigure }) {
  const variant = status === 'active' ? 'tonal' : (loading ? 'elevated' : 'outlined');
  return (
    <RipDpiCard variant={variant}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ font: `700 14px/20px ${RDP.type.sans}` }}>{title}</span>
        <StatusIndicator label={statusLabel} tone={status} />
      </div>
      <div style={{ font: `500 14px/20px ${RDP.type.sans}` }}>{primaryLabel}</div>
      {secondaryLabel && (
        <div style={{ font: `400 12px/16px ${RDP.type.mono}`, color: '#575757' }}>{secondaryLabel}</div>
      )}
      <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
        <RipDpiButton text={actionLabel} loading={loading} onClick={onAction} style={{ flex: 1 }}/>
        <RipDpiButton text="Configure" variant="outline" onClick={onConfigure} style={{ flex: 1 }}/>
      </div>
    </RipDpiCard>
  );
}

// ─────────────────────────────────────────────────────────────
// DiagnosticsScreen — DiagnosticsScreen.kt
// ─────────────────────────────────────────────────────────────
function DiagnosticsScreen({ state, dispatch }) {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: '0 20px 24px' }}>
      <ScreenHeader title="Diagnostics" onBack={() => dispatch({ type: 'nav', to: 'home' })}/>

      <RipDpiCard variant="tonal">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ font: `700 14px/20px ${RDP.type.sans}` }}>Network health</span>
          <StatusIndicator label="HEALTHY" tone="active" />
        </div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 4 }}>
          <MetricPill label="DNS  12 ms" tone="success" />
          <MetricPill label="TCP  18 ms" tone="success" />
          <MetricPill label="TLS  104 ms" tone="warning" />
          <MetricPill label="QUIC blocked" tone="danger" />
        </div>
      </RipDpiCard>

      <SectionHeader>DPI probe</SectionHeader>
      <RipDpiCard>
        <SettingsRow title="cloudflare-dns.com" subtitle="HTTP/2" value="403"
          trailing={<StatusIndicator label="" tone="error"/>} />
        <Hairline />
        <SettingsRow title="adblock.dns.adguard.com" subtitle="DoH" value="200 OK"
          trailing={<StatusIndicator label="" tone="active"/>} />
        <Hairline />
        <SettingsRow title="quad9.dns.net" subtitle="DoT" value="timeout"
          trailing={<StatusIndicator label="" tone="warning"/>} />
      </RipDpiCard>

      <SectionHeader>Tools</SectionHeader>
      <RipDpiCard>
        <SettingsRow title="Strategy probe suite" subtitle="Try 22 known strategies in sequence"
          trailing={<Chev/>} onClick={() => {}} />
        <Hairline />
        <SettingsRow title="Pluggable transport probe" subtitle="obfs4 · meek · webtunnel"
          trailing={<Chev/>} onClick={() => {}} />
        <Hairline />
        <SettingsRow title="CIDR whitelist" subtitle="ASN-scoped routing test"
          trailing={<Chev/>} onClick={() => {}} />
      </RipDpiCard>

      <div style={{ display: 'flex', gap: 8 }}>
        <RipDpiButton text="Re-run scan" onClick={() => dispatch({ type: 'runDiagnostic' })} style={{ flex: 1 }}/>
        <RipDpiButton text="Share report" variant="outline" style={{ flex: 1 }}/>
      </div>
    </div>
  );
}

function Chev() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <polyline points="9 6 15 12 9 18"/>
    </svg>
  );
}

// ─────────────────────────────────────────────────────────────
// StrategyConfigScreen — settings/StrategyConfigScreen.kt
// ─────────────────────────────────────────────────────────────
function StrategyConfigScreen({ state, dispatch }) {
  const { c } = useTheme();
  const [strategy, setStrategy] = React.useState(state.strategy);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: '0 20px 24px' }}>
      <ScreenHeader title="Strategy" onBack={() => dispatch({ type: 'nav', to: 'home' })}/>

      <WarningBanner
        title="Manual step required"
        message="Some strategies need root or a working DoH resolver. Tap each chip to read what it does."
        tone="info"
      />

      <SectionHeader>Preset</SectionHeader>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {['tlsrec_split_host','desync_fake','fragment_drop','host_fake','seq_overlap'].map(p => (
          <RipDpiChip key={p} text={p} selected={p === strategy} onClick={() => setStrategy(p)} />
        ))}
      </div>

      <SectionHeader>Configuration</SectionHeader>
      <RipDpiCard>
        <RipDpiTextField label="Server" value={state.server}
          onChange={v => dispatch({ type: 'set', key: 'server', value: v })}
          helperText="IPv4, IPv6, or hostname. Port optional, defaults to 443."/>
        <div style={{ height: 12 }}/>
        <RipDpiTextField label="Strategy args" value={state.args}
          onChange={v => dispatch({ type: 'set', key: 'args', value: v })}
          helperText="Space-separated flags. Use `man rosa` for reference."/>
      </RipDpiCard>

      <SectionHeader>Behavior</SectionHeader>
      <RipDpiCard>
        <RipDpiSwitch checked={state.autoReconnect}
          onChange={v => dispatch({ type: 'set', key: 'autoReconnect', value: v })}
          label="Auto-reconnect" helperText="Restart VPN when the network changes"/>
        <Hairline />
        <RipDpiSwitch checked={state.recordPcap}
          onChange={v => dispatch({ type: 'set', key: 'recordPcap', value: v })}
          label="Record PCAP" helperText="Capture packets while diagnostic runs"/>
        <Hairline />
        <RipDpiSwitch checked={false} enabled={false}
          label="Bypass IPv6" helperText="Requires command-line mode"/>
      </RipDpiCard>

      <div style={{ display: 'flex', gap: 8 }}>
        <RipDpiButton text="Apply strategy"
          onClick={() => dispatch({ type: 'applyStrategy', strategy })}
          style={{ flex: 1 }} />
        <RipDpiButton text="Reset to defaults" variant="ghost" style={{ flex: 1 }}/>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// VpnConfigScreen — config/VpnConfigScreen.kt
// ─────────────────────────────────────────────────────────────
function VpnConfigScreen({ state, dispatch }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: '0 20px 24px' }}>
      <ScreenHeader title="VPN configuration" onBack={() => dispatch({ type: 'nav', to: 'home' })}/>

      <SectionHeader>Remote relay</SectionHeader>
      <RipDpiCard>
        <RipDpiTextField label="Host" value="relay.example" onChange={()=>{}}/>
        <div style={{ height: 12 }}/>
        <RipDpiTextField label="Port" value="443" onChange={()=>{}}/>
        <div style={{ height: 12 }}/>
        <RipDpiTextField label="Public key" value="cLmZJyR8…7v8mPb=" onChange={()=>{}}/>
      </RipDpiCard>

      <SectionHeader>Transport</SectionHeader>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {['AmneziaWG','OpenVPN','WireGuard','Outline'].map((t,i) => (
          <RipDpiChip key={t} text={t} selected={i === 0} onClick={()=>{}}/>
        ))}
      </div>

      <SectionHeader>Routing</SectionHeader>
      <RipDpiCard>
        <SettingsRow title="Split tunnel" subtitle="Per-app routing rules" value="3 apps"
          trailing={<Chev/>} onClick={()=>{}}/>
        <Hairline/>
        <SettingsRow title="CIDR whitelist" subtitle="Bypass corporate ranges" value="0 ranges"
          trailing={<Chev/>} onClick={()=>{}}/>
        <Hairline/>
        <SettingsRow title="DNS resolver" value="AdGuard DoH"
          trailing={<Chev/>} onClick={()=>{}}/>
      </RipDpiCard>

      <RipDpiButton text="Save profile" onClick={() => dispatch({ type: 'nav', to: 'home' })}/>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// LogsScreen — logs/LogsScreen.kt
// ─────────────────────────────────────────────────────────────
function LogsScreen({ state, dispatch }) {
  const { c } = useTheme();
  const [filter, setFilter] = React.useState('all');
  const rows = LOG_ROWS.filter(r => filter === 'all' || r.lvl.toLowerCase() === filter);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: '0 20px 24px' }}>
      <ScreenHeader title="Logs" onBack={() => dispatch({ type: 'nav', to: 'home' })}/>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {['all','info','warn','error'].map(f => (
          <RipDpiChip key={f} text={f.toUpperCase()} selected={f === filter} onClick={() => setFilter(f)}/>
        ))}
      </div>

      <RipDpiCard variant="elevated" style={{ padding: 0 }}>
        <div style={{ padding: 12 }}>
          {rows.map((r, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '120px 60px 1fr', gap: 12, padding: '4px 0', font: `400 12px/20px ${RDP.type.mono}` }}>
              <span style={{ color: c.mutedForeground }}>{r.ts}</span>
              <span style={{ color: r.lvl === 'WARN' ? c.warning : r.lvl === 'ERROR' ? c.destructive : c.foreground }}>{r.lvl}</span>
              <span style={{ color: c.foreground }}>{r.msg}</span>
            </div>
          ))}
        </div>
      </RipDpiCard>

      <div style={{ display: 'flex', gap: 8 }}>
        <RipDpiButton text="Copy" variant="outline" style={{ flex: 1 }}/>
        <RipDpiButton text="Export PCAP" variant="outline" style={{ flex: 1 }}/>
        <RipDpiButton text="Reset cache" variant="destructive" style={{ flex: 1 }}/>
      </div>
    </div>
  );
}

const LOG_ROWS = [
  { ts: '12:18:42.013', lvl: 'INFO',  msg: 'strategy.applied  tlsrec_split_host' },
  { ts: '12:18:42.087', lvl: 'INFO',  msg: 'vpn.start  iface=tun0 mtu=1500' },
  { ts: '12:18:42.211', lvl: 'INFO',  msg: 'dns.resolve  cloudflare-dns.com → 1.1.1.1' },
  { ts: '12:18:42.804', lvl: 'WARN',  msg: 'probe.timeout  host=cloudflare-dns.com' },
  { ts: '12:18:42.910', lvl: 'INFO',  msg: 'fragment.send  count=8 bytes=1392' },
  { ts: '12:18:43.005', lvl: 'INFO',  msg: 'tls.handshake  alpn=h2 cipher=TLS_AES_256_GCM' },
  { ts: '12:18:43.118', lvl: 'ERROR', msg: 'quic.blocked   reason=fingerprint host=youtube.com' },
  { ts: '12:18:43.402', lvl: 'INFO',  msg: 'fallback.tcp   strategy=fragment_drop' },
  { ts: '12:18:43.518', lvl: 'INFO',  msg: 'route.add      10.0.0.0/8 dev tun0' },
];

// ─────────────────────────────────────────────────────────────
// Onboarding (single page sample)
// ─────────────────────────────────────────────────────────────
function OnboardingScreen({ dispatch }) {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24, padding: '24px 20px', minHeight: '100%' }}>
      <div style={{ width: 88, height: 88, borderRadius: 22, background: c.muted, border: `1px solid ${c.cardBorder}`, padding: 6, alignSelf: 'flex-start' }}>
        <img src="../../assets/icons/ic_launcher_foreground_ripdpi_clean.svg" style={{ width: '100%', height: '100%', objectFit: 'contain' }}/>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <h1 style={{ font: `500 22px/30px ${RDP.type.sans}`, margin: 0 }}>Probe. Then connect.</h1>
        <p style={{ font: `400 15px/22px ${RDP.type.sans}`, color: c.mutedForeground, margin: 0 }}>
          RIPDPI tests how the local network treats your traffic, then applies the strategy most likely to work.
          The diagnostic runs in 4 stages — DNS, TCP, TLS, QUIC.
        </p>
      </div>

      <RipDpiCard>
        <SettingsRow title="Step 1 · VPN permission" subtitle="Required to install the tunnel interface"
          value="Granted" trailing={<StatusIndicator label="" tone="active"/>}/>
        <Hairline/>
        <SettingsRow title="Step 2 · Ignore battery optimization" subtitle="Keeps the service running in background"
          value="Pending" trailing={<StatusIndicator label="" tone="warning"/>}/>
        <Hairline/>
        <SettingsRow title="Step 3 · Choose strategy" subtitle="Defaults work for most networks"
          value="Set later" trailing={<StatusIndicator label="" tone="idle"/>}/>
      </RipDpiCard>

      <div style={{ flex: 1 }}/>
      <RipDpiButton text="Continue" onClick={() => dispatch({ type: 'nav', to: 'home' })}/>
    </div>
  );
}

function ScreenHeader({ title, onBack }) {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, minHeight: 56, paddingTop: 16 }}>
      <RipDpiIconButton size={40} variant="ghost"
        icon={<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><polyline points="15 6 9 12 15 18"/></svg>}
        onClick={onBack}/>
      <h1 style={{ font: `500 20px/28px ${RDP.type.sans}`, color: c.foreground, margin: 0 }}>{title}</h1>
    </div>
  );
}

Object.assign(window, {
  HomeScreen, DiagnosticsScreen, StrategyConfigScreen, VpnConfigScreen,
  LogsScreen, OnboardingScreen, ScreenHeader, HomeModeCard, Chev,
});
