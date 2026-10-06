package com.poyka.ripdpi.services

internal fun testShellIntentCallbacks(
    reference: () -> com.poyka.ripdpi.data.PauseAuthorityRef,
): ServiceShellIntentCallbacks =
    ServiceShellIntentCallbacks(acceptedStop = { command ->
        when (command) {
            is AcceptedServiceStop.Prepared -> command.reference
            is AcceptedServiceStop.Notification -> reference()
        }
    })
