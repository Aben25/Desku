package com.deskbuddy.kiosk

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Joining Wi-Fi from inside Desku, so the locked kiosk never needs Android's Settings. A device
 * owner may still add and enable networks directly (ordinary apps lost that in Android 10).
 * Covers open, WPA2 (PSK) and WPA3 (SAE) networks. Passwords go straight to Android's Wi-Fi
 * config and are never logged or stored by Desku.
 */
class WifiSetup(private val activity: Activity) {

    data class Network(val ssid: String, val bars: Int, val secure: Boolean, val wpa3: Boolean)

    private val ctx: Context = activity.applicationContext
    private val wifi = ctx.getSystemService(WifiManager::class.java)
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)

    /** Only the device owner can add networks itself; otherwise the system Wi-Fi screen is used. */
    val canJoinDirectly: Boolean get() = KioskPolicy.isOwner(activity)

    /** Turns Wi-Fi and location on, and grants what scanning needs (device owner only). */
    fun prepare() {
        if (!canJoinDirectly) return
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(ctx, DeskuAdmin::class.java)
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        perms.forEach { p ->
            runCatching { dpm.setPermissionGrantState(admin, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
                .onFailure { Log.w(TAG, "grant $p failed", it) }
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching { dpm.setLocationEnabled(admin, true) }
        @Suppress("DEPRECATION")
        runCatching { if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true }
    }

    /** Nearby networks, strongest first, one row per name. Empty if scanning isn't allowed. */
    suspend fun scan(): List<Network> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        runCatching { wifi.startScan() }
        runCatching {
            @Suppress("MissingPermission")
            wifi.scanResults
                .mapNotNull { r ->
                    @Suppress("DEPRECATION")
                    val name = r.SSID?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val caps = r.capabilities.orEmpty()
                    Network(
                        ssid = name,
                        bars = WifiManager.calculateSignalLevel(r.level, 4),
                        secure = listOf("WPA", "WEP", "SAE", "PSK", "EAP").any { caps.contains(it) },
                        wpa3 = caps.contains("SAE") && !caps.contains("PSK"),
                    )
                }
                .groupBy { it.ssid }.map { (_, same) -> same.maxBy { it.bars } }
                .sortedByDescending { it.bars }
        }.getOrDefault(emptyList())
    }

    /** The network the phone is on now, or null. */
    fun currentNetwork(): String? {
        if (!onWifi()) return null
        @Suppress("DEPRECATION")
        val ssid = runCatching { wifi.connectionInfo.ssid }.getOrNull()?.trim('"')
        return ssid?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID } ?: "Wi-Fi"
    }

    /**
     * Adds the network and switches to it. Returns null on success, or a short reason to show.
     * [wpa3] comes from the scan; for a typed-in name it's tried as WPA2 first.
     */
    @Suppress("DEPRECATION")
    suspend fun join(ssid: String, password: String, wpa3: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!canJoinDirectly) return@withContext "Joining Wi-Fi from Desku needs the locked kiosk mode."
        if (password.isNotEmpty() && password.length < 8) return@withContext "Wi-Fi passwords are at least 8 characters."
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            when {
                password.isEmpty() -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                wpa3 && Build.VERSION.SDK_INT >= 30 -> {
                    setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE)
                    preSharedKey = "\"$password\""
                }
                else -> preSharedKey = "\"$password\""
            }
        }
        val id = runCatching { wifi.addNetwork(config) }.getOrDefault(-1)
        if (id == -1) return@withContext "Android wouldn't add that network."
        runCatching {
            wifi.disconnect()
            wifi.enableNetwork(id, true)
            wifi.reconnect()
        }
        // Wait up to ~20 s for the phone to actually be on it.
        repeat(40) {
            delay(500)
            val now = currentNetwork()
            if (onWifi() && (now == ssid || now == "Wi-Fi")) return@withContext null
        }
        "Couldn't join $ssid. Check the password and that the network is in range."
    }

    private fun onWifi(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        private const val TAG = "DeskuWifi"
    }
}
