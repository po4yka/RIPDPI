/* ui_kits/android/MobileElements.jsx
   Mobile adoption of the new component, gesture, and onboarding primitives.
   Composed from existing RipDpi kit primitives where possible.
*/

// ─── Live-state primitives ─────────────────────────────────────
function MHeartbeat({ tone = 'tunl', size = 'md' }) {
  const { c } = useTheme();
  const color = tone === 'tunl' ? c.success : tone === 'degr' ? c.warning : tone === 'fail' ? c.destructive : tone === 'info' ? c.info : c.mutedForeground;
  const dim = size === 'sm' ? 16 : size === 'lg' ? 28 : 20;
  const dot = size === 'sm' ? 6 : size === 'lg' ? 12 : 8;
  const ring = size === 'sm' ? 12 : size === 'lg' ? 20 : 14;
  return (
    <span style={{ position: 'relative', display: 'inline-grid', placeItems: 'center', width: dim, height: dim, verticalAlign: 'middle', color }}>
      <span style={{ width: dot, height: dot, borderRadius: 9999, background: color, display: 'block' }}/>
      {tone !== 'muted' && (
        <span style={{ position: 'absolute', top: '50%', left: '50%', width: ring, height: ring, transform: 'translate(-50%, -50%)', border: `1.5px solid ${color}`, borderRadius: 9999, opacity: 0.35, animation: 'mePulse 1.6s ease-out infinite' }}/>
      )}
    </span>
  );
}

function MLiveCounter({ value, unit }) {
  const { c } = useTheme();
  return (
    <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: 4, fontVariantNumeric: 'tabular-nums' }}>
      <span style={{ font: `500 24px/1 ${RDP.type.mono}`, color: c.foreground }}>{value}</span>
      {unit && <span style={{ font: `400 11px/1 ${RDP.type.mono}`, color: c.mutedForeground }}>{unit}</span>}
    </span>
  );
}

function MStaleBadge({ ageMin }) {
  const { c } = useTheme();
  let tone, label;
  if (ageMin < 0.1) { tone = 'fresh'; label = 'just now'; }
  else if (ageMin < 1) { tone = 'recent'; label = `${Math.floor(ageMin * 60)} s ago`; }
  else if (ageMin < 5) { tone = 'aging'; label = `${Math.floor(ageMin)} m ago`; }
  else if (ageMin < 30) { tone = 'stale'; label = `${Math.floor(ageMin)} m ago`; }
  else { tone = 'expired'; label = `${Math.floor(ageMin / 60)} h ago`; }
  const styles = {
    fresh:   { bg: c.successContainer,     fg: c.success,        dot: c.success,     pulse: true },
    recent:  { bg: c.card,                 fg: c.foreground,     dot: c.mutedForeground, border: c.border },
    aging:   { bg: c.muted,                fg: c.foreground,     dot: c.mutedForeground },
    stale:   { bg: c.warningContainer,     fg: c.warning,        dot: c.warning },
    expired: { bg: c.destructiveContainer, fg: c.destructive,    dot: c.destructive },
  }[tone];
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 9px', borderRadius: 9999, background: styles.bg, color: styles.fg, border: styles.border ? `0.5px solid ${styles.border}` : 'none', font: `400 10.5px/1 ${RDP.type.mono}`, whiteSpace: 'nowrap' }}>
      <span style={{ position: 'relative', display: 'inline-grid', placeItems: 'center', width: 10, height: 10, flexShrink: 0 }}>
        <span style={{ width: 6, height: 6, borderRadius: 9999, background: styles.dot, display: 'block' }}/>
        {styles.pulse && <span style={{ position: 'absolute', top: '50%', left: '50%', width: 10, height: 10, border: `1.5px solid ${styles.dot}`, borderRadius: 9999, opacity: 0.5, animation: 'mePulse 1.6s ease-out infinite' }}/>}
      </span>
      {label}
    </span>
  );
}

