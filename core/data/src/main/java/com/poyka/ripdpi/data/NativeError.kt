package com.poyka.ripdpi.data

import java.io.IOException

sealed class NativeError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class AlreadyRunning(component: String) :
        NativeError("$component is already running")

    class NotRunning(component: String) :
        NativeError("$component is not running")

    class SessionCreationFailed(component: String) :
        NativeError("Native $component session was not created")

    class NativeIoError(message: String, cause: IOException) :
        NativeError(message, cause)
}
