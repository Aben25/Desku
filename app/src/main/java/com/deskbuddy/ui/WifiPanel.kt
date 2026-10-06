package com.deskbuddy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskbuddy.kiosk.WifiSetup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Join Wi-Fi inside Desku: pick a nearby network (or type its name), enter the password, connect. */
@Composable
fun WifiPanel(setup: WifiSetup, onClose: () -> Unit) {
    var networks by remember { mutableStateOf(emptyList<WifiSetup.Network>()) }
    var current by remember { mutableStateOf<String?>(null) }
    var ssid by remember { mutableStateOf("") }
    var wpa3 by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Night.Text, unfocusedTextColor = Night.Text,
        focusedBorderColor = Night.Accent, unfocusedBorderColor = Night.Line, cursorColor = Night.Accent,
        focusedLabelColor = Night.Accent, unfocusedLabelColor = Night.Sub,
    )

    LaunchedEffect(Unit) {
        setup.prepare()
        while (true) {
            current = setup.currentNetwork()
            networks = setup.scan()
            delay(5_000)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        containerColor = Night.Raised,
        title = { Text("Wi-Fi", style = fig(20.sp, 600)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(current?.let { "Connected: $it" } ?: "Not connected to Wi-Fi", style = fig(15.sp, 500, if (current != null) Night.Accent else Night.Sub))
                if (networks.isNotEmpty()) {
                    Text("Nearby", style = fig(13.sp, 600, Night.Sub))
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        networks.take(12).forEach { n ->
                            Row(
                                Modifier.fillMaxWidth().clickable { ssid = n.ssid; wpa3 = n.wpa3; status = null }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(n.ssid, style = fig(16.sp, if (n.ssid == ssid) 600 else 400, if (n.ssid == ssid) Night.Accent else Night.Text), modifier = Modifier.weight(1f))
                                Text((if (n.secure) "🔒 " else "") + "▂▄▆█".take(n.bars + 1), style = fig(14.sp, color = Night.Sub))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = ssid, onValueChange = { ssid = it; wpa3 = false; status = null },
                    label = { Text("Network name") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it; status = null },
                    label = { Text("Password (blank if open)") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show", style = fig(13.sp, color = Night.Sub)) }
                    },
                )
                status?.let { Text(it, style = fig(14.sp, 500, if (it.startsWith("Connected")) Night.Accent else Night.Warn)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ssid.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    status = "Connecting to $ssid…"
                    scope.launch {
                        val problem = setup.join(ssid.trim(), password, wpa3)
                        busy = false
                        current = setup.currentNetwork()
                        status = problem ?: "Connected to ${ssid.trim()}."
                        if (problem == null) password = ""
                    }
                },
            ) { Text(if (busy) "Connecting…" else "Connect", style = fig(16.sp, 600, Night.Accent)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Done", style = fig(16.sp, color = Night.Sub)) } },
    )
}
