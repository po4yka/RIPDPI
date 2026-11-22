internal fun http3TransferComparisonProfile() =
    DiagnosticsProfileDefinition(
        id = "http3-transfer-comparison",
        name = "HTTPS/TCP and HTTP/3 comparison",
        family = CatalogDiagnosticProfileFamily.WEB_CONNECTIVITY,
        intentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
        legalSafety = CatalogLegalSafety.SAFE,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = true),
    )
