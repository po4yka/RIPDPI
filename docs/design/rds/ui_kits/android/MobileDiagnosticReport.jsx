/* ui_kits/android/MobileDiagnosticReport.jsx
   Mobile adoption of the 6 RIPDPI diagnostic-report preview elements.
   Composed entirely out of existing RipDpi kit primitives (RipDpiCard,
   MetricPill, StatusIndicator, RipDpiChip, RipDpiButton, SectionHeader,
   Hairline, WarningBanner) plus a handful of view-specific bits.

   Source elements:
     preview/diagnostic-report-summary.html
     preview/diagnostic-hop-trace.html
     preview/diagnostic-mtu-scan.html
     preview/diagnostic-port-matrix.html
     preview/diagnostic-censorship-signature.html
     preview/diagnostic-dns-resolvers.html
*/

// ─────────────────────────────────────────────────────────────
// 1. Report summary
// ─────────────────────────────────────────────────────────────
function MobileReportSummary({ onFindStrategy, onExport }) {
  const { c } = useTheme();
  const kpis = [
    { k: 'RTT p50',       v: '32 ms',   delta: '+8 ms vs baseline', dir: 'up'   },
    { k: 'Loss',          v: '4.1%',    delta: '+3.2 pp',           dir: 'up',   tone: 'warn' },
    { k: 'Path MTU',      v: '1380',    delta: '−120 from link',    dir: 'flat' },
    { k: 'Exit ASN',      v: 'AS13335', delta: 'Cloudflare · NL',   dir: 'flat' },
    { k: 'DPI signature', v: 'SNI',     delta: '2 of 4 stages',     dir: 'up',   tone: 'bad'  },
    { k: 'Leaks',         v: '3 / 5',   delta: 'IPv6, TZ, ASN',     dir: 'up',   tone: 'warn' },
  ];
  return (
    <RipDpiCard variant="status">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
          <div style={{ font: `400 11px/14px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground }}>
            Scan · 18:42 UTC · #4f7a
          </div>
          <div style={{ font: `500 22px/28px ${RDP.type.sans}`, color: c.foreground }}>
            Network somewhat restricted
          </div>
          <div style={{ font: `400 12px/16px ${RDP.type.mono}`, color: c.mutedForeground }}>
            Wi-Fi · AS12389 · MOW
          </div>
        </div>
        <MetricPill tone="warning"
          icon={<span style={{ display:'inline-block', width:0,height:0,borderLeft:'4px solid transparent',borderRight:'4px solid transparent',borderBottom:'7px solid currentColor' }}/>}
          label="WARNING"
        />
      </div>

      <div style={{
        marginTop: 8,
        display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 0,
        background: c.card, border: `0.5px solid ${c.divider}`, borderRadius: RDP.radius.md,
        overflow: 'hidden',
      }}>
        {kpis.map((k, i) => {
          const rightEdge = i % 2 === 1;
          const bottomEdge = i >= 4;
          const tone = k.tone === 'bad' ? c.destructive : k.tone === 'warn' ? c.warning : c.foreground;
          const deltaColor = k.dir === 'up' && k.tone ? (k.tone === 'bad' ? c.destructive : c.warning) : c.mutedForeground;
          return (
            <div key={k.k} style={{
              padding: '10px 12px',
              borderRight: rightEdge ? 'none' : `0.5px solid ${c.divider}`,
              borderBottom: bottomEdge ? 'none' : `0.5px solid ${c.divider}`,
              background: c.card,
            }}>
              <div style={{ font: `400 10px/14px ${RDP.type.mono}`, letterSpacing: 0.5, textTransform: 'uppercase', color: c.mutedForeground }}>{k.k}</div>
              <div style={{ font: `500 16px/20px ${RDP.type.mono}`, color: tone, fontVariantNumeric: 'tabular-nums', marginTop: 2 }}>{k.v}</div>
              <div style={{ font: `400 10px/14px ${RDP.type.mono}`, color: deltaColor, marginTop: 2 }}>{k.delta}</div>
            </div>
          );
        })}
      </div>

      <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginTop: 4, flexWrap: 'wrap' }}>
        <span style={{ font: `400 11px/14px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground, marginRight: 2 }}>Stages</span>
        <MetricPill label="DNS" tone="success" />
        <MetricPill label="TCP" tone="success" />
        <MetricPill label="TLS" tone="danger" />
        <MetricPill label="QUIC" tone="warning" />
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
        <RipDpiButton text="Export pcap" variant="outline" onClick={onExport} style={{ flex: 1 }} />
        <RipDpiButton text="Find a strategy" onClick={onFindStrategy} style={{ flex: 1 }} />
      </div>
    </RipDpiCard>
  );
}

