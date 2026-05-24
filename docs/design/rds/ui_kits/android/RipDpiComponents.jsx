/* ui_kits/android/RipDpiComponents.jsx
   Core RipDpi components — JSX recreations of the Compose originals at
   ui/components/. They share visuals only, not the production wiring. */

const RDP = {
  // pulled from ui/theme/Color.kt — RipDpiExtendedColors
  light: {
    background: '#FAFAFA', foreground: '#1A1A1A',
    card: '#FFFFFF', cardForeground: '#1A1A1A',
    muted: '#F5F5F5', mutedForeground: '#575757',
    accent: '#E8E8E8', accentForeground: '#1A1A1A',
    border: '#E0E0E0', cardBorder: '#E8E8E8', divider: '#F0F0F0',
    hairline: '#666666', outline: '#757575', outlineVariant: '#D0D0D0',
    inputBackground: '#F5F5F5',
    success: '#047857', successContainer: '#ECFDF5', successContainerFg: '#064E3B',
    warning: '#B45309', warningContainer: '#FFF7ED', warningContainerFg: '#1A1A1A',
    destructive: '#B91C1C', destructiveContainer: '#FEF2F2', destructiveContainerFg: '#7F1D1D',
    info: '#1D4ED8', infoContainer: '#EFF6FF', infoContainerFg: '#1E3A8A',
    restricted: '#6B7280', restrictedContainer: '#F3F4F6', restrictedContainerFg: '#374151',
  },
  dark: {
    background: '#121212', foreground: '#E8E8E8',
    card: '#1A1A1A', cardForeground: '#E8E8E8',
    muted: '#1E1E1E', mutedForeground: '#A3A3A3',
    accent: '#2A2A2A', accentForeground: '#E8E8E8',
    border: '#2A2A2A', cardBorder: '#2A2A2A', divider: '#222222',
    hairline: '#666666', outline: '#616161', outlineVariant: '#333333',
    inputBackground: '#1E1E1E',
    success: '#34D399', successContainer: '#064E3B', successContainerFg: '#D1FAE5',
    warning: '#FBBF24', warningContainer: '#451A03', warningContainerFg: '#FDE68A',
    destructive: '#F87171', destructiveContainer: '#450A0A', destructiveContainerFg: '#FECACA',
    info: '#60A5FA', infoContainer: '#0C4A6E', infoContainerFg: '#DBEAFE',
    restricted: '#9CA3AF', restrictedContainer: '#1F2937', restrictedContainerFg: '#E5E7EB',
  },
  type: {
    sans: '"Geist Sans", -apple-system, system-ui, sans-serif',
    mono: '"Geist Mono", "JetBrains Mono", monospace',
    pixel: '"Geist Pixel Circle", "Geist Mono", monospace',
  },
  // RipDpiSpacing
  space: { xs: 4, sm: 8, md: 12, lg: 16, xl: 20, xxl: 24, xxxl: 32, section: 40, screen: 48 },
  // RipDpiShapeMetrics
  radius: { xs: 4, sm: 8, md: 10, lg: 12, xl: 16, xlPlus: 20, xxl: 28, xxlPlus: 32, xxxl: 48 },
  // RipDpiMotion durations
  motion: {
    quick: '120ms cubic-bezier(0.2,0,0,1)',
    state: '220ms cubic-bezier(0.2,0,0,1)',
    emphasized: '320ms cubic-bezier(0.05,0.7,0.1,1)',
  },
};

const ThemeCtx = React.createContext({ c: RDP.light, dark: false });
const useTheme = () => React.useContext(ThemeCtx);

function RipDpiTheme({ dark = false, children }) {
  return (
    <ThemeCtx.Provider value={{ c: dark ? RDP.dark : RDP.light, dark }}>
      {children}
    </ThemeCtx.Provider>
  );
}

