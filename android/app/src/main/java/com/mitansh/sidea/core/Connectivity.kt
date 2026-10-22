package com.mitansh.sidea.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object Connectivity {
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.isValidatedInternet()
    }

    fun observe(context: Context) = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) { trySend(false); close(); return@callbackFlow }
        var currentNetwork = manager.activeNetwork
        // Seed outside callbacks. Querying ConnectivityManager inside callbacks can return
        // stale capabilities/default-network data, especially while a network is being lost.
        trySend(manager.getNetworkCapabilities(currentNetwork)?.isValidatedInternet() == true)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == currentNetwork) trySend(capabilities.isValidatedInternet())
            }
            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    currentNetwork = null
                    trySend(false)
                }
            }
            override fun onUnavailable() { trySend(false) }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun NetworkCapabilities.isValidatedInternet() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
