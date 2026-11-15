# DGN-1794730805168562: Preserve audited TLS probe evidence

## Objective

Publish attributable TLS and JA3 observations through both diagnostic entry points.

## Ownership

The portfolio task assigns the diagnostics TLS, JA3, and integration lanes. The findings run as separate fix commits.

## Execution

- [ ] DGN-1794730885862088 Preserve captured ClientHello evidence on failure #bug @item:DGN-1794730805168562
- [ ] DGN-1794730895791189 Bind TLS report evidence to its own connection #bug @item:DGN-1794730805168562
- [ ] DGN-1794730896305094 Classify typed TLS response and failure evidence #bug @item:DGN-1794730805168562
- [ ] DGN-1794730896780389 Bind ECH configuration selection and retry to the connected peer #bug @item:DGN-1794730805168562
- [ ] DGN-1794730897256277 Preserve cancellation partial results and ECH-only observations #bug @item:DGN-1794730805168562
- [ ] DGN-1794730897761185 Validate JA3 lengths and reassemble TLS records #bug @item:DGN-1794730805168562
- [ ] DGN-1794730898243492 Report negotiated ALPN and per-attempt TLS measurements #bug @item:DGN-1794730805168562
- [ ] DGN-1794730898711547 Identify standard custom and skipped trust policies #bug @item:DGN-1794730805168562

## Verification

Run per-finding loopback, parser, report and policy regressions. Run affected locked crate tests, the Kotlin policy tests where affected, and the named integration gates in design.md. Record the actual commands and results in verification.md.
