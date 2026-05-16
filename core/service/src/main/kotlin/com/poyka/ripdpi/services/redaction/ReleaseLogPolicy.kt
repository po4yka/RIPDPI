package com.poyka.ripdpi.services.redaction

/**
 * Decides whether a log line tagged with a [SensitiveTag] should be emitted.
 *
 * Release builds suppress all sensitive-tagged lines; debug builds allow them.
 */
object ReleaseLogPolicy {
    fun shouldEmit(
        tag: SensitiveTag,
        buildType: BuildType,
    ): Boolean =
        when {
            buildType == BuildType.Release -> false

            tag == SensitiveTag.Uuid ||
                tag == SensitiveTag.ShortId ||
                tag == SensitiveTag.Password ||
                tag == SensitiveTag.SubscriptionToken ||
                tag == SensitiveTag.Endpoint -> true

            else -> true
        }
}
