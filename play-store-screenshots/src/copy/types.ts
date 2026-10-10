/** Marketing copy. All UI text comes from unchanged Android captures. */
export interface SlideCopy {
  readonly locale: string;
  readonly dir: "ltr" | "rtl";
  readonly headlines: readonly (readonly [string, string])[];
  readonly labels: readonly string[];
  readonly descriptions: readonly string[];
  readonly featureGraphic: { readonly tagline: readonly [string, string] };
}
