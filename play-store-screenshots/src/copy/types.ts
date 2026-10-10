/** Marketing copy. All UI text comes from unchanged Android captures. */
export interface SlideCopy {
  readonly locale: string;
  readonly dir: "ltr" | "rtl";
  readonly headlines: readonly (readonly [string, string])[];
  readonly labels: readonly string[];
  readonly localTools: readonly string[];
  readonly dnsDescription: string;
  readonly strategyDescription: string;
  readonly featureGraphic: { readonly tagline: string };
}
