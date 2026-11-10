package com.poyka.ripdpi.services

import java.io.File

/** Owns the persistent Apps Script CA location; the private key stays in app storage. */
class AppsScriptRelayCertificateStore(
    filesDirectory: File,
) {
    val dataDirectory: File = File(filesDirectory, "apps-script-relay")
}
