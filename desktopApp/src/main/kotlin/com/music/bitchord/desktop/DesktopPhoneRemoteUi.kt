package com.music.bitchord.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopPhoneRemoteDialog(remote: DesktopPhoneRemote, onDismiss: () -> Unit) {
    val active by remote.running.collectAsState()
    val relayStatus by remote.relayStatus.collectAsState()
    val pairing by remote.pairing.offer.collectAsState()
    var https by remember { mutableStateOf(true) }
    val controller by remote.controllerLink.collectAsState()
    val guests by remote.guestLink.collectAsState()
    val members by remote.members.collectAsState()
    val guestControl by remote.guestControl.collectAsState()
    var networks by remember { mutableStateOf(DesktopPhoneRemote.networks()) }
    var selected by remember { mutableStateOf(networks.firstOrNull()?.address.orEmpty()) }
    var inviteGuests by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.width(760.dp).heightIn(max = 700.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Phone player & Jam", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text("Listen on your iPhone or control this PC. Share a Jam invitation so friends can add songs to the PC queue.", color = DesktopSecondary)
                if (!active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("No-admin HTTPS relay", fontWeight = FontWeight.Bold); Text("Cloudflare carries the player, metadata and audio. Internet required.", style = MaterialTheme.typography.bodySmall) }
                        Switch(checked = https, onCheckedChange = { https = it }, enabled = !busy)
                    }
                    if (!https) Text("Local network (may require an existing firewall allowance)", fontWeight = FontWeight.Bold)
                    if (!https) Box {
                        OutlinedButton(onClick = { menu = true }, enabled = !busy) {
                            Text(networks.firstOrNull { it.address == selected }?.label ?: "No local network found")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            networks.forEach { network -> DropdownMenuItem(text = { Text(network.label) }, onClick = { selected = network.address; menu = false }) }
                        }
                    }
                    Text("HTTPS mode opens an outbound connection and leaves Windows Firewall unchanged. The temporary link changes each time. Share controller invitations only with people you trust.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = (https || selected.isNotBlank()) && !busy, onClick = {
                            busy = true; note = ""
                            scope.launch {
                                try { withContext(Dispatchers.IO) { if (https) remote.startNoAdmin() else remote.start(selected) }; note = "Session started. Scan your invitation below." }
                                catch (error: Exception) { note = error.message ?: "Could not start the phone session." }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "Starting..." else "Start phone session") }
                        TextButton(onClick = { networks = DesktopPhoneRemote.networks(); selected = networks.firstOrNull()?.address.orEmpty() }, enabled = !busy) { Text("Refresh networks") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Session is on · ${members.size} / 16 devices", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { remote.close(); note = "Session ended. Every invitation and connected device was disconnected." }) { Text("End session") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = !inviteGuests, onClick = { inviteGuests = false }, label = { Text("My phone / controller") })
                        FilterChip(selected = inviteGuests, onClick = { inviteGuests = true }, label = { Text("Invite guests") })
                    }
                    if (relayStatus.isNotBlank()) Text(relayStatus, color = DesktopSecondary)
                    if (controller.startsWith("https://") && !inviteGuests && !busy) {
                        HorizontalDivider()
                        Text("Connect with a four-digit code", style = MaterialTheme.typography.titleLarge)
                        Text("Open bitchord.retrshot.chatgpt.site on the other device, choose Connect to a device, then enter the code. Approve only the device you recognize.", color = DesktopSecondary)
                        if (pairing.code.isNotEmpty()) Text(pairing.code, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                        if (pairing.message.isNotBlank()) Text(pairing.message, color = DesktopSecondary)
                        if (pairing.status == "pending") {
                            Text("Connection request from ${pairing.requester}", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { scope.launch { runCatching { remote.pairing.respond(true) }.onFailure { note = it.message.orEmpty() } } }) { Text("Approve device") }
                                OutlinedButton(onClick = { scope.launch { runCatching { remote.pairing.respond(false) }.onFailure { note = it.message.orEmpty() } } }) { Text("Reject") }
                            }
                        } else Button(enabled = pairing.status != "creating", onClick = { scope.launch { runCatching { remote.pairing.create(controller) }.onFailure { note = it.message.orEmpty() } } }) { Text(if (pairing.status == "creating") "Creating code..." else if (pairing.code.isEmpty()) "Show connection code" else "Generate new code") }
                        HorizontalDivider()
                    }
                    val link = if (inviteGuests) guests else controller
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!busy && link.isNotBlank()) DesktopQrCode(link, size = 220.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (inviteGuests) "Let friends join the queue" else "Open your iPhone music player", style = MaterialTheme.typography.titleLarge)
                            Text("Scan with the iPhone Camera, then open in Safari. You can use Share → Add to Home Screen for an app-like shortcut.", color = DesktopSecondary)
                            Text(if (inviteGuests) "Guests can search and add songs. Playback control is optional below." else "Controller invitations grant access to your music library, playback, playlists and supported PC settings. Share it only with people you trust.", style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !busy, onClick = { clipboard.setText(AnnotatedString(link)); note = "Invitation copied." }) { Text("Copy invitation") }
                            if (!inviteGuests && !busy && link.startsWith("https://")) TextButton(onClick = {
                                val branded = "https://bitchord.retrshot.chatgpt.site/#connect=" + java.net.URLEncoder.encode(link, Charsets.UTF_8)
                                clipboard.setText(AnnotatedString(branded)); note = "Branded invitation copied. The private website may ask you to sign in to ChatGPT before opening your current PC session."
                            }) { Text("Copy website invitation") }

                        }
                    }
                    OutlinedTextField(value = link, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(), label = { Text("Invitation link") }, maxLines = 2)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Let guests control playback")
                            Text("Off: guests can only add songs. Controllers always have full controls.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = guestControl, onCheckedChange = { remote.guestControl.value = it })
                    }
                    if (members.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Paired devices", fontWeight = FontWeight.Bold)
                        members.forEach { member ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${member.name} · ${if (member.controller) "Controller" else "Guest"}", modifier = Modifier.weight(1f))
                                TextButton(onClick = { remote.disconnect(member.id) }) { Text("Disconnect") }
                            }
                        }
                    }
                    Text("Keep BitChord open on this PC. Closing it stops the music and phone session. End and restart the session to replace all invitations.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (note.isNotBlank()) Text(note, color = DesktopAccent)
                Text("On iPhone, open in Safari and choose Share > Add to Home Screen. Headphones, Bluetooth and AirPlay routes are selected through iOS. This is a web app; PC-only integrations and audio effects remain on the PC. No administrator access is requested.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
