package com.deskbuddy.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Makes Desku the phone's device owner (Android's "dedicated device" mode), which is what lets
 * it lock the phone to this one app. Set once over USB:
 *   adb shell dpm set-device-owner com.deskbuddy/.kiosk.DeskuAdmin
 * The phone must have no accounts signed in. Undo with the admin PIN (KioskPolicy.exit).
 */
class DeskuAdmin : DeviceAdminReceiver()
