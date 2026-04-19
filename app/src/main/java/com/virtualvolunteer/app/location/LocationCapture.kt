package com.virtualvolunteer.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Best-effort current location using Fused Location Provider.
 * Returns null when permission is missing or location is unavailable.
 */
object LocationCapture {

    suspend fun tryGetCurrentLocation(context: Context): Location? {
        val app = context.applicationContext
        if (
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val client = LocationServices.getFusedLocationProviderClient(app)
        val cts = CancellationTokenSource()
        return try {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
        } catch (_: Exception) {
            // Timeouts / settings / missing providers: graceful fallback
            suspendCoroutine { cont ->
                client.lastLocation
                    .addOnSuccessListener { loc: Location? -> cont.resume(loc) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }
    }
}