function MProgressBar({ value, tone = 'default', indet = false, label, sub }) {
  const { c } = useTheme();
  const fill = tone === 'ok' ? c.success : tone === 'warn' ? c.warning : tone === 'bad' ? c.destructive : c.foreground;
  const pctColor = tone === 'warn' ? c.warning : tone === 'bad' ? c.destructive : c.foreground;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {(label || sub) && (
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
          <span style={{ font: `500 12px/16px ${RDP.type.sans}`, color: c.foreground }}>{label}</span>
          {!indet && <span style={{ font: `500 12px/16px ${RDP.type.mono}`, color: pctColor, fontVariantNumeric: 'tabular-nums' }}>{value}%</span>}
        </div>
      )}
      <div style={{ height: 6, background: c.muted, borderRadius: 3, overflow: 'hidden', position: 'relative' }}>
        {indet ? (
          <span style={{ position: 'absolute', top: 0, bottom: 0, width: '35%', background: c.foreground, borderRadius: 3, animation: 'meIndet 1.4s cubic-bezier(0.4,0,0.6,1) infinite' }}/>
        ) : (
          <span style={{ display: 'block', height: '100%', width: `${value}%`, background: fill, borderRadius: 3, transition: 'width 220ms cubic-bezier(0.2,0,0,1)' }}/>
        )}
      </div>
      {sub && <span style={{ font: `400 10.5px/14px ${RDP.type.mono}`, color: c.mutedForeground }}>{sub}</span>}
    </div>
  );
}

function MShimmer({ height = 12, width = '100%', style }) {
  return (
    <span style={{ display: 'block', height, width, borderRadius: 4, background: 'linear-gradient(90deg, var(--rdp-muted) 0%, var(--rdp-accent) 50%, var(--rdp-muted) 100%)', backgroundSize: '200% 100%', animation: 'meShimmer 1.4s linear infinite', ...style }}/>
  );
}

// ─── Engineering-precision components ──────────────────────────
function MKbd({ children, size = 'md' }) {
  const { c } = useTheme();
  const sz = size === 'sm' ? { fs: 9.5, pad: '2px 5px', mw: 14 } : { fs: 11, pad: '4px 7px', mw: 22 };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', font: `500 ${sz.fs}px/1 ${RDP.type.mono}`, padding: sz.pad, minWidth: sz.mw, borderRadius: 4, background: c.card, border: `0.5px solid ${c.border}`, boxShadow: `0 1px 0 0 ${c.outlineVariant}`, color: c.foreground }}>{children}</span>
  );
}

function MCommandPalette() {
  const { c } = useTheme();
  const groups = [
    { lbl: 'Connection', items: [
      { ic: '↻', nm: <><span style={{ background: c.warningContainer, color: c.warningContainerFg, padding: '0 2px', borderRadius: 2 }}>Rec</span>onnect tunnel</>, kbd: ['⌘', 'R'], active: true },
      { ic: '⏻', nm: 'Disconnect', kbd: ['⌘', '⇧', 'D'] },
    ]},
    { lbl: 'Diagnostics', items: [
      { ic: '◉', nm: <><span style={{ background: c.warningContainer, color: c.warningContainerFg, padding: '0 2px', borderRadius: 2 }}>Rec</span>ord pcap</>, desc: '5 m · 50 MB cap' },
      { ic: '◐', nm: 'Run probe wizard', kbd: ['⌘', 'P'] },
    ]},
  ];
  return (
    <div style={{ background: c.card, border: `1px solid ${c.border}`, borderRadius: 14, boxShadow: '0 12px 32px rgba(0,0,0,0.18)', overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 14px', borderBottom: `0.5px solid ${c.divider}` }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke={c.mutedForeground} strokeWidth="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" defaultValue="rec" style={{ flex: 1, border: 'none', outline: 'none', background: 'transparent', font: `500 13px/1.2 ${RDP.type.mono}`, color: c.foreground }}/>
        <MKbd size="sm">esc</MKbd>
      </div>
      {groups.map(g => (
        <div key={g.lbl} style={{ padding: '6px 0', borderBottom: `0.5px solid ${c.divider}` }}>
          <div style={{ padding: '4px 14px', font: `400 9.5px ${RDP.type.mono}`, letterSpacing: 0.6, textTransform: 'uppercase', color: c.mutedForeground }}>{g.lbl}</div>
          {g.items.map((it, i) => (
            <div key={i} style={{ display: 'grid', gridTemplateColumns: '20px 1fr auto', gap: 10, alignItems: 'center', padding: '8px 14px', background: it.active ? c.foreground : 'transparent', color: it.active ? c.background : c.foreground }}>
              <span style={{ font: `400 12px ${RDP.type.mono}`, color: it.active ? c.background : c.mutedForeground, textAlign: 'center' }}>{it.ic}</span>
              <span style={{ font: `400 12px ${RDP.type.mono}` }}>{it.nm}{it.desc && <span style={{ color: it.active ? 'rgba(255,255,255,0.7)' : c.mutedForeground, marginLeft: 6, fontSize: 10 }}>{it.desc}</span>}</span>
              {it.kbd && <span style={{ display: 'inline-flex', gap: 3 }}>{it.kbd.map((k, j) => <MKbd key={j} size="sm">{k}</MKbd>)}</span>}
            </div>
          ))}
        </div>
      ))}
      <div style={{ display: 'flex', justifyContent: 'space-between', padding: '7px 14px', background: c.muted, font: `400 9.5px ${RDP.type.mono}`, color: c.mutedForeground }}>
        <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}><MKbd size="sm">↑</MKbd><MKbd size="sm">↓</MKbd> nav</span>
        <span>5 of 47</span>
      </div>
    </div>
  );
}

function MFilterBar() {
  const { c } = useTheme();
  const chips = [
    { k: 'level:', v: 'error', tone: 'bad' },
    { k: 'stage:', v: 'tls' },
    { k: 'last:', v: '30 m', tone: 'warn' },
  ];
  const tone = (t) => t === 'bad' ? { bg: c.destructive, fg: c.destructiveForeground || '#fff' } : t === 'warn' ? { bg: c.warning, fg: c.warningForeground || '#fff' } : { bg: c.foreground, fg: c.background };
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px', background: c.muted, border: `1px solid ${c.border}`, borderRadius: 9999, flexWrap: 'wrap' }}>
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke={c.mutedForeground} strokeWidth="2" style={{ flexShrink: 0 }}><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
      <input type="text" placeholder="Filter logs…" style={{ flex: 1, minWidth: 60, border: 'none', outline: 'none', background: 'transparent', font: `400 12px ${RDP.type.mono}`, color: c.foreground }}/>
      {chips.map((ch, i) => {
        const t = tone(ch.tone);
        return (
          <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '4px 6px 4px 10px', borderRadius: 9999, background: t.bg, color: t.fg, font: `400 10.5px ${RDP.type.mono}`, whiteSpace: 'nowrap', lineHeight: 1 }}>
            <span style={{ opacity: 0.6, marginRight: -2 }}>{ch.k}</span>{ch.v}
            <span style={{ width: 14, height: 14, borderRadius: 9999, background: 'rgba(255,255,255,0.18)', display: 'inline-grid', placeItems: 'center', flexShrink: 0 }}>
              <svg width="7" height="7" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2.5"><line x1="3" y1="3" x2="13" y2="13"/><line x1="13" y1="3" x2="3" y2="13"/></svg>
            </span>
          </span>
        );
      })}
    </div>
  );
}

