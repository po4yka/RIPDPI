internal fun transferVolumeComparisonProfile() =
    DiagnosticsProfileDefinition(
        id = "transfer-volume-comparison",
        name = "Short and long HTTPS downloads",
        family = CatalogDiagnosticProfileFamily.WEB_CONNECTIVITY,
        intentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
        legalSafety = CatalogLegalSafety.SAFE,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = true),
    )