// ─────────────────────────────────────────────────────────────
// Button — RipDpiButton.kt
// ─────────────────────────────────────────────────────────────
function RipDpiButton({
  text, onClick, variant = 'primary', enabled = true, loading = false,
  leadingIcon, trailingIcon, style = {},
}) {
  const { c } = useTheme();
  const [pressed, setPressed] = React.useState(false);
  const enabledX = enabled && !loading;

  const stateCol = (() => {
    if (!enabledX) {
      return { bg: c.muted, fg: c.mutedForeground, br: 'transparent', bw: 1 };
    }
    switch (variant) {
      case 'secondary':   return { bg: c.muted, fg: c.foreground, br: 'transparent', bw: 1 };
      case 'outline':     return { bg: 'transparent', fg: c.foreground, br: c.border, bw: 1 };
      case 'ghost':       return { bg: 'transparent', fg: c.foreground, br: 'transparent', bw: 1 };
      case 'destructive': return { bg: c.destructive, fg: '#fff', br: 'transparent', bw: 1 };
      case 'primary':
      default:            return { bg: c.foreground, fg: c.background, br: 'transparent', bw: 1 };
    }
  })();

  return (
    <button
      type="button"
      onClick={enabledX ? onClick : undefined}
      onPointerDown={() => setPressed(true)}
      onPointerUp={() => setPressed(false)}
      onPointerLeave={() => setPressed(false)}
      style={{
        minHeight: 48, padding: '10px 20px', borderRadius: RDP.radius.xl,
        border: `${stateCol.bw}px solid ${stateCol.br}`,
        background: stateCol.bg, color: stateCol.fg,
        font: `500 15px/20px ${RDP.type.sans}`,
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
        cursor: enabledX ? 'pointer' : 'default',
        opacity: enabledX ? 1 : 0.5,
        transform: pressed ? 'scale(0.98)' : 'scale(1)',
        transition: `background-color ${RDP.motion.state}, color ${RDP.motion.state}, border-color ${RDP.motion.quick}, transform ${RDP.motion.quick}`,
        ...style,
      }}
    >
      {loading && <RDPSpinner color={stateCol.fg} />}
      {!loading && leadingIcon}
      <span style={{ whiteSpace: 'nowrap' }}>{text}</span>
      {trailingIcon}
    </button>
  );
}

function RDPSpinner({ color = 'currentColor', size = 14 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round" style={{ animation: 'rdpSpin 0.8s linear infinite' }}>
      <circle cx="12" cy="12" r="9" strokeDasharray="36 60" />
    </svg>
  );
}

// ─────────────────────────────────────────────────────────────
// IconButton
// ─────────────────────────────────────────────────────────────
function RipDpiIconButton({ icon, onClick, variant = 'ghost', size = 48 }) {
  const { c } = useTheme();
  const [pressed, setPressed] = React.useState(false);
  const styles = {
    ghost:   { bg: 'transparent', br: 'transparent' },
    outline: { bg: 'transparent', br: c.border },
    tonal:   { bg: c.muted, br: 'transparent' },
    solid:   { bg: c.foreground, br: 'transparent' },
  }[variant];
  return (
    <button type="button" onClick={onClick}
      onPointerDown={() => setPressed(true)} onPointerUp={() => setPressed(false)} onPointerLeave={() => setPressed(false)}
      style={{
        width: size, height: size, borderRadius: RDP.radius.xl,
        border: `1px solid ${styles.br}`, background: styles.bg,
        color: variant === 'solid' ? c.background : c.foreground,
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        cursor: 'pointer', transform: pressed ? 'scale(0.96)' : 'scale(1)',
        transition: `transform ${RDP.motion.quick}, background-color ${RDP.motion.state}`,
      }}
    >
      {icon}
    </button>
  );
}

