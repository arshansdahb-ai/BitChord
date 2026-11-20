package com.music.bitchord.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal data class PhonePairingOffer(val code: String = "", val expires: Long = 0, val status: String = "", val requester: String = "", val message: String = "")

/** A short code locates a request; only the PC's in-memory host secret can approve it. */
internal class DesktopPhonePairing : AutoCloseable {
    companion object { const val WEBSITE = "https://bitchord.retrshot.chatgpt.site" }
    val offer = MutableStateFlow(PhonePairingOffer())
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null
    @Volatile private var hostToken = ""
    @Volatile private var generation = 0L
    private suspend fun request(op: String, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val code = offer.value.code
        val uri = if (body == null) "$WEBSITE/api/pair?op=$op&code=$code" else "$WEBSITE/api/pair"
        val builder = HttpRequest.newBuilder(URI(uri)).timeout(Duration.ofSeconds(12))
            .header("User-Agent", "BitChordCustom/1.8.1 (Windows; device pairing)")
        if (hostToken.isNotEmpty()) builder.header("Authorization", "Bearer $hostToken")
        if (body != null) builder.header("Content-Type", "application/json").header("Origin", WEBSITE).POST(HttpRequest.BodyPublishers.ofString(body.toString())) else builder.GET()
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val data = Json.parseToJsonElement(response.body()).jsonObject
        check(response.statusCode() == 200) { data["error"]?.jsonPrimitive?.contentOrNull ?: "Device pairing is unavailable." }
        data
    }
    suspend fun create(invitation: String) {
        require(invitation.startsWith("https://")) { "Start the no-admin HTTPS phone session first." }
        close(); val epoch = generation
        offer.value = PhonePairingOffer(status = "creating", message = "Creating a two-minute connection code...")
        try {
            val data = request("host", buildJsonObject { put("op", "host"); put("invitation", invitation); put("name", "BitChord Windows PC") })
            if (generation != epoch) return
            hostToken = data["hostToken"]!!.jsonPrimitive.content
            offer.value = PhonePairingOffer(data["code"]!!.jsonPrimitive.content, data["expires"]!!.jsonPrimitive.long, "waiting", message = "Enter these four digits on the other device, then approve its request here.")
            polling = scope.launch {
                while (isActive && generation == epoch) {
                    delay(1250)
                    if (System.currentTimeMillis() > offer.value.expires) { offer.value = offer.value.copy(status = "expired", message = "Code expired. Generate a new code."); break }
                    try { val status = request("status"); offer.value = offer.value.copy(status = status["status"]!!.jsonPrimitive.content, requester = status["requester"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
                    catch (error: Exception) { if (error is CancellationException) throw error; offer.value = offer.value.copy(message = error.message ?: "Could not check pairing.") }
                }
            }
        } catch (error: Exception) { offer.value = PhonePairingOffer(message = error.message ?: "Could not create a pairing code."); throw error }
    }
    suspend fun respond(approve: Boolean) {
        request("decision", buildJsonObject { put("op", "decision"); put("code", offer.value.code); put("approve", approve) })
        polling?.cancel(); offer.value = offer.value.copy(status = if (approve) "approved" else "rejected", message = if (approve) "Approved. The other device can now connect." else "Connection rejected. Generate a new code to try again.")
    }
    override fun close() {
        generation++; polling?.cancel(); polling = null
        val oldCode = offer.value.code; val oldToken = hostToken; hostToken = ""; offer.value = PhonePairingOffer()
        if (oldCode.isNotEmpty() && oldToken.isNotEmpty()) runCatching {
            val body = buildJsonObject { put("op", "revoke"); put("code", oldCode) }
            client.sendAsync(HttpRequest.newBuilder(URI("$WEBSITE/api/pair")).timeout(Duration.ofSeconds(3)).header("User-Agent", "BitChordCustom/1.8.1 (Windows; device pairing)").header("Authorization", "Bearer $oldToken").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.discarding())
        }
    }
}
