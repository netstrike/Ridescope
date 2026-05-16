package com.example.ridescope.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.example.ridescope.data.model.PhoneLocationSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TripFixAccuracyMeters = 35.0
private const val SpeedFixAccuracyMeters = 20.0
private const val StationaryDistanceMeters = 2.5
private const val StationarySpeedThresholdKmh = 6.0
private const val MinDerivedIntervalMs = 500L
private const val MaxDerivedIntervalMs = 5_000L
private const val MaxDerivedJumpMeters = 250.0
private const val SpeedConsistencyToleranceKmh = 25.0
private const val MaxSpeedAccuracyMps = 3.0
private const val MaxBearingAccuracyDeg = 35.0

class PhoneSensorRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    fun gpsTelemetry(): Flow<PhoneLocationSnapshot> = callbackFlow {
        val locationManager = appContext.getSystemService(LocationManager::class.java)

        if (locationManager == null) {
            trySend(PhoneLocationSnapshot(permissionGranted = true))
            close()
            return@callbackFlow
        }

        val providers = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> listOf(LocationManager.GPS_PROVIDER)
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> listOf(LocationManager.NETWORK_PROVIDER)
            else -> emptyList()
        }

        var lastAcceptedLocation: Location? = null
        var tripMeters = 0.0
        var maxSpeedKmh = 0.0
        val altitudeConverter = altitudeConverterOrNull()

        fun snapshotFrom(
            location: Location?,
            previousLocation: Location?,
            fixAvailable: Boolean,
        ): PhoneLocationSnapshot {
            val accuracyMeters = location?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
            val altitudeMeters = if (fixAvailable) {
                location?.let { current ->
                    mslAltitudeMetersOrNull(current, altitudeConverter)
                }
            } else {
                null
            }
            val usingNetworkProvider = location?.provider == LocationManager.NETWORK_PROVIDER
            val speedKmh = location?.let { current ->
                validatedSpeedKmh(
                    current = current,
                    previous = previousLocation,
                    accuracyMeters = accuracyMeters,
                )
            } ?: 0.0
            val headingDeg = location?.let { current ->
                resolvedHeadingDeg(
                    current = current,
                    previous = previousLocation,
                    accuracyMeters = accuracyMeters,
                    speedKmh = speedKmh,
                )
            }

            if (fixAvailable) {
                maxSpeedKmh = max(maxSpeedKmh, speedKmh)
            }

            return PhoneLocationSnapshot(
                permissionGranted = true,
                fixAvailable = fixAvailable,
                usingNetworkProvider = usingNetworkProvider,
                latitudeDeg = location?.latitude,
                longitudeDeg = location?.longitude,
                headingDeg = if (fixAvailable) headingDeg else null,
                speedKmh = if (fixAvailable) speedKmh else 0.0,
                maxSpeedKmh = maxSpeedKmh,
                tripKm = tripMeters / 1000.0,
                altitudeMeters = altitudeMeters,
                accuracyMeters = accuracyMeters,
            )
        }

        fun handleLocation(location: Location) {
            val accuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
            val usableForTrip = accuracyMeters == null || accuracyMeters <= TripFixAccuracyMeters
            val previousLocation = lastAcceptedLocation

            if (usableForTrip) {
                previousLocation?.let { previous ->
                    val elapsedMillis = location.time - previous.time
                    val distanceMeters = previous.distanceTo(location).toDouble()
                    if (elapsedMillis in 500..30_000 && distanceMeters in 0.5..250.0) {
                        tripMeters += distanceMeters
                    }
                }
                lastAcceptedLocation = location
            }

            trySend(snapshotFrom(location, previousLocation, fixAvailable = true))
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocation(location)
            }

            override fun onLocationChanged(locations: MutableList<Location>) {
                locations.lastOrNull()?.let(::handleLocation)
            }

            override fun onProviderDisabled(provider: String) {
                trySend(
                    PhoneLocationSnapshot(
                        permissionGranted = true,
                        fixAvailable = false,
                        usingNetworkProvider = provider == LocationManager.NETWORK_PROVIDER,
                        maxSpeedKmh = maxSpeedKmh,
                        tripKm = tripMeters / 1000.0,
                    )
                )
            }
        }

        if (providers.isEmpty()) {
            trySend(PhoneLocationSnapshot(permissionGranted = true, fixAvailable = false))
            awaitClose {}
            return@callbackFlow
        }

        val locationThread = HandlerThread("RideScopeGpsLocation").apply { start() }
        val locationHandler = Handler(locationThread.looper)

        providers.forEach { provider ->
            locationHandler.post {
                locationManager.getLastKnownLocation(provider)?.let(::handleLocation)
            }
            locationManager.requestLocationUpdates(
                provider,
                1_000L,
                0f,
                listener,
                locationThread.looper,
            )
        }

        awaitClose {
            locationManager.removeUpdates(listener)
            locationThread.quitSafely()
        }
    }.conflate()

    private fun validatedSpeedKmh(
        current: Location,
        previous: Location?,
        accuracyMeters: Double?,
    ): Double {
        val previousAccuracyMeters = previous?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
        val usableCurrentFix = accuracyMeters == null || accuracyMeters <= SpeedFixAccuracyMeters
        val usablePreviousFix = previousAccuracyMeters == null || previousAccuracyMeters <= SpeedFixAccuracyMeters

        val providerSpeedKmh = current
            .takeIf { usableCurrentFix && it.hasSpeed() && hasUsableSpeedAccuracy(it) }
            ?.speed
            ?.toDouble()
            ?.times(3.6)

        val derivedSpeed = previous
            ?.takeIf { usableCurrentFix && usablePreviousFix }
            ?.let { previousLocation ->
                val elapsedMillis = current.time - previousLocation.time
                if (elapsedMillis !in MinDerivedIntervalMs..MaxDerivedIntervalMs) {
                    null
                } else {
                    val distanceMeters = previousLocation.distanceTo(current).toDouble()
                    if (distanceMeters !in 0.0..MaxDerivedJumpMeters) {
                        null
                    } else {
                        distanceMeters to ((distanceMeters / (elapsedMillis / 1000.0)) * 3.6)
                    }
                }
            }

        val movementMeters = derivedSpeed?.first
        val derivedSpeedKmh = derivedSpeed?.second
        val noiseDistanceMeters = max(StationaryDistanceMeters, accuracyMeters ?: 0.0)

        if (movementMeters != null && movementMeters <= noiseDistanceMeters) {
            val providerSuggestsMotion = (providerSpeedKmh ?: 0.0) > StationarySpeedThresholdKmh
            if (!providerSuggestsMotion) {
                return 0.0
            }
        }

        val resolvedSpeedKmh = when {
            providerSpeedKmh != null && derivedSpeedKmh != null -> {
                if (abs(providerSpeedKmh - derivedSpeedKmh) > SpeedConsistencyToleranceKmh) {
                    min(providerSpeedKmh, derivedSpeedKmh)
                } else {
                    (providerSpeedKmh + derivedSpeedKmh) / 2.0
                }
            }
            providerSpeedKmh != null -> providerSpeedKmh
            derivedSpeedKmh != null -> derivedSpeedKmh
            else -> 0.0
        }

        return resolvedSpeedKmh
            .takeUnless { !usableCurrentFix && it < 15.0 }
            ?.coerceIn(0.0, 320.0)
            ?: 0.0
    }

    private fun resolvedHeadingDeg(
        current: Location,
        previous: Location?,
        accuracyMeters: Double?,
        speedKmh: Double,
    ): Double? {
        val previousAccuracyMeters = previous?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
        val usableCurrentFix = accuracyMeters == null || accuracyMeters <= SpeedFixAccuracyMeters
        val usablePreviousFix = previousAccuracyMeters == null || previousAccuracyMeters <= SpeedFixAccuracyMeters
        val providerHeadingDeg = current
            .takeIf { usableCurrentFix && speedKmh >= StationarySpeedThresholdKmh && it.hasBearing() && hasUsableBearingAccuracy(it) }
            ?.bearing
            ?.toDouble()
            ?.normalizeHeadingDeg()
        val derivedHeadingDeg = previous
            ?.takeIf { usableCurrentFix && usablePreviousFix && speedKmh >= StationarySpeedThresholdKmh }
            ?.let { previousLocation ->
                val elapsedMillis = current.time - previousLocation.time
                if (elapsedMillis !in MinDerivedIntervalMs..MaxDerivedIntervalMs) {
                    null
                } else {
                    val distanceMeters = previousLocation.distanceTo(current).toDouble()
                    val minDistanceMeters = max(StationaryDistanceMeters, accuracyMeters ?: 0.0)
                    if (distanceMeters <= minDistanceMeters || distanceMeters > MaxDerivedJumpMeters) {
                        null
                    } else {
                        previousLocation.bearingTo(current).toDouble().normalizeHeadingDeg()
                    }
                }
            }

        return providerHeadingDeg ?: derivedHeadingDeg
    }

    private fun hasUsableSpeedAccuracy(location: Location): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !location.hasSpeedAccuracy()) {
            return true
        }
        return location.speedAccuracyMetersPerSecond.toDouble() <= MaxSpeedAccuracyMps
    }

    private fun hasUsableBearingAccuracy(location: Location): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !location.hasBearingAccuracy()) {
            return true
        }
        return location.bearingAccuracyDegrees.toDouble() <= MaxBearingAccuracyDeg
    }

    private fun mslAltitudeMetersOrNull(
        location: Location,
        altitudeConverter: AltitudeConverter?,
    ): Double? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return location.takeIf { it.hasAltitude() }?.altitude
        }

        if (location.hasMslAltitude()) {
            return location.mslAltitudeMeters
        }

        if (!location.hasAltitude() || altitudeConverter == null) {
            return null
        }

        return try {
            altitudeConverter.addMslAltitudeToLocation(appContext, location)
            location.takeIf { it.hasMslAltitude() }?.mslAltitudeMeters
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun altitudeConverterOrNull(): AltitudeConverter? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }
        return AltitudeConverter()
    }
}

private fun Double.normalizeHeadingDeg(): Double {
    val normalized = this % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}
