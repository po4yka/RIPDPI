package com.poyka.ripdpi.core

import android.content.Context
import java.io.File

private const val GeoDatabaseDirectoryName = "geo"
private const val GeoipDatabaseFileName = "geoip.db"
private const val GeositeDatabaseFileName = "geosite.db"

data class GeoDatabasePaths(
    val geoDirPath: String,
    val geoipDbPath: String,
    val geositeDbPath: String,
)

fun resolveGeoDatabasePaths(context: Context): GeoDatabasePaths {
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    val geoDir = File(baseDir, GeoDatabaseDirectoryName)
    geoDir.mkdirs()
    return GeoDatabasePaths(
        geoDirPath = geoDir.absolutePath,
        geoipDbPath = geoDir.resolve(GeoipDatabaseFileName).absolutePath,
        geositeDbPath = geoDir.resolve(GeositeDatabaseFileName).absolutePath,
    )
}
