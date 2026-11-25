internal fun verifiedHttpsUploadProfile() =
    DiagnosticsProfileDefinition(
        id = "verified-https-upload",
        name = "Verified HTTPS upload",
        family = CatalogDiagnosticProfileFamily.WEB_CONNECTIVITY,
        intentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
        legalSafety = CatalogLegalSafety.SAFE,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
    )
