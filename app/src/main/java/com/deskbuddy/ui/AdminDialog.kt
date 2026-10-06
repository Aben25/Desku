package com.deskbuddy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the locked-down kiosk (device owner + lock task, see kiosk/) lets the UI do. MainActivity
 * supplies it; when it's null or [active] is false the app behaves as before.
 */
class KioskHooks(
    /** True while the phone is locked to Desku. */
    val active: () -> Boolean,
    /** Checks the admin PIN without changing anything (used to open Settings). */
    val verifyPin: (String) -> Boolean,
    /** Checks the PIN and, if right, leaves kiosk mode. False on a wrong PIN. */
    val exit: (String) -> Boolean,
    /** Checks the PIN and, if right, opens Wi-Fi settings (the kiosk locks again on return). */
    val wifi: ((String) -> Boolean)? = null,
    /** In-app Wi-Fi joining (device owner). When set, the Wi-Fi button opens [WifiPanel]. */
    val wifiSetup: com.deskbuddy.kiosk.WifiSetup? = null,
)

/** The admin door, reached by holding the top edge of the screen for 5 seconds. */
@Composable
fun AdminDialog(hooks: KioskHooks, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    var wifiOpen by remember { mutableStateOf(false) }
    if (wifiOpen && hooks.wifiSetup != null) return WifiPanel(hooks.wifiSetup, onClose = onDismiss)
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Night.Text, unfocusedTextColor = Night.Text,
        focusedBorderColor = Night.Accent, unfocusedBorderColor = Night.Line, cursorColor = Night.Accent,
        focusedLabelColor = Night.Accent, unfocusedLabelColor = Night.Sub,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Night.Raised,
        title = { Text("Admin", style = fig(20.sp, 600)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the admin PIN to join Wi-Fi, open settings or unlock the phone.", style = fig(15.sp, color = Night.Sub, line = 21.sp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12); error = null },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = fig(14.sp, 500, Night.Warn)) }
            }
        },
        confirmButton = {
            Row {
                if (hooks.wifiSetup?.canJoinDirectly == true || hooks.wifi != null) {
                    TextButton(onClick = {
                        when {
                            !hooks.verifyPin(pin) -> error = "Wrong PIN"
                            hooks.wifiSetup?.canJoinDirectly == true -> wifiOpen = true
                            hooks.wifi?.invoke(pin) == true -> onDismiss()
                            else -> error = "Couldn't open Wi-Fi settings"
                        }
                    }) { Text("Wi-Fi", style = fig(16.sp, 500, Night.Text)) }
                }
                TextButton(onClick = {
                    if (hooks.verifyPin(pin)) onOpenSettings() else error = "Wrong PIN"
                }) { Text("Settings", style = fig(16.sp, 500, Night.Text)) }
                TextButton(onClick = {
                    if (hooks.exit(pin)) onDismiss() else error = "Wrong PIN"
                }) { Text("Unlock phone", style = fig(16.sp, 600, Night.Warn)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = fig(16.sp, color = Night.Sub)) } },
    )
}
