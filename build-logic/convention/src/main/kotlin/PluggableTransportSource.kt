data class PluggableTransportSource(
    val id: String,
    val sourceType: String,
    val repoUrl: String?,
    val commit: String?,
    val goToolchain: String?,
    val goLinkerFlags: List<String>,
    val cgoEnabled: Boolean,
    val packagePath: String?,
    val packageName: String?,
    val sourceBinaryName: String,
    val outputNames: List<String>,
)
