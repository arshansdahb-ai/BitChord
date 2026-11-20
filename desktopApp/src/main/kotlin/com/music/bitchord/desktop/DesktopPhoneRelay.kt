package com.music.bitchord.desktop

import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Portable outbound connector; never installs a service or changes a firewall rule. */
internal class DesktopPhoneRelay : AutoCloseable {
    @Volatile private var process: Process? = null
    suspend fun start(localOrigin: String): String = withContext(Dispatchers.IO) {
        require(Regex("http://127\\.0\\.0\\.1:[0-9]+").matches(localOrigin))
        val resources = System.getProperty("compose.application.resources.dir")
            ?: error("The HTTPS connector is available in the packaged Windows app.")
        val exe = Path.of(resources, "relay", "cloudflared.exe")
        check(Files.isRegularFile(exe)) { "The HTTPS connector is missing from this package." }
        val dir = DesktopCustomPaths.root.resolve("phone-relay").also { Files.createDirectories(it) }
        val config = dir.resolve("empty.yml")
        Files.writeString(config, "{}\n")
        val ready = CompletableFuture<String>()
        val log = dir.resolve("connector.log")
        Files.writeString(log, "")
        val child = ProcessBuilder(exe.toString(), "tunnel", "--config", config.toString(), "--no-autoupdate",
            "--url", localOrigin, "--http-host-header", localOrigin.removePrefix("http://"),
            "--protocol", "http2", "--edge-ip-version", "4", "--metrics", "127.0.0.1:0")
            .redirectErrorStream(true).start()
        process = child
        Thread({
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (Files.size(log) > 128_000) Files.writeString(log, "")
                        Files.writeString(log, line.take(1000) + "\n", java.nio.file.StandardOpenOption.APPEND)
                        Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line)?.value?.let { ready.complete(it) }
                    }
                }
            } finally { if (!ready.isDone) ready.completeExceptionally(IllegalStateException("The HTTPS connector could not connect. Check internet access.")) }
        }, "BitChord-phone-HTTPS").apply { isDaemon = true; start() }
        try {
            repeat(180) {
                ensureActive()
                if (ready.isDone) return@withContext ready.get()
                delay(250)
            }
            error("The HTTPS link timed out. Please try again.")
        } catch (error: Exception) { close(); throw error }
    }
    override fun close() {
        process?.let { child ->
            child.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            child.destroyForcibly()
            runCatching { child.waitFor(1, TimeUnit.SECONDS) }
        }
        process = null
    }
}