// ─────────────────────────────────────────────────────────────
// 2. Hop trace
// ─────────────────────────────────────────────────────────────
function MobileHopTrace() {
  const { c } = useTheme();
  const hops = [
    { i: 1,  ip: '192.168.1.1',    asn: '_gateway · LAN',                     rtt: '1 ms',   bar: 0.02, kind: 'you' },
    { i: 2,  ip: '10.40.12.1',     asn: 'AS12389 · ROSTELECOM',               rtt: '8 ms',   bar: 0.08 },
    { i: 3,  ip: '10.40.0.49',     asn: 'AS12389 · ROSTELECOM',               rtt: '12 ms',  bar: 0.12 },
    { i: 4,  ip: '* * *',          asn: 'No response · 3 probes',             rtt: 'timeout', timeout: true },
    { i: 5,  ip: '87.245.232.182', asn: 'AS12389 · ROSTELECOM',               rtt: '42 ms',  bar: 0.48, warn: true, tag: { text: 'RST injected', tone: 'bad' } },
    { i: 6,  ip: '194.85.41.10',   asn: 'AS3216 · SOVAM',                     rtt: '38 ms',  bar: 0.42 },
    { i: 7,  ip: '213.248.92.121', asn: 'AS1299 · ARELION · STO',             rtt: '58 ms',  bar: 0.62 },
    { i: 8,  ip: '* * *',          asn: 'No response · 3 probes',             rtt: 'timeout', timeout: true },
    { i: 9,  ip: '141.101.65.232', asn: 'AS13335 · CLOUDFLARE',               rtt: '82 ms',  bar: 0.78, tag: { text: 'tunnel-entry', tone: 'info' } },
    { i: 10, ip: '1.1.1.1',        asn: 'AS13335 · CLOUDFLARE · AMS',         rtt: '168 ms', bar: 1.00, kind: 'exit', tag: { text: 'exit', tone: 'ok' } },
  ];
  return (
    <RipDpiCard>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <span style={{ font: `500 14px/20px ${RDP.type.sans}` }}>Hop trace · 1.1.1.1</span>
        <span style={{ font: `400 11px/14px ${RDP.type.mono}`, color: c.mutedForeground }}>10 hops · <span style={{ color: c.foreground }}>168 ms</span></span>
      </div>

      <div style={{ marginTop: 4 }}>
        {hops.map((h, i) => {
          const isLast = i === hops.length - 1;
          const isFirst = i === 0;
          const rttColor = h.timeout ? c.destructive : h.warn ? c.warning : c.foreground;
          const barColor = h.timeout ? null : h.warn ? c.warning : c.foreground;
          return (
            <div key={h.i} style={{
              display: 'grid', gridTemplateColumns: '24px 1fr auto',
              gap: 10, alignItems: 'center', padding: '8px 0',
              borderTop: isFirst ? 'none' : `0.5px solid ${c.divider}`,
            }}>
              {/* rail */}
              <div style={{ position: 'relative', height: 36, display: 'grid', placeItems: 'center' }}>
                <span style={{ position: 'absolute', left: 11, top: isFirst ? '50%' : 0, bottom: isLast ? '50%' : 0, width: 2, background: c.outlineVariant }}/>
                {h.kind === 'you' ? (
                  <span style={{ position: 'relative', zIndex: 1, width: 12, height: 12, borderRadius: 9999, background: c.card, border: `2px solid ${c.foreground}`, boxShadow: `0 0 0 2px ${c.card}` }}/>
                ) : h.kind === 'exit' ? (
                  <span style={{ position: 'relative', zIndex: 1, width: 11, height: 11, background: c.foreground, transform: 'rotate(45deg)', borderRadius: 2, boxShadow: `0 0 0 2px ${c.card}` }}/>
                ) : (
                  <span style={{ position: 'relative', zIndex: 1, width: 10, height: 10, borderRadius: 9999,
                    background: h.timeout ? c.destructive : h.warn ? c.warning : c.foreground,
                    boxShadow: `0 0 0 2px ${c.card}` }}/>
                )}
              </div>

              {/* who */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, font: `400 13px/16px ${RDP.type.mono}`,
                              color: h.timeout ? c.mutedForeground : c.foreground }}>
                  <span style={{ color: c.mutedForeground, fontSize: 10, minWidth: 14 }}>{String(h.i).padStart(2,'0')}</span>
                  <span style={{ overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap' }}>{h.ip}</span>
                </div>
                <div style={{ font: `400 10.5px/14px ${RDP.type.mono}`, color: c.mutedForeground, marginLeft: 22, overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap' }}>
                  {h.asn}
                </div>
                {h.tag && (
                  <div style={{ marginLeft: 22, marginTop: 2 }}>
                    <span style={{
                      display: 'inline-block',
                      font: `400 9.5px/12px ${RDP.type.mono}`, letterSpacing: 0.3,
                      padding: '1px 6px', borderRadius: 3,
                      background: h.tag.tone === 'bad' ? c.destructiveContainer : h.tag.tone === 'info' ? c.infoContainer : c.successContainer,
                      color: h.tag.tone === 'bad' ? c.destructive : h.tag.tone === 'info' ? c.info : c.success,
                    }}>{h.tag.text}</span>
                  </div>
                )}
              </div>

              {/* rtt + bar */}
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 4, minWidth: 60 }}>
                <span style={{ font: `500 12px/14px ${RDP.type.mono}`, color: rttColor, fontVariantNumeric: 'tabular-nums' }}>{h.rtt}</span>
                <span style={{ width: 60, height: 4, background: h.timeout ? 'transparent' : c.muted, borderRadius: 2, overflow: 'hidden',
                              backgroundImage: h.timeout ? `repeating-linear-gradient(135deg, ${c.destructiveContainer} 0 4px, transparent 4px 8px)` : 'none' }}>
                  {!h.timeout && (
                    <span style={{ display: 'block', height: '100%', width: `${h.bar * 100}%`, background: barColor, borderRadius: 2 }}/>
                  )}
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </RipDpiCard>
  );
}

// ─────────────────────────────────────────────────────────────
// 3. Path-MTU scan
// ─────────────────────────────────────────────────────────────
function MobileMtuScan() {
  const { c } = useTheme();
  const gatePct = 87.0; // (1380 - 576) / (1500 - 576)
  const rows = [
    { i: 1, size: 1500, frag: 'DF',   echo: 'no echo · ICMP frag-needed at 7', stat: 'Too big', tone: 'bad' },
    { i: 2, size: 1400, frag: 'DF',   echo: 'no echo · timeout 800 ms',        stat: 'Too big', tone: 'bad' },
    { i: 3, size: 1380, frag: 'DF',   echo: 'echo 38 ms',                      stat: 'OK · gate', tone: 'ok',  gate: true },
    { i: 4, size: 1280, frag: 'DF',   echo: 'echo 36 ms',                      stat: 'OK',       tone: 'ok' },
    { i: 5, size: 1500, frag: 'FRAG', echo: 'echo 41 ms · split 2 fragments',  stat: 'Frag OK',  tone: 'warn' },
  ];
  return (
    <RipDpiCard>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end' }}>
        <div>
          <div style={{ font: `500 14px/20px ${RDP.type.sans}` }}>Path-MTU scan</div>
          <div style={{ font: `400 11px/14px ${RDP.type.mono}`, color: c.mutedForeground, marginTop: 2 }}>cloudflare-dns.com · DF set</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ font: `500 26px/28px ${RDP.type.mono}`, fontVariantNumeric: 'tabular-nums' }}>1380<span style={{ font: `400 11px/14px ${RDP.type.mono}`, color: c.mutedForeground, marginLeft: 4 }}>b</span></div>
          <div style={{ font: `400 9.5px/12px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground, marginTop: 2 }}>Effective MTU</div>
        </div>
      </div>

      {/* Scale */}
      <div style={{ position: 'relative', margin: '24px 4px 30px' }}>
        <div style={{ position: 'relative' }}>
          {/* Pin */}
          <div style={{ position: 'absolute', bottom: 'calc(100% + 6px)', left: `${gatePct}%`, transform: 'translateX(-50%)',
                        font: `500 10px/1 ${RDP.type.mono}`, color: c.background, background: c.foreground,
                        padding: '2px 7px', borderRadius: 4, whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>
            1380 b
            <span style={{ position: 'absolute', left: '50%', top: '100%', transform: 'translateX(-50%)',
                           width: 0, height: 0, borderLeft: '3px solid transparent', borderRight: '3px solid transparent', borderTop: `4px solid ${c.foreground}` }}/>
          </div>

          {/* Track */}
          <div style={{ height: 10, borderRadius: 3, background: c.muted, position: 'relative', overflow: 'hidden' }}>
            <span style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${gatePct}%`, background: c.foreground }}/>
            <span style={{ position: 'absolute', left: `${gatePct}%`, right: 0, top: 0, bottom: 0,
                           background: `repeating-linear-gradient(135deg, ${c.destructiveContainer} 0 4px, transparent 4px 8px)` }}/>
          </div>
          {/* Gate marker */}
          <span style={{ position: 'absolute', top: -4, bottom: -4, left: `${gatePct}%`, width: 2, background: c.foreground }}/>

          {/* Ticks */}
          <div style={{ position: 'absolute', left: 0, right: 0, top: 'calc(100% + 8px)', height: 14 }}>
            {[
              { p: 0,    n: '576'  },
              { p: 45.9, n: '1000' },
              { p: 76.2, n: '1280' },
              { p: 100,  n: '1500' },
            ].map(t => (
              <span key={t.p} style={{ position: 'absolute', left: `${t.p}%`, transform: 'translateX(-50%)',
                                       font: `400 9.5px/12px ${RDP.type.mono}`, color: c.mutedForeground, fontVariantNumeric: 'tabular-nums' }}>
                <span style={{ position: 'absolute', left: '50%', top: -6, transform: 'translateX(-50%)', width: 1, height: 4, background: c.outlineVariant }}/>
                {t.n}
              </span>
            ))}
          </div>
          <div style={{ position: 'absolute', left: 0, right: 0, top: 'calc(100% + 24px)', height: 12 }}>
            <span style={{ position: 'absolute', left: 0, font: `400 9px/12px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground }}>Pass</span>
            <span style={{ position: 'absolute', right: 0, font: `400 9px/12px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.destructive }}>Drop</span>
          </div>
        </div>
      </div>

      {/* Ladder */}
      <div style={{ marginTop: 8 }}>
        {rows.map((r, i) => {
          const isGate = r.gate;
          const fragBg = r.frag === 'FRAG' ? c.warningContainer : c.accent;
          const fragFg = r.frag === 'FRAG' ? c.warning : c.mutedForeground;
          const statColor = r.tone === 'ok' ? c.success : r.tone === 'bad' ? c.destructive : c.warning;
          return (
            <div key={i} style={{
              display: 'grid', gridTemplateColumns: '18px 50px 38px 1fr auto',
              gap: 8, alignItems: 'center', padding: '7px 8px',
              borderTop: i === 0 ? 'none' : `0.5px solid ${c.divider}`,
              background: isGate ? c.muted : 'transparent',
              borderRadius: isGate ? 6 : 0,
              margin: isGate ? '0 -8px' : 0,
              paddingLeft: isGate ? 16 : 8, paddingRight: isGate ? 16 : 8,
            }}>
              <span style={{ font: `400 10.5px/14px ${RDP.type.mono}`, color: c.mutedForeground, textAlign: 'right' }}>{r.i}</span>
              <span style={{ font: `500 13px/16px ${RDP.type.mono}`, fontVariantNumeric: 'tabular-nums' }}>{r.size}</span>
              <span style={{ font: `400 9px/12px ${RDP.type.mono}`, letterSpacing: 0.3, padding: '1px 5px', borderRadius: 3, background: fragBg, color: fragFg, textAlign: 'center' }}>{r.frag}</span>
              <span style={{ font: `400 10.5px/14px ${RDP.type.mono}`, color: c.mutedForeground, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.echo}</span>
              <span style={{ font: `400 10px/12px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: statColor }}>{r.stat}</span>
            </div>
          );
        })}
      </div>
    </RipDpiCard>
  );
}

// ─────────────────────────────────────────────────────────────
// 4. Port matrix — mobile recomposition: per-target accordion-style rows
// ─────────────────────────────────────────────────────────────
function MobilePortMatrix() {
  const { c } = useTheme();
  const targets = [
    { name: '1.1.1.1',         asn: 'AS13335 · TCP', ports: [
      { p: 22,   v: '—',  tone: 'bad' },
      { p: 53,   v: '8',  tone: 'ok' },
      { p: 80,   v: '12', tone: 'ok' },
      { p: 443,  v: '14', tone: 'ok' },
      { p: 853,  v: '18', tone: 'ok' },
      { p: 8443, v: '22', tone: 'ok' },
    ]},
    { name: '8.8.8.8',         asn: 'AS15169 · TCP', ports: [
      { p: 22,   v: '—',   tone: 'bad' },
      { p: 53,   v: '11',  tone: 'ok' },
      { p: 80,   v: '14',  tone: 'ok' },
      { p: 443,  v: '280', tone: 'warn' },
      { p: 853,  v: '22',  tone: 'ok' },
      { p: 8443, v: '24',  tone: 'ok' },
    ]},
    { name: '9.9.9.9',         asn: 'AS19281 · TCP', ports: [
      { p: 22,   v: '—',   tone: 'bad' },
      { p: 53,   v: '18',  tone: 'ok' },
      { p: 80,   v: '22',  tone: 'ok' },
      { p: 443,  v: '—',   tone: 'bad' },
      { p: 853,  v: '412', tone: 'warn' },
      { p: 8443, v: '—',   tone: 'bad' },
    ]},
    { name: 'smtp.fastmail',   asn: 'AS49224 · TCP', ports: [
      { p: 80,   v: '58', tone: 'ok' },
      { p: 143,  v: '88', tone: 'ok' },
      { p: 443,  v: '72', tone: 'ok' },
      { p: 465,  v: '68', tone: 'ok' },
      { p: 587,  v: '68', tone: 'ok' },
      { p: 993,  v: '82', tone: 'ok' },
    ]},
    { name: 'tunnel.relay',    asn: 'AS13335 · UDP', ports: [
      { p: 53,   v: '38', tone: 'ok' },
      { p: 123,  v: '42', tone: 'ok' },
      { p: 443,  v: '42', tone: 'ok' },
      { p: 1194, v: '—',  tone: 'bad' },
    ]},
  ];
  const fill = (tone) => tone === 'ok' ? c.success : tone === 'warn' ? c.warning : tone === 'bad' ? c.destructive : c.muted;

  return (
    <RipDpiCard>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ font: `500 14px/20px ${RDP.type.sans}` }}>Port reachability</span>
        <span style={{ display: 'inline-flex', gap: 10, font: `400 9.5px/12px ${RDP.type.mono}`, letterSpacing: 0.3, textTransform: 'uppercase', color: c.mutedForeground }}>
          {[
            ['Open',    c.success],
            ['Slow',    c.warning],
            ['Blocked', c.destructive],
          ].map(([lab, col]) => (
            <span key={lab} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: col }}/>{lab}
            </span>
          ))}
        </span>
      </div>

      <div style={{ marginTop: 4 }}>
        {targets.map((t, ti) => (
          <div key={t.name} style={{ padding: '12px 0', borderTop: ti === 0 ? 'none' : `0.5px solid ${c.divider}` }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <span style={{ font: `500 13px/16px ${RDP.type.mono}`, color: c.foreground }}>{t.name}</span>
              <span style={{ font: `400 10.5px/14px ${RDP.type.mono}`, color: c.mutedForeground }}>{t.asn}</span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 6, marginTop: 8 }}>
              {t.ports.map(p => (
                <div key={p.p} style={{
                  borderRadius: 6,
                  padding: '8px 4px',
                  background: fill(p.tone),
                  color: '#fff',
                  display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
                  minHeight: 44,
                }}>
                  <span style={{ font: `400 10px/12px ${RDP.type.mono}`, opacity: 0.85 }}>:{p.p}</span>
                  <span style={{ font: `500 13px/14px ${RDP.type.mono}`, fontVariantNumeric: 'tabular-nums' }}>{p.v}{p.v !== '—' ? <span style={{ font: `400 9px/10px ${RDP.type.mono}`, opacity: 0.7, marginLeft: 2 }}>ms</span> : null}</span>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>

      {/* Summary */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginTop: 12, paddingTop: 12, borderTop: `0.5px solid ${c.divider}` }}>
        {[
          ['Open',    '22 / 28', c.success],
          ['Slow',    '2',       c.warning],
          ['Blocked', '4',       c.destructive],
        ].map(([k, v, col]) => (
          <div key={k} style={{ background: c.muted, padding: '8px 10px', borderRadius: 8 }}>
            <div style={{ font: `400 9.5px/12px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground }}>{k}</div>
            <div style={{ font: `500 15px/18px ${RDP.type.mono}`, color: col, fontVariantNumeric: 'tabular-nums', marginTop: 3 }}>{v}</div>
          </div>
        ))}
      </div>
    </RipDpiCard>
  );
}

// ─────────────────────────────────────────────────────────────
// 5. Censorship signatures
// ─────────────────────────────────────────────────────────────
function MobileSignatures() {
  const { c } = useTheme();
  const sigs = [
    {
      icon: 'SNI', tone: 'bad', name: 'SNI-based blocking', code: 'tls.sni', conf: 0.94,
      desc: 'ClientHello with the target SNI is dropped silently. The same target completes when SNI is rewritten.',
      evidence: [
        { ts: '0.247s', msg: 'tls.client_hello sni=', em: 'youtube.com', tail: ' → no ServerHello (3 attempts)' },
        { ts: '0.812s', msg: 'tls.client_hello sni=', em: 'echo.local',  tail: ' · same 5-tuple → ServerHello 88 ms' },
      ],
    },
    {
      icon: 'RST', tone: 'bad', name: 'TCP reset injection', code: 'tcp.rst', conf: 0.97,
      desc: 'RST arrives before the upstream could have responded. TTL mismatch points to a middlebox between hop 4 and hop 5.',
    },
    {
      icon: 'DNS', tone: 'bad', name: 'DNS response forgery', code: 'dns.poison', conf: 0.91,
      desc: 'Plain-UDP DNS returns an answer that differs from DoH and DoT for the same name.',
    },
    {
      icon: 'QoS', tone: 'warn', name: 'QUIC throttling', code: 'udp.qos', conf: 0.62,
      desc: 'UDP/443 throughput collapses to ≈64 KiB/s after first 2 MiB; TCP/443 to the same host sustains.',
    },
    {
      icon: 'ECH', tone: 'ok', name: 'Encrypted ClientHello accepted', code: 'tls.ech', conf: 0.88,
      desc: 'ECH-wrapped handshake reaches origin; outer SNI is rewritten on path but does not affect the inner handshake.',
    },
  ];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <SectionHeader>Interference signatures · 3 high-confidence</SectionHeader>
      {sigs.map(s => {
        const accent = s.tone === 'bad' ? c.destructive : s.tone === 'warn' ? c.warning : c.success;
        const accentBg = s.tone === 'bad' ? c.destructiveContainer : s.tone === 'warn' ? c.warningContainer : c.successContainer;
        return (
          <RipDpiCard key={s.code}>
            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
              <span style={{ width: 32, height: 32, borderRadius: 6, background: accentBg, color: accent,
                             display: 'grid', placeItems: 'center', font: `500 10.5px/1 ${RDP.type.mono}`, letterSpacing: 0.4, flexShrink: 0 }}>
                {s.icon}
              </span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                  <span style={{ font: `500 14px/18px ${RDP.type.sans}` }}>{s.name}</span>
                  <span style={{ font: `400 10px/14px ${RDP.type.mono}`, padding: '1px 6px', borderRadius: 3, background: c.muted, color: c.mutedForeground }}>{s.code}</span>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
                  <span style={{ font: `500 14px/18px ${RDP.type.mono}`, color: accent, fontVariantNumeric: 'tabular-nums' }}>{s.conf.toFixed(2)}</span>
                  <span style={{ flex: 1, height: 4, background: c.muted, borderRadius: 2, overflow: 'hidden' }}>
                    <span style={{ display: 'block', height: '100%', width: `${s.conf * 100}%`, background: accent }}/>
                  </span>
                  <span style={{ font: `400 9.5px/12px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', color: c.mutedForeground }}>
                    {s.tone === 'ok' ? 'Bypassed' : 'Confidence'}
                  </span>
                </div>
              </div>
            </div>
            <div style={{ font: `400 13px/19px ${RDP.type.sans}`, color: c.mutedForeground }}>{s.desc}</div>
            {s.evidence && (
              <div style={{ padding: '8px 10px', background: c.muted, borderLeft: `2px solid ${accent}`, borderRadius: '0 6px 6px 0' }}>
                {s.evidence.map((e, i) => (
                  <div key={i} style={{ display: 'flex', gap: 8, font: `400 11px/16px ${RDP.type.mono}` }}>
                    <span style={{ color: c.mutedForeground, flexShrink: 0 }}>{e.ts}</span>
                    <span style={{ color: c.foreground, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {e.msg}<span style={{ color: c.destructive }}>{e.em}</span>{e.tail}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </RipDpiCard>
        );
      })}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// 6. DNS resolvers
// ─────────────────────────────────────────────────────────────
function MobileDnsResolvers() {
  const { c } = useTheme();
  const resolvers = [
    { name: 'cloudflare-dns.com',   addr: '1.1.1.1 · 2606:4700:4700::1111', tx: 'DoH',    txTone: 'info',  rtt: '14 ms',    rttTone: 'ok',   pct: 99.8, pctTone: 'ok',   primary: true,  flags: [{t:'ECH', tone:'info'}, {t:'0-RTT'}], action: 'Probe' },
    { name: 'quad9.net',            addr: '9.9.9.9 · 2620:fe::fe',          tx: 'DoT',    txTone: 'neutral', rtt: '142 ms', rttTone: 'warn', pct: 94.2, pctTone: 'warn',                  flags: [{t:'slow p95', tone:'warn'}, {t:'filter'}], action: 'Probe' },
    { name: 'dns.adguard-dns.com',  addr: '94.140.14.14 · 2a10:50c0::ad1:ff', tx: 'DoQ',  txTone: 'success', rtt: '28 ms',  rttTone: 'ok',   pct: 99.4, pctTone: 'ok',                    flags: [{t:'block-ads'}],                action: 'Probe' },
    { name: 'system',               addr: '192.168.1.1 · ISP-assigned',     tx: 'UDP/53', txTone: 'mono',    rtt: 'timeout',rttTone: 'bad',  pct: 62.1, pctTone: 'bad',                   flags: [{t:'poison', tone:'bad'}, {t:'cleartext', tone:'bad'}], action: 'Probe' },
    { name: 'dns.google',           addr: '8.8.8.8 · 2001:4860:4860::8888', tx: 'DoH',    txTone: 'info',    rtt: '22 ms',  rttTone: 'ok',   pct: 99.6, pctTone: 'ok',                    flags: [{t:'ECH', tone:'info'}],         action: 'Promote', actionPrimary: true },
  ];
  const txStyle = (tone) => {
    if (tone === 'info')    return { bg: c.infoContainer,    fg: c.info };
    if (tone === 'success') return { bg: c.successContainer, fg: c.success };
    if (tone === 'mono')    return { bg: c.muted,            fg: c.mutedForeground };
    return                         { bg: c.accent,           fg: c.foreground };
  };
  const flagStyle = (tone) => {
    if (tone === 'bad')  return { bg: c.destructiveContainer, fg: c.destructive };
    if (tone === 'warn') return { bg: c.warningContainer,     fg: c.warning };
    if (tone === 'info') return { bg: c.infoContainer,        fg: c.info };
    return                      { bg: c.muted,                fg: c.foreground };
  };
  const sentinel = (tone) => tone === 'ok' ? c.success : tone === 'warn' ? c.warning : c.destructive;
  const barFill  = (tone) => tone === 'ok' ? c.success : tone === 'warn' ? c.warning : c.destructive;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <SectionHeader>DNS resolver health · last 5 m</SectionHeader>
        <span style={{ font: `400 11px/14px ${RDP.type.mono}`, color: c.mutedForeground }}><span style={{ color: c.foreground }}>312</span> queries</span>
      </div>

      {resolvers.map(r => {
        const tx = txStyle(r.txTone);
        return (
          <RipDpiCard key={r.name}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0, flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ width: 8, height: 8, borderRadius: 9999, background: sentinel(r.rttTone), flexShrink: 0 }}/>
                  <span style={{ font: `500 14px/18px ${RDP.type.sans}`, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.name}</span>
                  {r.primary && (
                    <span style={{ font: `400 9px/12px ${RDP.type.mono}`, padding: '1px 5px', borderRadius: 3, background: c.infoContainer, color: c.info, letterSpacing: 0.4, flexShrink: 0 }}>PRIMARY</span>
                  )}
                </div>
                <div style={{ font: `400 10.5px/14px ${RDP.type.mono}`, color: c.mutedForeground, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.addr}</div>
              </div>
              <span style={{ font: `400 10px/12px ${RDP.type.mono}`, letterSpacing: 0.4, textTransform: 'uppercase', padding: '3px 7px', borderRadius: 3, background: tx.bg, color: tx.fg, flexShrink: 0 }}>{r.tx}</span>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr auto', gap: 10, alignItems: 'center', marginTop: 6 }}>
              <span style={{ font: `500 13px/16px ${RDP.type.mono}`, color: r.rttTone === 'ok' ? c.foreground : r.rttTone === 'warn' ? c.warning : c.destructive, fontVariantNumeric: 'tabular-nums', minWidth: 56 }}>{r.rtt}</span>
              <span style={{ height: 6, background: c.muted, borderRadius: 3, overflow: 'hidden' }}>
                <span style={{ display: 'block', height: '100%', width: `${r.pct}%`, background: barFill(r.pctTone), borderRadius: 3 }}/>
              </span>
              <span style={{ font: `500 12px/14px ${RDP.type.mono}`, color: r.pctTone === 'ok' ? c.foreground : r.pctTone === 'warn' ? c.warning : c.destructive, fontVariantNumeric: 'tabular-nums', minWidth: 44, textAlign: 'right' }}>{r.pct}%</span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, marginTop: 4 }}>
              <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', flex: 1 }}>
                {r.flags.map((f, i) => {
                  const fs = flagStyle(f.tone);
                  return <span key={i} style={{ font: `400 10.5px/14px ${RDP.type.mono}`, padding: '2px 7px', borderRadius: 4, background: fs.bg, color: fs.fg }}>{f.t}</span>;
                })}
              </div>
              <RipDpiButton text={r.action} variant={r.actionPrimary ? 'primary' : 'outline'} style={{ minHeight: 36, padding: '6px 14px', fontSize: 12 }}/>
            </div>
          </RipDpiCard>
        );
      })}

      <WarningBanner
        tone="info"
        title="Promote dns.google as primary"
        message="system is poisoning answers on cleartext UDP/53. Promote dns.google or keep cloudflare-dns.com to remove the leak."
      />
    </div>
  );
}

Object.assign(window, {
  MobileReportSummary, MobileHopTrace, MobileMtuScan,
  MobilePortMatrix, MobileSignatures, MobileDnsResolvers,
});
