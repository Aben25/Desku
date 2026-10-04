package com.deskbuddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskbuddy.Prefs
import com.deskbuddy.voice.WakeWord

@Composable
fun SettingsDialog(
    prefs: Prefs,
    wake: WakeWord.Status,
    onDismiss: () -> Unit,
    onSave: (apiKey: String, model: String, wakeWord: Boolean, frontCamera: Boolean, serverUrl: String, deviceToken: String) -> Unit,
    onPin: () -> Unit,
    onNewConversation: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(prefs.model) }
    var wakeOn by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var front by remember { mutableStateOf(prefs.frontCamera) }
    var server by remember { mutableStateOf(prefs.serverUrl) }
    var token by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Night.Text, unfocusedTextColor = Night.Text,
        focusedBorderColor = Night.Accent, unfocusedBorderColor = Night.Line,
        focusedLabelColor = Night.Accent, unfocusedLabelColor = Night.Sub, cursorColor = Night.Accent,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Night.Raised,
        titleContentColor = Night.Text,
        textContentColor = Night.Sub,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it.trim() }, singleLine = true,
                    label = { Text("Desku engine (GPT-Live)") },
                    placeholder = { Text("ws://192.168.1.20:8787 · blank = Claude on this phone") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it.trim() }, singleLine = true,
                    label = { Text("Engine device token") },
                    placeholder = { Text(if (prefs.deviceToken.isBlank()) "DEVICE_TOKEN from server/.env" else "Saved · paste to replace") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    label = { Text("Anthropic API key") },
                    placeholder = {
                        Text(if (prefs.apiKey.isBlank()) "sk-ant-…" else "Saved (…${prefs.apiKey.takeLast(4)}) · paste to replace")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, singleLine = true,
                    label = { Text("Claude model") }, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wake phrase “Hey Desku”", color = Night.Text, fontSize = 16.sp)
                        Text(
                            when (wake) {
                                WakeWord.Status.NotInstalled -> "Not installed in this build (run scripts/fetch-wake-model.sh)"
                                WakeWord.Status.Failed -> "Couldn't start on this phone"
                                else -> "Recognized on this phone only; nothing is recorded or sent until you say it"
                            },
                            fontSize = 13.sp,
                        )
                    }
                    Switch(
                        checked = wakeOn, onCheckedChange = { wakeOn = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Night.Accent),
                    )
                }
                Column {
                    Text("Camera", color = Night.Text, fontSize = 16.sp)
                    Row {
                        listOf(true to "Front (faces you)", false to "Back").forEach { (isFront, label) ->
                            FilterChip(
                                selected = front == isFront, onClick = { front = isFront }, label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    labelColor = Night.Sub, selectedLabelColor = Night.Ink, selectedContainerColor = Night.Accent,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                Text(
                    "Privacy: conversations stay in memory only and are forgotten after 10 quiet minutes. " +
                        "Only things you ask me to remember are saved, on this phone. Your words and photos " +
                        "are sent to Claude to answer you; speech-to-text uses the phone's recognizer.",
                    fontSize = 13.sp, lineHeight = 18.sp,
                )
                Row {
                    TextButton(onClick = onNewConversation) { Text("New conversation", color = Night.Text) }
                    TextButton(onClick = onPin) { Text("Pin to screen", color = Night.Text) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key.ifBlank { prefs.apiKey }, model, wakeOn, front, server, token.ifBlank { prefs.deviceToken }) }) {
                Text("Save", color = Night.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Night.Sub) } },
    )
}
