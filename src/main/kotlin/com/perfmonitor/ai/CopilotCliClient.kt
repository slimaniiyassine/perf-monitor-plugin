
package com.perfmonitor.ai

import java.io.File

object CopilotCliClient {

    // Both are at /usr/local/bin — hardcode as primary, fallback to discovery
    private val nodePath    = "/usr/local/bin/node"
    private val copilotScript = "/usr/local/lib/node_modules/@github/copilot/npm-loader.js"

    // Resolved copilot script — follow the symlink
    private val resolvedScript: String by lazy {
        try {
            // Follow symlink: /usr/local/bin/copilot -> ../lib/node_modules/@github/copilot/npm-loader.js
            val link   = File("/usr/local/bin/copilot").canonicalPath
            if (File(link).exists()) link
            else copilotScript
        } catch (_: Exception) { copilotScript }
    }

    private fun buildEnv(): Map<String, String> {
        val existing = System.getenv("PATH") ?: ""
        return mapOf(
            "PATH" to "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$existing"
        )
    }

    fun isAvailable(): Boolean {
        return try {
            val pb = ProcessBuilder(nodePath, resolvedScript, "--version").apply {
                environment().putAll(buildEnv())
                redirectErrorStream(true)
            }
            val output = pb.start().inputStream.bufferedReader().readText()
            output.contains("Copilot", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    fun getVersion(): String {
        return try {
            val pb = ProcessBuilder(nodePath, resolvedScript, "--version").apply {
                environment().putAll(buildEnv())
                redirectErrorStream(true)
            }
            pb.start().inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun analyse(
        prompt: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                // Write prompt to temp file
                val tmpFile = File.createTempFile("perf-monitor-copilot", ".txt")
                tmpFile.writeText(prompt)
                tmpFile.deleteOnExit()

                // Pass full prompt via -p by reading from the file in bash
                // This avoids all shell escaping issues with special characters
                val pb = ProcessBuilder(
                    "bash", "-c",
                    """'$nodePath' '$resolvedScript' -p "$(cat '${tmpFile.absolutePath}')" -s --no-ask-user"""
                ).apply {
                    environment().putAll(buildEnv())
                    redirectErrorStream(false)
                }

                val process = pb.start()
                val reader  = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onToken((line ?: "") + "\n")
                }

                val stderr   = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                tmpFile.delete()

                if (exitCode != 0 && stderr.isNotBlank()) onError(stderr)
                else onDone()

            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }.also { it.isDaemon = true }.start()
    }
}