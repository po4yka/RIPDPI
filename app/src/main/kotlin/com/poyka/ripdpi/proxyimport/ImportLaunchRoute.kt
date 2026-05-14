package com.poyka.ripdpi.proxyimport

/**
 * String tokens identifying which import-confirmation destination an inbound intent
 * should open. Carried on the forwarding intent from [ImportHandlerActivity] to
 * [com.poyka.ripdpi.activities.MainActivity] under
 * [ImportHandlerActivity.EXTRA_IMPORT_ROUTE].
 */
object ImportLaunchRoute {
    /** Open the single-profile import-confirmation screen. */
    const val PROFILE_CONFIRM: String = "import/profile_confirm"

    /** Open the subscription-add import-confirmation screen. */
    const val SUBSCRIPTION_CONFIRM: String = "import/subscription_confirm"
}
