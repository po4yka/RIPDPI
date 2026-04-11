package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface CloudflareMasqueGeohashResolver {
    suspend fun resolveHeaderValue(): String?
}

@Singleton
class AndroidCloudflareMasqueGeohashResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CloudflareMasqueGeohashResolver {
        override suspend fun resolveHeaderValue(): String? {
            if (!hasCoarseLocationPermission()) {
                return null
            }
            val location = bestLastKnownLocation() ?: return null
            val geohash = encodeGeohash(location.latitude, location.longitude, precision = 7)
            val countryCode = resolveCountryCode(location).ifBlank { Locale.getDefault().country }
            return if (geohash.isBlank() || countryCode.isBlank()) {
                null
            } else {
                "$geohash-${countryCode.uppercase(Locale.US)}"
            }
        }

        private fun hasCoarseLocationPermission(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        @Suppress("MissingPermission")
        private fun bestLastKnownLocation(): Location? {
            val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
            val providerNames =
                buildList {
                    add(LocationManager.GPS_PROVIDER)
                    add(LocationManager.NETWORK_PROVIDER)
                    add(LocationManager.PASSIVE_PROVIDER)
                    addAll(locationManager.getProviders(true))
                }.distinct()
            return providerNames
                .mapNotNull { provider ->
                    runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }.maxByOrNull(Location::getTime)
        }

        @Suppress("DEPRECATION")
        private fun resolveCountryCode(location: Location): String {
            if (!Geocoder.isPresent()) {
                return ""
            }
            val geocoder = Geocoder(context, Locale.US)
            return runCatching {
                geocoder
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
                    ?.countryCode
                    .orEmpty()
            }.getOrDefault("")
        }

        private fun encodeGeohash(
            latitude: Double,
            longitude: Double,
            precision: Int,
        ): String {
            var latRange = -90.0 to 90.0
            var lonRange = -180.0 to 180.0
            var bit = 0
            var ch = 0
            var evenBit = true
            val output = StringBuilder(precision)

            while (output.length < precision) {
                if (evenBit) {
                    val mid = (lonRange.first + lonRange.second) / 2.0
                    if (longitude >= mid) {
                        ch = ch or (1 shl (4 - bit))
                        lonRange = mid to lonRange.second
                    } else {
                        lonRange = lonRange.first to mid
                    }
                } else {
                    val mid = (latRange.first + latRange.second) / 2.0
                    if (latitude >= mid) {
                        ch = ch or (1 shl (4 - bit))
                        latRange = mid to latRange.second
                    } else {
                        latRange = latRange.first to mid
                    }
                }
                evenBit = !evenBit
                if (bit < 4) {
                    bit += 1
                } else {
                    output.append(Base32Alphabet[ch])
                    bit = 0
                    ch = 0
                }
            }
            return output.toString()
        }

        private companion object {
            const val Base32Alphabet = "0123456789bcdefghjkmnpqrstuvwxyz"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudflareMasqueGeohashResolverModule {
    @Binds
    @Singleton
    abstract fun bindCloudflareMasqueGeohashResolver(
        resolver: AndroidCloudflareMasqueGeohashResolver,
    ): CloudflareMasqueGeohashResolver
}
