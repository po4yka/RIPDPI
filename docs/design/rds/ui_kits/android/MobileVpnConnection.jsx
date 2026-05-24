/* ui_kits/android/MobileVpnConnection.jsx
   Mobile adoption of the 6 VPN connection-state preview elements.
   Composed from existing RipDpi kit primitives (RipDpiCard, MetricPill,
   SectionHeader, RipDpiButton, WarningBanner) plus view-specific bits.

   Source elements:
     preview/vpn-state-machine.html
     preview/vpn-handshake-timeline.html
     preview/vpn-session-card.html
     preview/vpn-reconnect-toast.html
     preview/vpn-degradation-strip.html
     preview/vpn-state-history.html
*/

// ─────────────────────────────────────────────────────────────
// 1. State machine — vertical lifecycle flow with current ring
// ─────────────────────────────────────────────────────────────
function MobileStateMachine() {
  const { c } = useTheme();
  // Linear traversal of the state machine; same set of states as the
  // desktop diagram but expressed as a top-to-bottom lifecycle, which is
  // the natural mobile shape. Current state ringed; degraded / recover
  // pair shown as a two-arrow bidirectional row.
  const steps = [
    { id: 'disc',  name: 'Disconnected',  meta: 'idle',          tone: 'idle',  trigger: 'tap connect' },
    { id: 'perm',  name: 'Permissioning', meta: 'os prompt',     tone: 'conn',  trigger: 'allowed' },
    { id: 'conn',  name: 'Connecting',    meta: 'handshake',     tone: 'conn',  trigger: 'handshake ok', active: true,
      branch: { name: 'Failed', meta: 'timeout 8 s', tone: 'fail' } },
    { id: 'tunl',  name: 'Tunneling',     meta: '12 m 14 s',     tone: 'tunl',  current: true,
      pair: { downLabel: 'loss > 3% · 30 s', upLabel: 'loss < 0.5%' },
      trigger: null /* see pair handling below */ },
    { id: 'degr',  name: 'Degraded',      meta: '2 in 24 h',     tone: 'degr',  trigger: 'network change' },
    { id: 'recon', name: 'Reconnecting',  meta: 'backoff',       tone: 'recon', trigger: 're-handshake → Tunneling',
      branch: { name: 'Failed', meta: '3 attempts', tone: 'fail',
                next: { name: '(Disconnected)', meta: 'dismiss', tone: 'idle' } } },
  ];

  const toneStyle = (tone) => {
    switch (tone) {
      case 'tunl':  return { bg: c.successContainer,     border: c.success,     fg: c.successContainerFg,     dot: c.success,           pulse: true };
      case 'degr':  return { bg: c.warningContainer,     border: c.warning,     fg: c.warningContainerFg,     dot: c.warning };
      case 'recon': return { bg: c.infoContainer,        border: c.info,        fg: c.infoContainerFg,        dot: c.info };
      case 'fail':  return { bg: c.destructiveContainer, border: c.destructive, fg: c.destructiveContainerFg, dot: c.destructive };
      case 'conn':  return { bg: c.card,                 border: c.border,      fg: c.foreground,             dot: c.info };
      default:      return { bg: c.card,                 border: c.border,      fg: c.foreground,             dot: c.mutedForeground };
    }
  };

  const StateRow = ({ step, indent = 0, branchTone = null }) => {
    const t = toneStyle(step.tone);
    return (
      <div style={{ display: 'flex', alignItems: 'stretch', gap: 10, marginLeft: indent }}>
        {/* rail spine */}
        <div style={{ width: 18, display: 'flex', flexDirection: 'column', alignItems: 'center', flexShrink: 0 }}>
          <div style={{ width: 10, height: 10, borderRadius: 9999, background: t.dot,
                        boxShadow: t.pulse ? `0 0 0 3px ${c.card}, 0 0 0 4px ${t.border}` : 'none',
                        animation: t.pulse ? 'rdpPulse 1.6s ease-in-out infinite' : 'none' }}/>
        </div>
        {/* state pill */}
        <div style={{
          flex: 1,
          background: t.bg,
          border: `1px solid ${t.border}`,
          borderRadius: 10,
          padding: '10px 12px',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8,
          position: 'relative',
          outline: step.current ? `1.5px solid ${c.foreground}` : 'none',
          outlineOffset: step.current ? 3 : 0,
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0 }}>
            <span style={{ font: `500 13px/16px ${RDP.type.mono}`, color: t.fg, letterSpacing: 0.2 }}>{step.name}</span>
            <span style={{ font: `9.5px ${RDP.type.mono}`, color: c.mutedForeground, letterSpacing: 0.4, textTransform: 'uppercase' }}>{step.meta}</span>
          </div>
          {step.current && (
            <span style={{ font: `9px ${RDP.type.mono}`, padding: '2px 6px', borderRadius: 3, background: c.foreground, color: c.background, letterSpacing: 0.5, flexShrink: 0 }}>CURRENT</span>
          )}
        </div>
      </div>
    );
  };

  const Connector = ({ label, branchLabel, dashed = false, color = c.outlineVariant }) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginLeft: 0 }}>
      <div style={{ width: 18, display: 'flex', justifyContent: 'center', flexShrink: 0 }}>
        <div style={{ width: 2, height: 22, background: dashed
          ? `repeating-linear-gradient(to bottom, ${color} 0 3px, transparent 3px 6px)`
          : color }}/>
      </div>
      <div style={{ font: `9.5px ${RDP.type.mono}`, color: c.mutedForeground, letterSpacing: 0.3, padding: '4px 0', flex: 1 }}>
        {label && <span>↓ {label}</span>}
        {branchLabel && (
          <span style={{ marginLeft: 14, color: c.destructive }}>↘ {branchLabel}</span>
        )}
      </div>
    </div>
  );

  return (
    <RipDpiCard>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <span style={{ font: `500 14px/20px ${RDP.type.sans}` }}>Connection lifecycle</span>
        <span style={{ font: `400 11px/14px ${RDP.type.mono}`, color: c.mutedForeground }}>9 transitions / 24 h</span>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {/* Disconnected */}
        <StateRow step={steps[0]}/>
        <Connector label={steps[0].trigger}/>

        {/* Permissioning */}
        <StateRow step={steps[1]}/>
        <Connector label={steps[1].trigger}/>

        {/* Connecting (branches to Failed on timeout) */}
        <StateRow step={steps[2]}/>
        <Connector label={steps[2].trigger} branchLabel={steps[2].branch.meta} dashed/>

        {/* Tunneling — current */}
        <StateRow step={steps[3]}/>
        {/* Tunneling ↔ Degraded — bidirectional row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ width: 18, display: 'flex', justifyContent: 'space-between', flexShrink: 0, padding: '6px 4px' }}>
            <span style={{ font: `12px ${RDP.type.mono}`, color: c.warning }}>↓</span>
            <span style={{ font: `12px ${RDP.type.mono}`, color: c.success }}>↑</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', flex: 1, font: `9.5px ${RDP.type.mono}`, color: c.mutedForeground, letterSpacing: 0.3 }}>
            <span>{steps[3].pair.downLabel}</span>
            <span>{steps[3].pair.upLabel}</span>
          </div>
        </div>

        {/* Degraded */}
        <StateRow step={steps[4]}/>
        <Connector label={steps[4].trigger}/>

        {/* Reconnecting (branches to Failed) */}
        <StateRow step={steps[5]}/>
        <Connector label="re-handshake → Tunneling" branchLabel="3 attempts" dashed/>

        {/* Failed (terminal branch) */}
        <StateRow step={{ name: 'Failed', meta: 'last 6 h: 0', tone: 'fail' }} indent={20}/>
        <div style={{ marginLeft: 20 }}>
          <Connector label="dismiss"/>
        </div>
        <StateRow step={{ name: '(Disconnected)', meta: 'loops back', tone: 'idle' }} indent={20}/>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, paddingTop: 10, borderTop: `0.5px solid ${c.divider}`, marginTop: 4, font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground }}>
        {[
          ['Idle',      c.card,                 c.border],
          ['Transition',c.infoContainer,        c.info],
          ['Healthy',   c.successContainer,     c.success],
          ['Degraded',  c.warningContainer,     c.warning],
          ['Failed',    c.destructiveContainer, c.destructive],
        ].map(([nm, bg, br]) => (
          <span key={nm} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 10, height: 10, borderRadius: 3, background: bg, border: `1px solid ${br}` }}/>{nm}
          </span>
        ))}
      </div>
    </RipDpiCard>
  );
}