function MLogStream() {
  const { c } = useTheme();
  const lines = [
    { ts: '18:42:07', lvl: 'INFO', msg: <>probe: dns lookup <span style={{ color: c.info }}>cloudflare-dns.com</span> · 24 ms</>, tone: 'info' },
    { ts: '18:42:07', lvl: 'WARN', msg: <>mtu: <b style={{ color: c.warning, fontWeight: 500 }}>probe over budget</b> · 423 ms</>, tone: 'warn' },
    { ts: '18:42:07', lvl: 'ERROR', msg: <>tcp: <b style={{ color: c.destructive, fontWeight: 500 }}>RST received</b> · ttl=128</>, tone: 'bad' },
    { ts: '18:42:07', lvl: 'OK', msg: <>tunnel: <b style={{ color: c.success, fontWeight: 500 }}>established</b> · 1.42 s</>, tone: 'ok', live: true },
  ];
  const lvlBg = (t) => t === 'info' ? c.infoContainer : t === 'warn' ? c.warningContainer : t === 'bad' ? c.destructiveContainer : t === 'ok' ? c.successContainer : c.muted;
  const lvlFg = (t) => t === 'info' ? c.info : t === 'warn' ? c.warning : t === 'bad' ? c.destructive : t === 'ok' ? c.success : c.mutedForeground;
  return (
    <div style={{ background: c.card, border: `0.5px solid ${c.divider}`, borderRadius: 10, overflow: 'hidden' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px', background: c.muted, font: `400 10.5px ${RDP.type.mono}`, color: c.mutedForeground }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <MHeartbeat tone="tunl" size="sm"/>
          <span style={{ color: c.success, fontWeight: 500 }}>LIVE</span>
          <span>· 1 482 rows</span>
        </span>
        <span style={{ display: 'inline-flex', gap: 4 }}>
          <MKbd size="sm">⏸</MKbd>
          <MKbd size="sm">↓</MKbd>
        </span>
      </div>
      {lines.map((ln, i) => (
        <div key={i} style={{ display: 'grid', gridTemplateColumns: 'auto auto 1fr', gap: 8, alignItems: 'center', padding: '7px 12px', borderTop: i === 0 ? 'none' : `0.5px solid ${c.divider}`, font: `400 11px/16px ${RDP.type.mono}`, animation: ln.live ? 'meArrive 220ms cubic-bezier(0.2,0,0,1)' : 'none' }}>
          <span style={{ color: c.mutedForeground, fontSize: 10 }}>{ln.ts}</span>
          <span style={{ background: lvlBg(ln.tone), color: lvlFg(ln.tone), font: `500 9px ${RDP.type.mono}`, letterSpacing: 0.4, padding: '1px 5px', borderRadius: 3 }}>{ln.lvl}</span>
          <span style={{ color: c.foreground, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{ln.msg}</span>
        </div>
      ))}
    </div>
  );
}

function MJsonTree() {
  const { c } = useTheme();
  const node = ({ children, indent = 0 }) => children;
  return (
    <div style={{ background: c.muted, border: `0.5px solid ${c.divider}`, borderRadius: 8, padding: 12, font: `400 11.5px/19px ${RDP.type.mono}`, color: c.foreground, overflowX: 'auto' }}>
      <div>{'{'}</div>
      <div style={{ paddingLeft: 14, borderLeft: `0.5px solid ${c.outlineVariant}`, marginLeft: 5 }}>
        <div><span style={{ color: c.foreground, fontWeight: 500 }}>"scan_id"</span><span style={{ color: c.mutedForeground }}>: </span><span style={{ color: c.success }}>"4f7a"</span>,</div>
        <div><span style={{ color: c.foreground, fontWeight: 500 }}>"verdict"</span><span style={{ color: c.mutedForeground }}>: </span><span style={{ color: c.success }}>"degraded"</span>,</div>
        <div>▾ <span style={{ color: c.foreground, fontWeight: 500 }}>"signatures"</span><span style={{ color: c.mutedForeground }}>: </span>[</div>
        <div style={{ paddingLeft: 14, borderLeft: `0.5px solid ${c.outlineVariant}`, marginLeft: 5 }}>
          <div>▾ [0] {'{'}</div>
          <div style={{ paddingLeft: 14, borderLeft: `0.5px solid ${c.outlineVariant}`, marginLeft: 5 }}>
            <div><span style={{ color: c.foreground, fontWeight: 500 }}>"code"</span><span style={{ color: c.mutedForeground }}>: </span><span style={{ color: c.success }}>"tls.sni"</span>,</div>
            <div><span style={{ color: c.foreground, fontWeight: 500 }}>"confidence"</span><span style={{ color: c.mutedForeground }}>: </span><span style={{ color: c.info }}>0.94</span>,</div>
            <div><span style={{ color: c.foreground, fontWeight: 500 }}>"bypassed"</span><span style={{ color: c.mutedForeground }}>: </span><span style={{ color: c.warning }}>false</span></div>
          </div>
          <div>{'}'},</div>
          <div style={{ color: c.mutedForeground }}>▸ [1] {'{ 3 keys }'},</div>
          <div style={{ color: c.mutedForeground }}>▸ [2] {'{ 3 keys }'}</div>
        </div>
        <div>]</div>
      </div>
      <div>{'}'}</div>
    </div>
  );
}

function MDiffViewer() {
  const { c } = useTheme();
  // Unified diff for mobile (side-by-side doesn't fit)
  const lines = [
    { type: 'ctx', ln: 1, code: '[strategy]' },
    { type: 'ctx', ln: 2, code: 'name = "tlsrec_v1"' },
    { type: 'rem', ln: 3, code: 'split = false' },
    { type: 'add', ln: 3, code: 'split = true' },
    { type: 'ctx', ln: 4, code: 'resolver = "cloudflare"' },
    { type: 'hunk', code: '@@ -8,3 +8,5 @@' },
    { type: 'rem', ln: 10, code: 'timeout_ms = 2000' },
    { type: 'add', ln: 10, code: 'timeout_ms = 4000' },
    { type: 'add', ln: 11, code: 'retries = 3' },
  ];
  return (
    <div style={{ background: c.card, border: `0.5px solid ${c.divider}`, borderRadius: 8, overflow: 'hidden' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 12px', background: c.muted, font: `400 10px ${RDP.type.mono}`, color: c.mutedForeground }}>
        <span style={{ color: c.foreground }}>tlsrec_split_host.toml</span>
        <span><span style={{ color: c.success }}>+3</span> · <span style={{ color: c.destructive }}>−2</span></span>
      </div>
      {lines.map((ln, i) => {
        const bg = ln.type === 'add' ? c.successContainer : ln.type === 'rem' ? c.destructiveContainer : ln.type === 'hunk' ? c.accent : c.card;
        const fg = ln.type === 'add' ? c.successContainerFg : ln.type === 'rem' ? c.destructiveContainerFg : ln.type === 'hunk' ? c.mutedForeground : c.foreground;
        const prefix = ln.type === 'add' ? '+ ' : ln.type === 'rem' ? '− ' : '';
        return (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: '32px 1fr', font: `400 11px/18px ${RDP.type.mono}`, background: bg, color: fg }}>
            <span style={{ textAlign: 'right', padding: '0 8px', font: `400 9.5px ${RDP.type.mono}`, color: c.mutedForeground, background: ln.type === 'hunk' ? 'transparent' : c.muted, borderRight: ln.type === 'hunk' ? 'none' : `0.5px solid ${c.divider}`, alignSelf: 'stretch', display: ln.type === 'hunk' ? 'none' : 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>{ln.ln}</span>
            <span style={{ padding: '0 8px', gridColumn: ln.type === 'hunk' ? '1 / -1' : 'auto', textAlign: ln.type === 'hunk' ? 'center' : 'left' }}>{prefix}{ln.code}</span>
          </div>
        );
      })}
    </div>
  );
}

function MSlider({ label, value, unit, min, max, ticks = [], snaps = [], tone = 'default' }) {
  const { c } = useTheme();
  const pct = ((value - min) / (max - min)) * 100;
  const fill = tone === 'warn' ? c.warning : tone === 'bad' ? c.destructive : c.foreground;
  return (
    <div style={{ padding: '14px 0', borderBottom: `0.5px solid ${c.divider}` }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 26 }}>
        <span style={{ font: `500 13px/16px ${RDP.type.sans}`, color: c.foreground }}>{label}</span>
        <span style={{ font: `500 14px/1 ${RDP.type.mono}`, color: c.foreground, fontVariantNumeric: 'tabular-nums' }}>{value}<span style={{ font: `400 10.5px ${RDP.type.mono}`, color: c.mutedForeground, marginLeft: 2 }}>{unit}</span></span>
      </div>
      <div style={{ position: 'relative', height: 24, padding: '0 4px' }}>
        <span style={{ position: 'absolute', top: '50%', left: 4, right: 4, height: 4, background: c.muted, borderRadius: 2, transform: 'translateY(-50%)' }}/>
        <span style={{ position: 'absolute', top: '50%', left: 4, width: `calc(${pct}% - 8px * ${pct/100})`, height: 4, background: fill, borderRadius: 2, transform: 'translateY(-50%)' }}/>
        {snaps.map((s, i) => {
          const sPct = ((s.v - min) / (max - min)) * 100;
          return <span key={i} style={{ position: 'absolute', top: '50%', left: `${sPct}%`, width: 6, height: 6, borderRadius: 9999, background: s.v === value ? fill : c.card, border: `1.5px solid ${s.v === value ? fill : c.mutedForeground}`, transform: 'translate(-50%, -50%)' }}/>;
        })}
        <span style={{ position: 'absolute', top: '50%', left: `${pct}%`, width: 20, height: 20, borderRadius: 9999, background: c.card, border: `1.5px solid ${fill}`, transform: 'translate(-50%, -50%)', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
          <span style={{ position: 'absolute', left: '50%', top: -28, transform: 'translateX(-50%)', background: fill, color: '#fff', padding: '3px 7px', borderRadius: 4, font: `500 10.5px/1 ${RDP.type.mono}`, whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{value}</span>
        </span>
      </div>
      <div style={{ position: 'relative', height: 14, marginTop: 8 }}>
        {ticks.map((t, i) => (
          <span key={i} style={{ position: 'absolute', left: `${((t.v - min) / (max - min)) * 100}%`, transform: 'translateX(-50%)', font: `400 9.5px ${RDP.type.mono}`, color: c.mutedForeground, fontVariantNumeric: 'tabular-nums' }}>{t.label}</span>
        ))}
      </div>
    </div>
  );
}

function MSegmented({ options, selected, onSelect }) {
  const { c } = useTheme();
  return (
    <span style={{ display: 'inline-flex', alignItems: 'stretch', background: c.muted, padding: 3, borderRadius: 9999, border: `0.5px solid ${c.border}` }}>
      {options.map(o => (
        <button key={o} onClick={() => onSelect?.(o)} style={{ padding: '6px 14px', font: `${o === selected ? 500 : 400} 12px ${RDP.type.sans}`, color: o === selected ? c.foreground : c.mutedForeground, background: o === selected ? c.card : 'transparent', borderRadius: 9999, border: 'none', cursor: 'pointer', boxShadow: o === selected ? '0 1px 2px rgba(0,0,0,0.06)' : 'none' }}>{o}</button>
      ))}
    </span>
  );
}

function MStepper({ steps, current }) {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', flexDirection: 'column' }}>
      {steps.map((s, i) => {
        const done = i < current;
        const now = i === current;
        const failed = s.failed;
        const bullet = failed ? { bg: c.destructive, fg: c.destructiveForeground || '#fff' } : (done || now) ? { bg: c.foreground, fg: c.background } : { bg: c.card, fg: c.mutedForeground, border: c.border };
        return (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: '24px 1fr', columnGap: 14, padding: '8px 0', position: 'relative', alignItems: 'start' }}>
            <span style={{ width: 24, height: 24, borderRadius: 9999, background: bullet.bg, color: bullet.fg, border: bullet.border ? `1px solid ${bullet.border}` : 'none', display: 'grid', placeItems: 'center', font: `500 11px/1 ${RDP.type.mono}`, justifySelf: 'center', alignSelf: 'start', marginTop: 1, zIndex: 2 }}>
              {failed ? '!' : done ? '✓' : i + 1}
            </span>
            {i < steps.length - 1 && (
              <span style={{ position: 'absolute', left: 11, top: 34, bottom: -2, width: 2, background: done ? c.foreground : c.outlineVariant }}/>
            )}
            <div style={{ paddingTop: 3 }}>
              <div style={{ font: `${now ? 500 : 400} 12px ${RDP.type.mono}`, color: failed ? c.destructive : c.foreground }}>{s.name}</div>
              <div style={{ font: `400 10.5px ${RDP.type.mono}`, color: c.mutedForeground, marginTop: 2 }}>{s.desc}</div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function MTabsUnderline({ tabs, selected, onSelect }) {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', gap: 0, borderBottom: `0.5px solid ${c.divider}`, overflowX: 'auto' }}>
      {tabs.map(t => (
        <button key={t.id} onClick={() => onSelect?.(t.id)} style={{ padding: '10px 14px', font: `${t.id === selected ? 500 : 400} 13px ${RDP.type.sans}`, color: t.id === selected ? c.foreground : c.mutedForeground, background: 'transparent', border: 'none', position: 'relative', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap' }}>
          {t.label}
          {t.badge !== undefined && <span style={{ font: `400 9.5px ${RDP.type.mono}`, padding: '1px 5px', borderRadius: 9999, background: t.badgeTone === 'bad' ? c.destructiveContainer : t.badgeTone === 'warn' ? c.warningContainer : c.accent, color: t.badgeTone === 'bad' ? c.destructive : t.badgeTone === 'warn' ? c.warning : c.mutedForeground }}>{t.badge}</span>}
          {t.id === selected && <span style={{ position: 'absolute', left: 8, right: 8, bottom: -0.5, height: 2, background: c.foreground, borderRadius: 2 }}/>}
        </button>
      ))}
    </div>
  );
}

function MAccordion({ items, openIdx = 0 }) {
  const { c } = useTheme();
  return (
    <div style={{ border: `0.5px solid ${c.divider}`, borderRadius: 10, overflow: 'hidden' }}>
      {items.map((it, i) => {
        const open = i === openIdx;
        const toneColor = it.tone === 'bad' ? c.destructive : it.tone === 'warn' ? c.warning : it.tone === 'ok' ? c.success : c.mutedForeground;
        return (
          <div key={i} style={{ background: c.card, borderTop: i === 0 ? 'none' : `0.5px solid ${c.divider}` }}>
            <div style={{ display: 'grid', gridTemplateColumns: '18px 1fr auto auto', gap: 10, alignItems: 'center', padding: '12px 14px' }}>
              <span style={{ color: c.mutedForeground, transition: 'transform 220ms', transform: open ? 'rotate(90deg)' : 'rotate(0)' }}>
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
              </span>
              <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                <span style={{ font: `500 13px/16px ${RDP.type.sans}` }}>{it.title}</span>
                <span style={{ font: `400 10.5px/14px ${RDP.type.mono}`, color: c.mutedForeground }}>{it.sub}</span>
              </span>
              <span style={{ color: toneColor }}>●</span>
              <span style={{ font: `400 10.5px ${RDP.type.mono}`, color: c.mutedForeground }}>{it.meta}</span>
            </div>
            {open && it.body && (
              <div style={{ padding: '0 14px 12px 42px', font: `400 11px/18px ${RDP.type.mono}`, color: c.mutedForeground }}>
                {it.body}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function MCombobox() {
  const { c } = useTheme();
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px', border: `1px solid ${c.foreground}`, borderRadius: 8, background: c.card, height: 44 }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke={c.mutedForeground} strokeWidth="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input defaultValue="ams" style={{ flex: 1, border: 'none', outline: 'none', background: 'transparent', font: `500 13px ${RDP.type.mono}`, color: c.foreground }}/>
        <span style={{ width: 18, height: 18, borderRadius: 9999, background: c.muted, display: 'grid', placeItems: 'center', cursor: 'pointer' }}>
          <svg width="8" height="8" viewBox="0 0 16 16" fill="none" stroke={c.foreground} strokeWidth="2.5"><line x1="3" y1="3" x2="13" y2="13"/><line x1="13" y1="3" x2="3" y2="13"/></svg>
        </span>
      </div>
      <div style={{ marginTop: 4, background: c.card, border: `1px solid ${c.border}`, borderRadius: 8, boxShadow: '0 8px 24px rgba(0,0,0,0.12)' }}>
        <div style={{ padding: '6px 14px 4px', font: `400 9.5px ${RDP.type.mono}`, letterSpacing: 0.6, textTransform: 'uppercase', color: c.mutedForeground }}>Nearest</div>
        {[
          { match: 'AMS', rest: ' · Amsterdam', desc: 'Cloudflare', meta: '38 ms', active: true },
          { match: 'AMS', rest: 'terdam-IX', meta: '42 ms' },
        ].map((o, i) => (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 8, alignItems: 'center', padding: '9px 14px', background: o.active ? c.foreground : 'transparent', color: o.active ? c.background : c.foreground }}>
            <span style={{ font: `400 12px ${RDP.type.mono}` }}>
              <span style={{ background: c.warningContainer, color: c.warningContainerFg, padding: '0 2px', borderRadius: 2 }}>{o.match}</span>{o.rest}
              {o.desc && <span style={{ marginLeft: 6, font: `400 10px ${RDP.type.mono}`, color: o.active ? 'rgba(255,255,255,0.7)' : c.mutedForeground }}>{o.desc}</span>}
            </span>
            <span style={{ font: `400 10.5px ${RDP.type.mono}`, color: o.active ? 'rgba(255,255,255,0.7)' : c.mutedForeground }}>{o.meta}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function MCidrInput({ label, octets, mask, ok = true }) {
  const { c } = useTheme();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, padding: '10px 0' }}>
      <span style={{ font: `400 10.5px ${RDP.type.mono}`, color: c.mutedForeground }}>{label}</span>
      <div style={{ display: 'flex', alignItems: 'center', border: `1px solid ${ok ? c.success : c.destructive}`, borderRadius: 8, background: ok ? c.card : c.destructiveContainer, height: 44, padding: '0 2px', font: `400 13px ${RDP.type.mono}`, color: c.foreground }}>
        {octets.map((o, i) => (
          <React.Fragment key={i}>
            {i > 0 && <span style={{ color: c.mutedForeground }}>.</span>}
            <input defaultValue={o} style={{ border: 'none', outline: 'none', background: 'transparent', font: 'inherit', color: 'inherit', textAlign: 'center', width: '3ch', padding: 0, fontVariantNumeric: 'tabular-nums' }}/>
          </React.Fragment>
        ))}
        {mask !== undefined && (
          <>
            <span style={{ color: c.mutedForeground }}>/</span>
            <input defaultValue={mask} style={{ border: 'none', outline: 'none', background: 'transparent', font: 'inherit', color: 'inherit', textAlign: 'center', width: '3ch', padding: 0, fontVariantNumeric: 'tabular-nums' }}/>
          </>
        )}
      </div>
    </div>
  );
}

// ─── Onboarding ────────────────────────────────────────────────
function MCoachMark({ targetTop = 100 }) {
  const { c } = useTheme();
  return (
    <div style={{ position: 'absolute', inset: 0, '--sx': '50%', '--sy': targetTop + 'px', '--sr': '56px', '--rr': '64px' }}>
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)',
        WebkitMask: `radial-gradient(circle 64px at 50% ${targetTop}px, transparent 56px, black 64px)`,
        mask: `radial-gradient(circle 64px at 50% ${targetTop}px, transparent 56px, black 64px)` }}/>
      <span style={{ position: 'absolute', left: '50%', top: targetTop, width: 128, height: 128, borderRadius: 9999, border: `2px solid ${c.foreground === '#1A1A1A' ? '#fff' : c.foreground}`, transform: 'translate(-50%, -50%)', animation: 'meCoachPulse 1.6s ease-out infinite' }}/>
      <div style={{ position: 'absolute', left: '50%', top: targetTop + 64 + 24, transform: 'translateX(-50%)', width: 280, background: c.foreground, color: c.background, borderRadius: 12, padding: 14, boxShadow: '0 12px 32px rgba(0,0,0,0.3)' }}>
        <div style={{ position: 'absolute', top: -7, left: '50%', transform: 'translateX(-50%)', width: 0, height: 0, borderLeft: '8px solid transparent', borderRight: '8px solid transparent', borderBottom: `8px solid ${c.foreground}` }}/>
        <div style={{ font: `400 9.5px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', opacity: 0.6 }}>Step 2 of 4</div>
        <div style={{ font: `500 14px/18px ${RDP.type.sans}`, marginTop: 4 }}>Tap to connect</div>
        <div style={{ font: `400 12px/18px ${RDP.type.sans}`, marginTop: 6, opacity: 0.85 }}>Long-press to pick a strategy without disconnecting.</div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 12, gap: 8 }}>
          <span style={{ display: 'inline-flex', gap: 4 }}>
            {[0,1,2,3].map(i => <span key={i} style={{ width: i === 1 ? 14 : 5, height: 5, borderRadius: 9999, background: i === 1 ? c.background : 'rgba(255,255,255,0.3)' }}/>)}
          </span>
          <span style={{ display: 'inline-flex', gap: 6 }}>
            <button style={{ background: 'transparent', border: 'none', color: c.background, opacity: 0.7, padding: '5px 10px', font: `400 10px ${RDP.type.mono}`, cursor: 'pointer' }}>Skip</button>
            <button style={{ background: c.background, color: c.foreground, border: 'none', padding: '5px 12px', borderRadius: 9999, font: `500 10px ${RDP.type.mono}`, cursor: 'pointer' }}>Next</button>
          </span>
        </div>
      </div>
    </div>
  );
}

function MWhatsNewSheet() {
  const { c } = useTheme();
  return (
    <div style={{ background: c.card, border: `1px solid ${c.cardBorder}`, borderRadius: 14, padding: 18, display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
        <div>
          <div style={{ font: `500 22px/1 ${RDP.type.mono}`, fontVariantNumeric: 'tabular-nums' }}>v3.2.0</div>
          <div style={{ font: `400 11px ${RDP.type.mono}`, color: c.mutedForeground, marginTop: 4 }}>Released May 15</div>
        </div>
        <span style={{ font: `400 10px ${RDP.type.mono}`, padding: '3px 9px', borderRadius: 9999, background: c.infoContainer, color: c.info, letterSpacing: 0.4, textTransform: 'uppercase', display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={{ width: 6, height: 6, borderRadius: 9999, background: c.info }}/>What's new
        </span>
      </div>
      {[
        { tag: 'NEW',      tone: c.success,    bg: c.successContainer,     ttl: 'DPI confidence meter', body: 'Each detected signature ships with a 0–1 score.' },
        { tag: 'FIX',      tone: c.info,       bg: c.infoContainer,        ttl: 'Reconnect respects cancel', body: 'Cancelling during a backoff stops the chain immediately.' },
        { tag: 'BREAKING', tone: c.destructive,bg: c.destructiveContainer, ttl: 'retry → retries', body: 'Strategy schema field renamed; auto-migrated on import.' },
      ].map((ch, i) => (
        <div key={i} style={{ display: 'grid', gridTemplateColumns: '76px 1fr', gap: 10, alignItems: 'flex-start' }}>
          <span style={{ font: `400 9.5px ${RDP.type.mono}`, padding: '2px 6px', borderRadius: 3, background: ch.bg, color: ch.tone, letterSpacing: 0.4, textTransform: 'uppercase', textAlign: 'center', marginTop: 2, whiteSpace: 'nowrap' }}>{ch.tag}</span>
          <div>
            <div style={{ font: `500 13px/17px ${RDP.type.sans}` }}>{ch.ttl}</div>
            <div style={{ font: `400 11px ${RDP.type.mono}`, color: c.mutedForeground, marginTop: 2 }}>{ch.body}</div>
          </div>
        </div>
      ))}
      <div style={{ display: 'flex', justifyContent: 'space-between', paddingTop: 10, borderTop: `0.5px solid ${c.divider}` }}>
        <a style={{ font: `400 10.5px ${RDP.type.mono}`, color: c.mutedForeground, textDecoration: 'underline' }}>Full changelog</a>
        <RipDpiButton text="Got it" style={{ minHeight: 32, padding: '5px 12px', fontSize: 12 }}/>
      </div>
    </div>
  );
}

Object.assign(window, {
  MHeartbeat, MLiveCounter, MStaleBadge, MProgressBar, MShimmer,
  MKbd, MCommandPalette, MFilterBar, MLogStream, MJsonTree, MDiffViewer,
  MSlider, MSegmented, MStepper, MTabsUnderline, MAccordion, MCombobox, MCidrInput,
  MCoachMark, MWhatsNewSheet,
});