// ─────────────────────────────────────────────────────────────
// Card — RipDpiCard.kt
// ─────────────────────────────────────────────────────────────
function RipDpiCard({ variant = 'outlined', onClick, children, style = {} }) {
  const { c } = useTheme();
  let bg, border;
  switch (variant) {
    case 'tonal':    bg = c.muted; border = 'transparent'; break;
    case 'elevated': bg = c.card;  border = c.border; break;
    case 'status':   bg = c.warningContainer; border = c.warning; break;
    case 'outlined':
    default:         bg = c.card;  border = c.cardBorder;
  }
  return (
    <div
      onClick={onClick}
      style={{
        background: bg, color: c.cardForeground,
        border: `1px solid ${border}`, borderRadius: RDP.radius.xl,
        padding: RDP.space.lg, cursor: onClick ? 'pointer' : 'default',
        display: 'flex', flexDirection: 'column', gap: RDP.space.sm,
        ...style,
      }}
    >
      {children}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Chip — RipDpiChip.kt
// ─────────────────────────────────────────────────────────────
function RipDpiChip({ text, selected = false, enabled = true, onClick, leadingIcon }) {
  const { c } = useTheme();
  return (
    <button type="button" onClick={enabled ? onClick : undefined} style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '6px 17px', minHeight: 32,
      background: selected ? c.foreground : 'transparent',
      color: selected ? c.background : c.foreground,
      border: `1px solid ${selected ? c.foreground : c.border}`,
      borderRadius: RDP.radius.lg,
      font: `500 14px/20px ${RDP.type.sans}`,
      cursor: enabled ? 'pointer' : 'default', opacity: enabled ? 1 : 0.4,
      transition: `background-color ${RDP.motion.state}, color ${RDP.motion.state}, border-color ${RDP.motion.state}`,
    }}>
      {selected && (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="20 6 9 17 4 12"/>
        </svg>
      )}
      {leadingIcon}
      {text}
    </button>
  );
}

// ─────────────────────────────────────────────────────────────
// Switch — RipDpiSwitch.kt
// ─────────────────────────────────────────────────────────────
function RipDpiSwitch({ checked, onChange, enabled = true, label, helperText, errorText }) {
  const { c } = useTheme();
  const dis = !enabled;
  const trk = checked ? c.foreground : c.outlineVariant;
  const thumb = checked ? c.background : '#fff';
  const left = checked ? 24 : 4;

  const control = (
    <span onClick={() => enabled && onChange?.(!checked)} style={{
      display: 'inline-block', position: 'relative',
      width: 52, height: 32, borderRadius: 9999,
      background: trk, transition: `background-color ${RDP.motion.state}`,
      cursor: dis ? 'default' : 'pointer', opacity: dis ? 0.38 : 1,
    }}>
      <span style={{
        position: 'absolute', top: 4, left, width: 24, height: 24, borderRadius: 9999,
        background: thumb, boxShadow: '0 1px 2px rgba(0,0,0,0.2)',
        transition: `left ${RDP.motion.state}`,
      }}/>
    </span>
  );

  if (!label && !helperText && !errorText) return control;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minHeight: 52 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {label && (
            <div style={{ font: `400 14px/20px ${RDP.type.sans}`, color: errorText ? c.destructive : c.foreground }}>{label}</div>
          )}
          {(helperText || errorText) && (
            <div style={{ font: `400 12px/16px ${RDP.type.sans}`, color: errorText ? c.destructive : c.mutedForeground }}>
              {errorText || helperText}
            </div>
          )}
        </div>
        {control}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// TextField — RipDpiTextField.kt
// ─────────────────────────────────────────────────────────────
function RipDpiTextField({ value, onChange, label, placeholder, helperText, errorText, enabled = true, mono = true }) {
  const { c } = useTheme();
  const [focused, setFocused] = React.useState(false);
  const borderColor = errorText ? c.destructive : (focused ? c.foreground : c.border);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {label && (
        <div style={{ font: `500 12px/16px ${RDP.type.sans}`, color: c.mutedForeground }}>{label}</div>
      )}
      <input
        type="text"
        value={value} onChange={e => onChange?.(e.target.value)}
        placeholder={placeholder}
        disabled={!enabled}
        onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
        style={{
          minHeight: 48, padding: focused ? '13px 18px' : '14px 17px',
          border: `1px solid ${borderColor}`, borderRadius: RDP.radius.xl,
          background: c.inputBackground, color: c.foreground,
          font: `400 14px/20px ${mono ? RDP.type.mono : RDP.type.sans}`,
          outline: 'none', opacity: enabled ? 1 : 0.38,
          transition: `border-color ${RDP.motion.quick}`,
        }}
      />
      {(helperText || errorText) && (
        <div style={{ font: `400 12px/16px ${RDP.type.sans}`, color: errorText ? c.destructive : c.mutedForeground }}>
          {errorText || helperText}
        </div>
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// StatusIndicator — StatusIndicator.kt
// ─────────────────────────────────────────────────────────────
function StatusIndicator({ label, tone = 'active' }) {
  const { c } = useTheme();
  const colorFor = (t) => ({
    active: c.foreground, idle: c.mutedForeground, warning: c.warning, error: c.destructive,
  }[t]);
  const col = colorFor(tone);
  let glyph;
  if (tone === 'active') glyph = <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 9999, background: col }}/>;
  else if (tone === 'idle')  glyph = <span style={{ display: 'inline-block', width: 9, height: 9, background: col, transform: 'rotate(45deg)' }}/>;
  else if (tone === 'warning') glyph = <span style={{ display:'inline-block', width: 0, height: 0, borderLeft: '5px solid transparent', borderRight: '5px solid transparent', borderBottom: `10px solid ${col}` }}/>;
  else glyph = <span style={{ display: 'inline-block', width: 8, height: 8, background: col }}/>;

  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, font: `500 13px/18px ${RDP.type.mono}`, color: c.foreground }}>
      {glyph}{label}
    </span>
  );
}

