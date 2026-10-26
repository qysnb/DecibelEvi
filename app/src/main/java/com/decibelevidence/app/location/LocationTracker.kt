package com.decibelevidence.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.os.Looper
import androidx.core.content.ContextCompat
import com.decibelevidence.app.model.LocationSnapshot

class LocationTracker(private val context: Context, private val onLocation: (LocationSnapshot) -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val state = LocationState()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val approximate = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            val sample = LocationSnapshot(location.latitude, location.longitude, location.accuracy,
                location.elapsedRealtimeNanos, location.time, approximate = approximate)
            if (sample.timeNs > SystemClock.elapsedRealtimeNanos() || !state.accept(sample)) return
            onLocation(sample)

        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    @Suppress("MissingPermission")
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        stop()
        state.start()
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (provider in manager.allProviders) manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            }
        }
    }
    fun stop() { state.stop(); runCatching { manager.removeUpdates(listener) } }
    fun release() { stop() }
}
