// ─────────────────────────────────────────────────────────────────────
// SlideCopy: localized marketing strings for the Play Store generator.
//
// Technical tokens (DPI, VPN, DNS, TLS, DoH, DoT, DNSCrypt, QUIC, HTTP,
// TCP, WebRTC, AdGuard, RIPDPI, etc.) stay in Latin per project convention
// and are NOT part of this dictionary.
// ─────────────────────────────────────────────────────────────────────

/** A single headline line. Each entry renders on its own visual line. */
export type HeadlineLines = readonly [string, string] | readonly [string, string, string];

/** One feature card on Slide 2. */
export interface Slide2Card {
  readonly title: string;
  readonly desc: string;
}

/** One slide's copy. */
export interface SlideCopy {
  readonly locale: string;
  readonly dir: "ltr" | "rtl";

  readonly slide1: {
    readonly label: string;
    readonly headline: HeadlineLines;
  };

  readonly slide2: {
    readonly eyebrow: string;
    readonly headline: HeadlineLines;
    readonly cards: readonly [Slide2Card, Slide2Card, Slide2Card];
    readonly bottomBadge: string;
  };

  readonly slide3: {
    readonly label: string;
    readonly headline: HeadlineLines;
    /** Short pill labels surfaced on the left of the slide. */
    readonly pills: readonly [string, string, string];
  };

  readonly slide4: {
    readonly label: string;
    readonly headline: HeadlineLines;
    readonly sectionEncryptedDns: string;
    readonly sectionDpiBypass: string;
    readonly sectionModes: string;
    readonly modeVpn: string;
    readonly modeProxy: string;
    readonly footer: string;
  };

  readonly slide5: {
    readonly label: string;
    readonly headline: HeadlineLines;
  };

  readonly slide6: {
    readonly headline: HeadlineLines;
    readonly features: readonly string[];
    readonly comingSoonLabel: string;
    readonly comingSoon: readonly string[];
  };

  readonly featureGraphic: {
    readonly tagline: string;
  };
}
