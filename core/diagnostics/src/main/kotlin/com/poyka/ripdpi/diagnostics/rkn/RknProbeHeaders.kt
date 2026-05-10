package com.poyka.ripdpi.diagnostics.rkn

object RknProbeHeaders {
    // Update at least once per release cycle, or when this is more than six months behind Chrome stable.
    const val CHROME_UA_VERSION = "147.0.0.0"

    fun build(
        identify: Boolean,
        versionName: String = "unknown",
        repoUrl: String = DefaultRepoUrl,
    ): Map<String, String> =
        linkedMapOf(
            "User-Agent" to userAgent(identify = identify, versionName = versionName, repoUrl = repoUrl),
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate, br",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
        )

    private fun userAgent(
        identify: Boolean,
        versionName: String,
        repoUrl: String,
    ): String =
        if (identify) {
            "RIPDPI/$versionName (+$repoUrl)"
        } else {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$CHROME_UA_VERSION Safari/537.36"
        }

    private const val DefaultRepoUrl = "https://github.com/po4yka/RIPDPI"
}