// ─────────────────────────────────────────────────────────────
// 2. Handshake timeline — compact Gantt
// ─────────────────────────────────────────────────────────────
function MobileHandshakeTimeline() {
  const { c } = useTheme();
  const rows = [
    { name: 'DNS',             sub: 'cloudflare',           left: 0,    width: 1.6,  dur: '24 ms',  tone: 'ok' },
    { name: 'TCP / QUIC init', sub: 'UDP 443 · I_INITIAL',  left: 1.6,  width: 3.0,  dur: '45 ms',  tone: 'info' },
    { name: 'TLS ClientHello', sub: 'ECH outer · grease',   left: 4.6,  width: 12.8, dur: '192 ms', tone: 'info' },
    { name: 'Key exchange',    sub: 'X25519 · Noise IK',    left: 17.4, width: 7.6,  dur: '114 ms', tone: 'info' },
    { name: 'Auth',            sub: 'PSK + device cert',    left: 25.0, width: 5.4,  dur: '81 ms',  tone: 'info' },
    { name: 'MTU probe',       sub: '576 → 1380 (DF set)',  left: 30.4, width: 28.2, dur: '423 ms', tone: 'warn' },
    { name: 'Route install',   sub: 'VpnService.Builder',   left: 58.6, width: 4.6,  dur: '69 ms',  tone: 'ok' },
    { name: 'First packet',    sub: 'echo · 1 RTT',         left: 63.2, width: 3.6,  dur: '54 ms',  tone: 'ok' },
    { name: 'Keepalive',       sub: 'queued · t+25 s',      left: 66.8, width: 33.2, dur: '—',      tone: 'queued', dim: true },
  ];
  const barColor = (tone) => tone === 'ok' ? c.success : tone === 'info' ? c.info : tone === 'warn' ? c.warning : tone === 'bad' ? c.destructive : null;
  const NOW = 66.8;

  return (
    <RipDpiCard>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end' }}>
        <div>
          <div style={{ font: `500 14px/18px ${RDP.type.sans}` }}>Handshake timeline</div>
          <div style={{ font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground, marginTop: 2 }}>cloudflare-dns.com · attempt 1/3</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ font: `500 22px/24px ${RDP.type.mono}`, fontVariantNumeric: 'tabular-nums' }}>1.42<span style={{ font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground, marginLeft: 3 }}>s</span></div>
          <div style={{ font: `9.5px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground, marginTop: 2 }}>1st packet</div>
        </div>
      </div>

      {/* Axis */}
      <div style={{ position: 'relative', height: 16, marginTop: 6 }}>
        {[0, 500, 1000, 1500].map((ms, i) => (
          <span key={ms} style={{ position: 'absolute', left: `${(ms / 1500) * 100}%`, transform: 'translateX(-50%)', font: `9.5px ${RDP.type.mono}`, color: c.mutedForeground, fontVariantNumeric: 'tabular-nums' }}>
            <span style={{ position: 'absolute', left: '50%', bottom: -4, transform: 'translateX(-50%)', width: 1, height: 4, background: c.outlineVariant }}/>
            {ms}{i === 3 ? ' ms' : ''}
          </span>
        ))}
      </div>

      {/* Rows: compact two-line — label on top, bar + duration on bottom */}
      <div style={{ position: 'relative' }}>
        {rows.map((r, i) => (
          <div key={r.name} style={{
            display: 'grid', gridTemplateColumns: '1fr 60px', gap: 10, alignItems: 'center',
            padding: '8px 0', borderTop: i === 0 ? `0.5px solid ${c.divider}` : `0.5px solid ${c.divider}`,
            opacity: r.dim ? 0.6 : 1,
          }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 6 }}>
                <span style={{ font: `12px ${RDP.type.mono}`, color: c.foreground, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.name}</span>
                <span style={{ font: `9.5px ${RDP.type.mono}`, color: c.mutedForeground, flexShrink: 0 }}>{r.sub}</span>
              </div>
              <div style={{ position: 'relative', height: 10, background: c.muted, borderRadius: 2 }}>
                {r.tone === 'queued' ? (
                  <span style={{ position: 'absolute', left: `${r.left}%`, width: `${r.width}%`, top: 0, bottom: 0, background: `repeating-linear-gradient(135deg, ${c.outlineVariant} 0 3px, transparent 3px 6px)`, borderRadius: 2 }}/>
                ) : (
                  <span style={{ position: 'absolute', left: `${r.left}%`, width: `${r.width}%`, top: 0, bottom: 0, background: barColor(r.tone), borderRadius: 2 }}/>
                )}
              </div>
            </div>
            <span style={{ font: `500 11.5px/14px ${RDP.type.mono}`,
                           color: r.tone === 'warn' ? c.warning : r.tone === 'bad' ? c.destructive : c.foreground,
                           fontVariantNumeric: 'tabular-nums', textAlign: 'right' }}>{r.dur}</span>
          </div>
        ))}

        {/* NOW marker — pinned to the bar column. The grid is 1fr | 60px with
            10 px gap, so the bar column ends at: full width - 60 px - 10 px.
            NOW lives at `left: NOW% of bar-column`. */}
        <div style={{ position: 'absolute', top: 0, bottom: 0, left: 0, right: 70 /* 60px col + 10px gap */, pointerEvents: 'none' }}>
          <span style={{ position: 'absolute', top: 8, bottom: 8, left: `${NOW}%`, width: 1, background: c.foreground }}>
            <span style={{ position: 'absolute', top: -8, left: '50%', transform: 'translate(-50%, -100%)', font: `8.5px ${RDP.type.mono}`, letterSpacing: 0.5, background: c.foreground, color: c.background, padding: '1px 4px', borderRadius: 2 }}>NOW</span>
          </span>
        </div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground, paddingTop: 8, borderTop: `0.5px solid ${c.divider}` }}>
        <span>Slowest <span style={{ color: c.foreground }}>MTU probe</span></span>
        <span>Budget <span style={{ color: c.foreground }}>2.0 s</span></span>
      </div>
    </RipDpiCard>
  );
}

// ─────────────────────────────────────────────────────────────
// 3. Session card — currently connected
// ─────────────────────────────────────────────────────────────
function MobileSessionCard() {
  const { c } = useTheme();
  return (
    <RipDpiCard variant="status">
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ position: 'relative', width: 8, height: 8, borderRadius: 9999, background: c.success }}>
          <span style={{ position: 'absolute', inset: -3, borderRadius: 9999, border: `1.5px solid ${c.success}`, opacity: 0.35, animation: 'rdpRing 1.6s ease-out infinite' }}/>
        </span>
        <span style={{ font: `10.5px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground }}>Connected · Tunneling</span>
      </div>

      <div style={{ font: `500 34px/38px ${RDP.type.mono}`, fontVariantNumeric: 'tabular-nums' }}>00:12:14</div>
      <div style={{ font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground, marginTop: -4 }}>Since 18:30 UTC</div>

      {/* Exit row */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, padding: '10px 0', borderTop: `0.5px solid ${c.divider}`, borderBottom: `0.5px solid ${c.divider}` }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <span style={{ width: 22, height: 16, background: 'linear-gradient(to bottom, #AE1C28 0 33%, #FFFFFF 33% 66%, #21468B 66% 100%)', border: `0.5px solid ${c.outlineVariant}`, borderRadius: 2, flexShrink: 0 }}/>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0 }}>
            <span style={{ font: `500 13px/16px ${RDP.type.sans}`, whiteSpace: 'nowrap' }}>AMS · Amsterdam</span>
            <span style={{ font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>AS13335 · Cloudflare</span>
          </div>
        </div>
        <span style={{ font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground, flexShrink: 0 }}>141.101.65.232</span>
      </div>

      {/* Sparklines */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Spark dir="in"  v="2.4" unit="MB/s" color={c.success}
               path="M0,28 L20,22 L40,24 L60,18 L80,20 L100,14 L120,16 L140,10 L160,12 L180,8 L200,12 L220,6 L240,10 L260,4 L280,8 L300,5 L320,3" muted={c.muted}/>
        <Spark dir="out" v="340" unit="KB/s" color={c.info}
               path="M0,30 L20,28 L40,30 L60,26 L80,30 L100,28 L120,30 L140,26 L160,28 L180,30 L200,28 L220,26 L240,30 L260,28 L280,30 L300,26 L320,28" muted={c.muted}/>
      </div>

      {/* KPI row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 0, paddingTop: 8, borderTop: `0.5px solid ${c.divider}` }}>
        {[
          ['Sent',     '128 MiB', c.foreground],
          ['Received', '1.4 GiB', c.foreground],
          ['RTT p50',  '32 ms',   c.foreground],
          ['Loss',     '0.2%',    c.success],
        ].map(([k, v, col], i) => (
          <div key={k} style={{ padding: '0 8px', borderRight: i < 3 ? `0.5px solid ${c.divider}` : 'none', display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span style={{ font: `9.5px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground }}>{k}</span>
            <span style={{ font: `500 13px/16px ${RDP.type.mono}`, color: col, fontVariantNumeric: 'tabular-nums' }}>{v}</span>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', paddingTop: 8, borderTop: `0.5px solid ${c.divider}`, flexWrap: 'wrap' }}>
        <span style={{ flex: 1, font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          <code style={{ padding: '1px 5px', background: c.muted, borderRadius: 3, color: c.foreground }}>tlsrec_split_host</code>
        </span>
        <RipDpiButton text="Pause 5 m" variant="outline" style={{ minHeight: 36, padding: '6px 12px', fontSize: 12 }}/>
        <RipDpiButton text="Disconnect" variant="outline" style={{ minHeight: 36, padding: '6px 12px', fontSize: 12, color: c.destructive, borderColor: c.destructive }}/>
      </div>
    </RipDpiCard>
  );
}

function Spark({ dir, v, unit, color, path, muted }) {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <span style={{ font: `9.5px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground }}>{dir === 'in' ? '↓ Down' : '↑ Up'}</span>
        <span style={{ font: `500 14px/1 ${RDP.type.mono}`, fontVariantNumeric: 'tabular-nums' }}>{v}<span style={{ font: `10px ${RDP.type.mono}`, color: c.mutedForeground, marginLeft: 3 }}>{unit}</span></span>
      </div>
      <svg viewBox="0 0 320 36" preserveAspectRatio="none" style={{ width: '100%', height: 30 }}>
        <path d={`${path} L320,36 L0,36 Z`} fill={color} opacity="0.12"/>
        <path d={path} fill="none" stroke={color} strokeWidth="1.5"/>
      </svg>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// 4. Reconnect toast — 3 variants stacked
// ─────────────────────────────────────────────────────────────
function MobileReconnectToast() {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Toast tone="primary">
        <ToastIcon spin tone="primary">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0114.85-3.36L23 10"/><path d="M20.49 15A9 9 0 015.64 18.36L1 14"/></svg>
        </ToastIcon>
        <ToastBody title="Reconnecting…" desc={<>Wi-Fi → mobile · attempt <b>2 of 3</b></>} />
        <ToastActions tone="primary">
          <ToastCount text="00:03"/>
          <ToastBtn>Cancel</ToastBtn>
          <ToastBtn solid>Retry</ToastBtn>
        </ToastActions>
        <ToastCountdown/>
      </Toast>

      <Toast tone="bad">
        <ToastIcon tone="bad">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </ToastIcon>
        <ToastBody title="Could not reconnect" desc={<>3 attempts failed · <b>MTU probe timeout</b></>} />
        <ToastActions tone="bad">
          <ToastBtn>Diagnose</ToastBtn>
          <ToastBtn solid>Switch strategy</ToastBtn>
        </ToastActions>
      </Toast>

      <Toast tone="ok">
        <ToastIcon tone="ok">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
        </ToastIcon>
        <ToastBody title="Tunnel restored" desc={<>Recovered in <b>4.8 s</b> · no kill-switch trip</>}/>
        <ToastActions tone="ok">
          <ToastBtn>Dismiss</ToastBtn>
        </ToastActions>
      </Toast>
    </div>
  );
}

const ToastToneCtx = React.createContext('primary');

function Toast({ tone, children }) {
  const { c } = useTheme();
  const map = {
    primary: { bg: c.foreground,           fg: c.background },
    bad:     { bg: c.destructiveContainer, fg: c.destructiveContainerFg },
    ok:      { bg: c.successContainer,     fg: c.successContainerFg },
  };
  const { bg, fg } = map[tone];
  return (
    <ToastToneCtx.Provider value={tone}>
      <div style={{
        background: bg, color: fg, borderRadius: 14,
        padding: '12px 14px 10px',
        display: 'grid', gridTemplateColumns: '32px 1fr', gridTemplateRows: 'auto auto', columnGap: 12, rowGap: 6,
        position: 'relative',
        boxShadow: tone === 'primary' ? '0 8px 24px rgba(0,0,0,0.18)' : 'none',
      }}>
        {children}
      </div>
    </ToastToneCtx.Provider>
  );
}
function ToastIcon({ children, tone, spin }) {
  const { c } = useTheme();
  const innerBg = tone === 'primary' ? c.background : tone === 'bad' ? c.destructive : c.success;
  const innerFg = tone === 'primary' ? c.foreground : c.background;
  return (
    <span style={{ width: 32, height: 32, borderRadius: 9999, background: innerBg, color: innerFg, display: 'grid', placeItems: 'center', gridRow: '1', position: 'relative' }}>
      {spin && <span style={{ position: 'absolute', inset: -2, borderRadius: 9999, border: '2px solid rgba(255,255,255,0.2)', borderTopColor: 'rgba(255,255,255,0.85)', animation: 'rdpSpin 1.2s linear infinite' }}/>}
      {children}
    </span>
  );
}
function ToastBody({ title, desc }) {
  return (
    <div style={{ gridColumn: '2', display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
      <div style={{ font: `500 14px/18px ${RDP.type.sans}` }}>{title}</div>
      <div style={{ font: `400 11px/14px ${RDP.type.mono}`, opacity: 0.78 }}>{desc}</div>
    </div>
  );
}
function ToastActions({ children, tone }) {
  return (
    <div style={{ gridColumn: '2', display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>{children}</div>
  );
}
function ToastBtn({ children, solid }) {
  const { c } = useTheme();
  const tone = React.useContext(ToastToneCtx);

  // Each toast tone needs its own button palette so SOLID has real contrast
  // (the failed toast was rendering destructive-red text on destructive-red
  // background — invisible — when solid was just "currentColor").
  let solidBg, solidFg, outlineBorder;
  if (tone === 'primary') {
    solidBg = c.background;        solidFg = c.foreground;             outlineBorder = 'rgba(255,255,255,0.28)';
  } else if (tone === 'bad') {
    solidBg = c.destructive;       solidFg = c.destructiveForeground || '#FFFFFF'; outlineBorder = 'rgba(0,0,0,0.2)';
  } else if (tone === 'ok') {
    solidBg = c.success;           solidFg = c.successForeground || '#FFFFFF';     outlineBorder = 'rgba(0,0,0,0.2)';
  } else {
    solidBg = c.foreground;        solidFg = c.background;             outlineBorder = 'rgba(0,0,0,0.2)';
  }

  return (
    <button style={{
      background: solid ? solidBg : 'transparent',
      color: solid ? solidFg : 'currentColor',
      border: `1px solid ${solid ? solidBg : outlineBorder}`,
      padding: '5px 10px', borderRadius: 9999,
      font: `10px ${RDP.type.mono}`, letterSpacing: 0.3, textTransform: 'uppercase',
      cursor: 'pointer', whiteSpace: 'nowrap',
    }}>{children}</button>
  );
}
function ToastCount({ text }) { return <span style={{ font: `500 12px ${RDP.type.mono}`, marginRight: 'auto', fontVariantNumeric: 'tabular-nums' }}>{text}</span>; }
function ToastCountdown() {
  return (
    <span style={{ position: 'absolute', left: 14, right: 14, bottom: 4, height: 2, background: 'rgba(255,255,255,0.14)', borderRadius: 2, overflow: 'hidden' }}>
      <span style={{ display: 'block', height: '100%', background: 'rgba(255,255,255,0.85)', width: '100%', transformOrigin: 'right center', animation: 'rdpCountdown 4s linear forwards' }}/>
    </span>
  );
}

// ─────────────────────────────────────────────────────────────
// 5. Degradation strip — warn + critical variants
// ─────────────────────────────────────────────────────────────
function MobileDegradationStrip() {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Strip tone="warn"
        title="Quality degraded · still connected"
        desc="Three indicators outside their normal range; SNI handshakes slower and loss rising."
        chips={[
          ['Loss',    '4.1%',   '↑ +3.2 pp',   c.destructive],
          ['RTT p50', '168 ms', '↑ +44 ms',    c.destructive],
          ['Jitter',  '22 ms',  '↑ +18 ms',    c.destructive],
        ]}
        actions={['Switch relay', 'Re-probe']}
        since="3 m 12 s · since 18:39 UTC"/>

      <Strip tone="bad"
        title="Tunnel about to drop"
        desc="Loss above kill-switch threshold for 30 s. Tunnel tears down unless a new strategy is applied."
        chips={[
          ['Loss',      '21.4%',     '↑ threshold 10%', c.destructive],
          ['Last byte', '14 s ago', null,               null],
        ]}
        actions={['Disconnect', 'Find a strategy']}/>
    </div>
  );
}

function Strip({ tone, title, desc, chips, actions, since }) {
  const { c } = useTheme();
  const accent = tone === 'bad' ? c.destructive       : c.warning;
  const bg     = tone === 'bad' ? c.destructiveContainer : c.warningContainer;
  const fg     = tone === 'bad' ? c.destructiveContainerFg : c.warningContainerFg;
  return (
    <div style={{
      background: bg, color: fg,
      borderRadius: 12,
      padding: '14px 14px 12px',
      borderLeft: `4px solid ${accent}`,
      display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
        <span style={{ width: 24, height: 24, borderRadius: 6, background: accent, color: tone === 'bad' ? c.destructiveContainer : c.warningContainerFg, display: 'grid', placeItems: 'center', font: `500 11px/1 ${RDP.type.mono}`, flexShrink: 0 }}>
          {tone === 'bad' ? '×' : '!'}
        </span>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ font: `500 14px/18px ${RDP.type.sans}` }}>{title}</div>
          <div style={{ font: `400 12.5px/18px ${RDP.type.sans}`, marginTop: 3, opacity: 0.88 }}>{desc}</div>
        </div>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {chips.map(([k, v, delta, deltaCol]) => (
          <span key={k} style={{ font: `10.5px ${RDP.type.mono}`, padding: '3px 8px', borderRadius: 9999, background: 'rgba(0,0,0,0.06)', border: '0.5px solid rgba(0,0,0,0.12)', whiteSpace: 'nowrap', display: 'inline-flex', alignItems: 'center', gap: 5, fontVariantNumeric: 'tabular-nums' }}>
            {k} <b style={{ fontWeight: 500 }}>{v}</b>{delta && <span style={{ color: deltaCol }}>{delta}</span>}
          </span>
        ))}
      </div>
      {since && (
        <div style={{ font: `10.5px ${RDP.type.mono}`, opacity: 0.78 }}>Degraded for <b style={{ fontWeight: 500 }}>{since}</b></div>
      )}
      <div style={{ display: 'flex', gap: 6, marginTop: 2 }}>
        <RipDpiButton text={actions[0]} variant="outline" style={{ flex: 1, minHeight: 36, padding: '6px 10px', fontSize: 12, color: fg, borderColor: fg }}/>
        <RipDpiButton text={actions[1]}                       style={{ flex: 1, minHeight: 36, padding: '6px 10px', fontSize: 12, background: fg, color: bg, borderColor: fg }}/>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// 6. State history — 24h ribbon + recent transitions
// ─────────────────────────────────────────────────────────────
function MobileStateHistory() {
  const { c } = useTheme();
  const segs = [
    ['tunl',  91.4],
    ['degr',  4.2],
    ['recon', 2.1],
    ['fail',  1.3],
    ['off',   1.0],
  ];
  const segColor = (k) => k === 'tunl' ? c.success : k === 'degr' ? c.warning : k === 'recon' ? c.info : k === 'fail' ? c.destructive : c.mutedForeground;
  const cells = [
    'tunl','tunl','tunl','tunl','tunl','tunl',
    'off','off',
    'recon','tunl','tunl','tunl','tunl',
    'mixed-degr','degr','fail','recon',
    'tunl','tunl','tunl',
    'mixed-degr','degr','tunl','tunl',
  ];
  const cellBg = (k) => {
    if (k.startsWith('mixed-')) return null;
    return segColor(k);
  };

  const tx = [
    { ts: '18:30', from: 'RECON', to: 'TUNL', reason: 're-handshake · MTU 1380', dur: '4.8 s' },
    { ts: '18:28', from: 'DEGR',  to: 'RECON', reason: 'Wi-Fi → mobile',          dur: '2.1 s' },
    { ts: '14:02', from: 'TUNL',  to: 'FAIL',  reason: 'MTU probe timeout',       dur: '19 m'  },
    { ts: '02:14', from: 'OFF',   to: 'RECON', reason: 'manual connect',          dur: '1.4 s' },
  ];
  const pillStyle = (k) => {
    if (k === 'TUNL')  return { bg: c.successContainer,     fg: c.success };
    if (k === 'DEGR')  return { bg: c.warningContainer,     fg: c.warning };
    if (k === 'RECON') return { bg: c.infoContainer,        fg: c.info };
    if (k === 'FAIL')  return { bg: c.destructiveContainer, fg: c.destructive };
    return                     { bg: c.muted,               fg: c.mutedForeground };
  };

  return (
    <RipDpiCard>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <div>
          <div style={{ font: `500 14px/18px ${RDP.type.sans}` }}>State history</div>
          <div style={{ font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground, marginTop: 2 }}>Last 24 h · <span style={{ color: c.foreground }}>91.4%</span> connected</div>
        </div>
        <div style={{ display: 'inline-flex', gap: 0, border: `0.5px solid ${c.border}`, borderRadius: 9999, overflow: 'hidden' }}>
          {['1 h','6 h','24 h','7 d'].map(r => (
            <span key={r} style={{ padding: '3px 8px', font: `10px ${RDP.type.mono}`, letterSpacing: 0.3, color: r === '24 h' ? c.background : c.mutedForeground, background: r === '24 h' ? c.foreground : 'transparent' }}>{r}</span>
          ))}
        </div>
      </div>

      {/* Stacked summary bar */}
      <div style={{ display: 'flex', height: 8, borderRadius: 4, overflow: 'hidden', background: c.muted, marginTop: 2 }}>
        {segs.map(([k, w]) => (
          <span key={k} style={{ width: `${w}%`, background: segColor(k) }}/>
        ))}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 12px', font: `10.5px ${RDP.type.mono}`, color: c.mutedForeground }}>
        {[
          ['Tunneling',    '21h 56m', 'tunl'],
          ['Degraded',     '1h 1m',   'degr'],
          ['Reconnecting', '30m',     'recon'],
          ['Failed',       '19m',     'fail'],
          ['Off',          '14m',     'off'],
        ].map(([nm, v, k]) => (
          <span key={nm} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: segColor(k), opacity: k === 'off' ? 0.5 : 1 }}/>{nm} <b style={{ fontWeight: 500, color: c.foreground }}>{v}</b>
          </span>
        ))}
      </div>

      {/* Hour ribbon */}
      <div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(24, 1fr)', height: 18, borderRadius: 4, overflow: 'hidden' }}>
          {cells.map((k, i) => {
            if (k.startsWith('mixed-')) {
              const sub = k.split('-')[1];
              return (
                <div key={i} style={{ position: 'relative', background: c.success }}>
                  <div style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: '30%', background: segColor(sub) }}/>
                </div>
              );
            }
            return <div key={i} style={{ background: cellBg(k), opacity: k === 'off' ? 0.3 : 1 }}/>;
          })}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(24, 1fr)', marginTop: 4, font: `9px ${RDP.type.mono}`, color: c.mutedForeground }}>
          {['18','','','','','','00','','','','','','06','','','','','','12','','','','',''].map((h, i) => (
            <span key={i} style={{ textAlign: 'center', color: h ? c.foreground : c.mutedForeground }}>{h}</span>
          ))}
        </div>
      </div>

      {/* Recent transitions */}
      <div style={{ paddingTop: 12, borderTop: `0.5px solid ${c.divider}` }}>
        <div style={{ font: `9.5px ${RDP.type.mono}`, letterSpacing: 0.5, textTransform: 'uppercase', color: c.mutedForeground, marginBottom: 6 }}>Recent · 4 of 11</div>
        {tx.map((t, i) => {
          const from = pillStyle(t.from);
          const to = pillStyle(t.to);
          return (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 0', borderTop: i === 0 ? 'none' : `0.5px solid ${c.divider}`, font: `11px ${RDP.type.mono}` }}>
              <span style={{ color: c.mutedForeground, minWidth: 36, fontVariantNumeric: 'tabular-nums' }}>{t.ts}</span>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
                <span style={{ font: `9.5px ${RDP.type.mono}`, padding: '2px 5px', borderRadius: 3, background: from.bg, color: from.fg, letterSpacing: 0.4 }}>{t.from}</span>
                <span style={{ color: c.mutedForeground }}>→</span>
                <span style={{ font: `9.5px ${RDP.type.mono}`, padding: '2px 5px', borderRadius: 3, background: to.bg, color: to.fg, letterSpacing: 0.4 }}>{t.to}</span>
              </span>
              <span style={{ color: c.mutedForeground, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{t.reason}</span>
              <span style={{ color: c.foreground, fontVariantNumeric: 'tabular-nums', flexShrink: 0 }}>{t.dur}</span>
            </div>
          );
        })}
      </div>
    </RipDpiCard>
  );
}

Object.assign(window, {
  MobileStateMachine, MobileHandshakeTimeline, MobileSessionCard,
  MobileReconnectToast, MobileDegradationStrip, MobileStateHistory,
});
