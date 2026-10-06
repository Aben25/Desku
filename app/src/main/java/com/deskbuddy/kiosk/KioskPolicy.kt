package com.deskbuddy.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.deskbuddy.BuildConfig
import com.deskbuddy.MainActivity

/**
 * The real kiosk. When Desku is the device owner, the phone runs only Desku:
 * - lock task mode with no home, recents, notifications or status bar, and no lock screen;
 * - Desku is the home app, so it comes back on every boot;
 * - the screen stays on while charging;
 * - Desku can't be uninstalled, and factory reset, safe boot and new users are blocked;
 * - mic and camera permissions are granted.
 * Without device owner, it falls back to Android's screen pinning, which the user can undo.
 * The only way out is [exit] with the admin PIN (or a factory reset).
 */
object KioskPolicy {
    private const val TAG = "DeskuKiosk"

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
    )

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun admin(ctx: Context) = ComponentName(ctx, DeskuAdmin::class.java)

    fun isOwner(ctx: Context): Boolean = runCatching { dpm(ctx).isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

    /** True while the phone is locked to Desku. */
    fun isActive(ctx: Context): Boolean =
        isOwner(ctx) && ctx.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /** Call from the activity's onResume: applies the policies and enters lock task mode. */
    fun enter(activity: Activity) {
        if (!isOwner(activity)) return
        val d = dpm(activity)
        val a = admin(activity)
        val pkg = activity.packageName
        fun step(name: String, block: () -> Unit) = runCatching(block).onFailure { Log.w(TAG, "$name failed", it) }
        step("lock task packages") { d.setLockTaskPackages(a, arrayOf(pkg)) }
        step("lock task features") { d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
        step("keyguard") { d.setKeyguardDisabled(a, true) }
        step("status bar") { d.setStatusBarDisabled(a, true) }
        step("home app") {
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(a, home, ComponentName(activity, MainActivity::class.java))
        }
        step("stay awake") {
            val plugged = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_WIRELESS
            d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())
        }
        RESTRICTIONS.forEach { r -> step(r) { d.addUserRestriction(a, r) } }
        step("permissions") {
            listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).forEach {
                d.setPermissionGrantState(a, pkg, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
        step("uninstall block") { d.setUninstallBlocked(a, pkg, true) }
        if (!isActive(activity)) step("start lock task") { activity.startLockTask() }
    }

    /**
     * Admin Wi-Fi: with the right PIN, lets the phone's Wi-Fi settings (and the Wi-Fi sign-in page
     * many venue networks need) run inside the kiosk, and opens them. Back returns to Desku, whose
     * onResume calls [enter], which shrinks the allowed apps to Desku alone again.
     */
    fun openWifi(activity: Activity, pin: String): Boolean {
        if (BuildConfig.KIOSK_PIN.isBlank() || pin != BuildConfig.KIOSK_PIN) return false
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isOwner(activity)) {
            val settings = intent.resolveActivity(activity.packageManager)?.packageName ?: "com.android.settings"
            runCatching {
                dpm(activity).setLockTaskPackages(
                    admin(activity),
                    arrayOf(activity.packageName, settings, "com.android.captiveportallogin", "com.google.android.captiveportallogin"),
                )
            }.onFailure { Log.w(TAG, "couldn't allow Wi-Fi settings", it) }
        }
        return runCatching { activity.startActivity(intent) }.onFailure { Log.w(TAG, "couldn't open Wi-Fi settings", it) }.isSuccess
    }

    /**
     * Admin exit: with the right PIN, undoes everything [enter] did and gives up device owner,
     * so the phone is an ordinary phone again. Returns false for a wrong PIN.
     */
    fun exit(activity: Activity, pin: String): Boolean {
        if (BuildConfig.KIOSK_PIN.isBlank() || pin != BuildConfig.KIOSK_PIN) return false
        runCatching { activity.stopLockTask() }
        if (!isOwner(activity)) return true
        val d = dpm(activity)
        val a = admin(activity)
        val pkg = activity.packageName
        runCatching { d.setUninstallBlocked(a, pkg, false) }
        RESTRICTIONS.forEach { r -> runCatching { d.clearUserRestriction(a, r) } }
        runCatching { d.clearPackagePersistentPreferredActivities(a, pkg) }
        runCatching { d.setStatusBarDisabled(a, false) }
        runCatching { d.setKeyguardDisabled(a, false) }
        runCatching { d.setLockTaskPackages(a, emptyArray()) }
        runCatching { d.clearDeviceOwnerApp(pkg) }.onFailure { Log.e(TAG, "couldn't give up device owner", it) }
        return true
    }
}
