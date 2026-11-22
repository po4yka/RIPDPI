internal fun echTransferComparisonProfile() =
    DiagnosticsProfileDefinition(
        id = "ech-transfer-comparison",
        name = "ECH transfer comparison",
        family = CatalogDiagnosticProfileFamily.WEB_CONNECTIVITY,
        intentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
        legalSafety = CatalogLegalSafety.SAFE,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
    )
