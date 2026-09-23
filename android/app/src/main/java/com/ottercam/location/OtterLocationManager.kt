package com.kylescholz.otterspotter.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

class OtterLocationManager(private val context: Context) {

    companion object {
        private const val TAG = "OtterLocationManager"

        @Volatile
        private var instance: OtterLocationManager? = null

        fun getInstance(context: Context): OtterLocationManager {
            return instance ?: synchronized(this) {
                instance ?: OtterLocationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    var lastKnownLocation: Location? = null
        private set

    var onLocationChanged: ((Location) -> Unit)? = null
    private var isTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastKnownLocation = location
            Log.d(TAG, "Acquired high-accuracy location fix: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
            onLocationChanged?.invoke(location)
        }

        override fun onLocationAvailability(avail: LocationAvailability) {
            Log.d(TAG, "Location availability: isLocationAvailable=${avail.isLocationAvailable}")
        }
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot start tracking: Location permissions not granted")
            return
        }

        if (isTracking) return
        isTracking = true

        // 1. Immediately request cached/last known location for instantaneous availability
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                lastKnownLocation = loc
                Log.d(TAG, "Initial lastKnownLocation: lat=${loc.latitude}, lon=${loc.longitude}, acc=${loc.accuracy}m")
                onLocationChanged?.invoke(loc)
            }
        }.addOnFailureListener { e ->
            Log.w(TAG, "Failed retrieving initial lastLocation: ${e.message}")
        }

        // 2. Continuous high-accuracy GNSS/GPS updates while camera is active
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.i(TAG, "Started continuous high-accuracy location updates")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates: ${e.message}", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            fusedClient.removeLocationUpdates(locationCallback)
            Log.i(TAG, "Stopped location updates")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping location updates: ${e.message}")
        } finally {
            isTracking = false
        }
    }

    fun getCurrentLocation(): Location? {
        return lastKnownLocation
    }
}
