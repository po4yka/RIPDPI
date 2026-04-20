import java.nio.file.Path

internal object DiagnosticsCatalogDefinitions {
    private val legalSafetyRegistry =
        DiagnosticsCatalogLegalSafetyRegistry(
            rules =
                listOf(
                    DiagnosticsCatalogLegalSafetyRule(
                        domain = "rutor.info",
                        classification = CatalogLegalSafety.UNSAFE,
                        shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                        jurisdictionTag = "ru",
                        ruleId = "ru_client_risk_rutor",
                    ),
                    DiagnosticsCatalogLegalSafetyRule(
                        domain = "rezka.ag",
                        classification = CatalogLegalSafety.UNSAFE,
                        shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                        jurisdictionTag = "ru",
                        ruleId = "ru_client_risk_rezka",
                    ),
                    DiagnosticsCatalogLegalSafetyRule(
                        domain = "shikimori.one",
                        classification = CatalogLegalSafety.UNSAFE,
                        shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                        jurisdictionTag = "ru",
                        ruleId = "ru_client_risk_shikimori",
                    ),
                    DiagnosticsCatalogLegalSafetyRule(
                        domain = "nnmclub.to",
                        classification = CatalogLegalSafety.UNSAFE,
                        shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                        jurisdictionTag = "ru",
                        ruleId = "ru_client_risk_nnmclub",
                    ),
                    DiagnosticsCatalogLegalSafetyRule(
                        domain = "mos-gorsud.co",
                        classification = CatalogLegalSafety.UNSAFE,
                        shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                        jurisdictionTag = "ru",
                        ruleId = "ru_client_risk_impersonation",
                    ),
                    DiagnosticsCatalogLegalSafetyRule(
                        domain = "kinopub.online",
                        classification = CatalogLegalSafety.UNSAFE,
                        shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                        jurisdictionTag = "ru",
                        ruleId = "ru_client_risk_kinopub_online",
                    ),
                    DiagnosticsCatalogLegalSafetyRule(
                        domain = "kino.pub",
                        classification = CatalogLegalSafety.UNSAFE,
                        shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                        jurisdictionTag = "ru",
                        ruleId = "ru_client_risk_kino_pub",
                    ),
                ),
        )

    private val assembler =
        DiagnosticsCatalogAssembler(
            packSource = DefaultDiagnosticsCatalogPackSource,
            profileSource = DefaultDiagnosticsCatalogProfileSource,
            legalSafetyRegistry = legalSafetyRegistry,
            validator =
                DiagnosticsCatalogValidator(
                    legalSafetyRegistry = legalSafetyRegistry,
                    repoRoot = diagnosticsCatalogRepoRoot(),
                    generatedArtifactDirectories = generatedArtifactDirectories,
                    enforceGeneratedArtifactGuard = true,
                ),
            renderer = DiagnosticsCatalogJsonRenderer(),
        )

    internal fun renderCatalog(): String = assembler.renderCatalog()
}

private val generatedArtifactDirectories =
    listOf(
        "core/diagnostics/src/main/assets/diagnostics",
        "diagnostics-contract-fixtures",
    )

private fun diagnosticsCatalogRepoRoot(): Path =
    System
        .getProperty("ripdpi.diagnostics.catalog.repoRoot")
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
        ?: Path.of("").toAbsolutePath().normalize()