// ─────────────────────────────────────────────────────────────
// WarningBanner — WarningBanner.kt
// ─────────────────────────────────────────────────────────────
function WarningBanner({ title, message, tone = 'warning', onDismiss }) {
  const { c } = useTheme();
  const map = {
    warning:     { bg: c.warningContainer,     fg: c.warningContainerFg,     border: c.warning,     ic: c.warning,     ifg: '#fff' },
    error:       { bg: c.destructiveContainer, fg: c.destructiveContainerFg, border: c.destructive, ic: c.destructive, ifg: '#fff' },
    info:        { bg: c.infoContainer,        fg: c.infoContainerFg,        border: c.info,        ic: c.info,        ifg: '#fff' },
    restricted:  { bg: c.restrictedContainer,  fg: c.restrictedContainerFg,  border: c.restricted,  ic: c.restricted,  ifg: '#fff' },
  };
  const m = map[tone];
  const icon = {
    warning: <path d="M12 2L1 21h22z M11 16h2v2h-2z M11 10h2v5h-2z"/>,
    error:   <g><circle cx="12" cy="12" r="10"/><path fill="white" d="M11 7h2v6h-2zM11 15h2v2h-2z"/></g>,
    info:    <g><circle cx="12" cy="12" r="10"/><path fill="white" d="M11 10h2v7h-2zM11 7h2v2h-2z"/></g>,
    restricted: <path d="M18 8h-1V6a5 5 0 10-10 0v2H6a2 2 0 00-2 2v10a2 2 0 002 2h12a2 2 0 002-2V10a2 2 0 00-2-2zm-7 9a2 2 0 110-4 2 2 0 010 4zm3.1-9H8.9V6a3.1 3.1 0 016.2 0z"/>,
  }[tone];
  return (
    <div style={{
      display: 'flex', gap: 12, padding: 16, alignItems: 'flex-start',
      background: m.bg, color: m.fg, border: `1px solid ${m.border}`, borderRadius: RDP.radius.xl,
    }}>
      <div style={{ width: 28, height: 28, borderRadius: 9999, background: m.ic, color: m.ifg, display: 'grid', placeItems: 'center', flexShrink: 0 }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">{icon}</svg>
      </div>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <div style={{ font: `500 14px/20px ${RDP.type.sans}` }}>{title}</div>
        <div style={{ font: `400 14px/20px ${RDP.type.sans}` }}>{message}</div>
      </div>
      {onDismiss && (
        <button type="button" onClick={onDismiss} style={{ width: 28, height: 28, background: 'transparent', border: 'none', cursor: 'pointer', color: 'inherit' }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M18 6L6 18 M6 6l12 12"/></svg>
        </button>
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// MetricPill — RipDpiMetricPill.kt
// ─────────────────────────────────────────────────────────────
function MetricPill({ label, tone = 'neutral', icon }) {
  const { c } = useTheme();
  const styles = {
    neutral: { bg: c.muted, fg: c.foreground },
    success: { bg: c.successContainer, fg: c.successContainerFg },
    warning: { bg: c.warningContainer, fg: c.warningContainerFg },
    danger:  { bg: c.destructiveContainer, fg: c.destructiveContainerFg },
  }[tone];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '4px 10px', borderRadius: 9999,
      background: styles.bg, color: styles.fg,
      font: `400 12px/16px ${RDP.type.mono}`,
    }}>
      {icon}
      {label}
    </span>
  );
}

// ─────────────────────────────────────────────────────────────
// SettingsRow — SettingsRow.kt
// ─────────────────────────────────────────────────────────────
function SettingsRow({ title, subtitle, value, trailing, onClick }) {
  const { c } = useTheme();
  const hasSubtitle = !!subtitle;
  return (
    <div onClick={onClick} style={{
      display: 'grid', gridTemplateColumns: '1fr auto',
      gap: 16, padding: '14px 0',
      minHeight: hasSubtitle ? 68 : 52,
      alignItems: 'center', cursor: onClick ? 'pointer' : 'default',
    }}>
      <div>
        <div style={{ font: `500 14px/20px ${RDP.type.sans}`, color: c.foreground }}>{title}</div>
        {subtitle && (
          <div style={{ font: `400 12px/16px ${RDP.type.sans}`, color: c.mutedForeground, marginTop: 2 }}>{subtitle}</div>
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        {value && (
          <span style={{ font: `400 14px/20px ${RDP.type.mono}`, color: c.mutedForeground }}>{value}</span>
        )}
        {trailing}
      </div>
    </div>
  );
}

function SectionHeader({ children }) {
  const { c } = useTheme();
  return (
    <div style={{
      font: `500 13px/18px ${RDP.type.sans}`,
      letterSpacing: 0.72, textTransform: 'uppercase',
      color: c.mutedForeground,
      padding: '12px 0 8px',
    }}>{children}</div>
  );
}

function Hairline({ style = {} }) {
  return <div style={{ borderTop: '0.5px solid #666666', opacity: 0.6, ...style }}/>;
}

// ─────────────────────────────────────────────────────────────
// TopAppBar — RipDpiTopAppBar.kt + HomeChrome.kt
// ─────────────────────────────────────────────────────────────
function RipDpiTopAppBar({ title = 'RIPDPI', subtitle, brand = true, trailing }) {
  const { c } = useTheme();
  return (
    <div style={{
      minHeight: 72, padding: '16px 20px',
      display: 'flex', alignItems: 'center', gap: 12,
      background: c.background, color: c.foreground,
    }}>
      {brand && (
        <div style={{
          width: 32, height: 32, borderRadius: 9999,
          background: c.foreground, color: c.background,
          display: 'grid', placeItems: 'center',
          font: `500 12px/16px ${RDP.type.sans}`,
        }}>R</div>
      )}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div style={{ font: `400 28px/32px ${RDP.type.pixel}`, letterSpacing: 0.8 }}>{title}</div>
        {subtitle && (
          <div style={{ font: `500 13px/18px ${RDP.type.mono}`, color: c.mutedForeground }}>{subtitle}</div>
        )}
      </div>
      {trailing}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// BottomNavBar — BottomNavBar.kt
// ─────────────────────────────────────────────────────────────
function BottomNavBar({ selected, onSelect, items }) {
  const { c } = useTheme();
  return (
    <div style={{
      display: 'grid', gridTemplateColumns: `repeat(${items.length}, 1fr)`,
      height: 64, borderTop: `1px solid ${c.divider}`, background: c.card,
    }}>
      {items.map(it => {
        const isSel = it.key === selected;
        return (
          <button key={it.key} type="button" onClick={() => onSelect(it.key)}
            style={{
              border: 'none', background: 'transparent', cursor: 'pointer',
              display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
              gap: 4, paddingTop: 8, color: isSel ? c.foreground : c.mutedForeground, position: 'relative',
              transition: `color ${RDP.motion.state}`,
            }}>
            {isSel && (
              <span style={{ position: 'absolute', top: 10, width: 64, height: 28, borderRadius: 9999, background: c.accent }}/>
            )}
            <span style={{ position: 'relative', zIndex: 1, display: 'grid', placeItems: 'center' }}>{it.icon}</span>
            <span style={{ font: `500 12px/16px ${RDP.type.sans}`, position: 'relative', zIndex: 1 }}>{it.label}</span>
          </button>
        );
      })}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// ConnectionActuator — RipDpiConnectionActuator.kt
// Bespoke draggable connect/disconnect control.
// ─────────────────────────────────────────────────────────────
function ConnectionActuator({ connected, onChange }) {
  const { c } = useTheme();
  const railRef = React.useRef(null);
  const [dragX, setDragX] = React.useState(null);
  const carriageWidth = 128;

  React.useEffect(() => {
    if (dragX === null) return;
    function move(e) {
      const rect = railRef.current.getBoundingClientRect();
      const x = (e.touches?.[0]?.clientX ?? e.clientX) - rect.left - carriageWidth / 2;
      setDragX(Math.max(0, Math.min(rect.width - carriageWidth, x)));
    }
    function up() {
      const rect = railRef.current.getBoundingClientRect();
      const travel = rect.width - carriageWidth;
      const frac = (dragX ?? 0) / travel;
      if (!connected && frac > 0.72) onChange?.(true);
      else if (connected && frac < 0.28) onChange?.(false);
      setDragX(null);
    }
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
  }, [dragX, connected, onChange]);

  const baseLeft = connected ? '100%' : '0%';
  const baseTransform = connected ? `translateX(-${carriageWidth}px)` : 'translateX(0)';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div ref={railRef} style={{ position: 'relative', height: 116, padding: '32px 0' }}>
        <div style={{
          position: 'absolute', left: 0, right: 0, top: '50%', transform: 'translateY(-50%)',
          height: 48, background: c.muted, border: `1px solid ${c.border}`,
          borderRadius: 9999, display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '0 24px', font: `500 12px/16px ${RDP.type.mono}`, color: c.mutedForeground,
        }}>
          {/* Hint text sits in the unoccupied half of the rail. */}
          <span style={{ visibility: connected ? 'visible' : 'hidden' }}>← SLIDE TO DISCONNECT</span>
          <span style={{ visibility: connected ? 'hidden' : 'visible' }}>SLIDE TO CONNECT →</span>
        </div>
        <div
          onPointerDown={(e) => {
            const rect = railRef.current.getBoundingClientRect();
            setDragX((e.touches?.[0]?.clientX ?? e.clientX) - rect.left - carriageWidth / 2);
          }}
          style={{
            position: 'absolute', top: '50%',
            left: dragX !== null ? dragX : baseLeft,
            transform: dragX !== null ? 'translateY(-50%)' : `translateY(-50%) ${baseTransform}`,
            transition: dragX === null ? `left 220ms cubic-bezier(0.05,0.7,0.1,1), transform 220ms cubic-bezier(0.05,0.7,0.1,1)` : 'none',
            width: carriageWidth, height: 52, borderRadius: 9999,
            background: connected ? c.success : c.foreground,
            color: connected ? '#fff' : c.background,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            font: `500 15px/20px ${RDP.type.sans}`, cursor: 'grab', userSelect: 'none',
            touchAction: 'none',
          }}>
          <span style={{ display: 'flex', gap: 2 }}>
            {[0,1,2,3].map(i => <i key={i} style={{ width: 2, height: 20, background: 'currentColor', opacity: 0.5, borderRadius: 2 }}/>)}
          </span>
          {connected ? 'CONNECTED' : 'CONNECT'}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  RDP, RipDpiTheme, useTheme,
  RipDpiButton, RipDpiIconButton, RipDpiCard, RipDpiChip, RipDpiSwitch, RipDpiTextField,
  StatusIndicator, WarningBanner, MetricPill,
  SettingsRow, SectionHeader, Hairline,
  RipDpiTopAppBar, BottomNavBar, ConnectionActuator, RDPSpinner,
});
