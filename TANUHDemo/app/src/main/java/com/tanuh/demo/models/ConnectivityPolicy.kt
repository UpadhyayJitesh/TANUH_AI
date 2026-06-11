package com.tanuh.demo.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Centralizes the network rule used by model delivery.
 *
 * Downloads require a validated network and default to unmetered connectivity.
 */
class ConnectivityPolicy(context: Context) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    fun canDownload(allowMetered: Boolean): Boolean {
        val network = connectivityManager.activeNetwork
        if (network == null) {
            Log.w(TAG, "No active network")
            return false
        }
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) {
            Log.w(TAG, "Active network has no capabilities")
            return false
        }
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val allowed = validated && (allowMetered || unmetered)
        Log.i(
            TAG,
            "Network policy: validated=$validated, unmetered=$unmetered, " +
                "allowMetered=$allowMetered, allowed=$allowed",
        )
        return allowed
    }

    companion object {
        private const val TAG = "ConnectivityPolicy"
    }
}
