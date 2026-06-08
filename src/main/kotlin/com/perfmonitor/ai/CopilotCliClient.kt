package com.perfmonitor.ai

import java.io.File

object CopilotCliClient {

    private val nodePath      = "/usr/local/bin/node"
    private val copilotScript = "/usr/local/lib/node_modules/@github/copilot/npm-loader.js"

    private val resolvedScript: String by lazy {
        try {
            val link = File("/usr/local/bin/copilot").canonicalPath
            if (File(link).exists()) link else copilotScript
        } catch (_: Exception) { copilotScript }
    }

    private fun buildEnv(): Map<String, String> {
        val existing = System.getenv("PATH") ?: ""
        return mapOf("PATH" to "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$existing")
    }

    fun isAvailable(): Boolean {
        return try {
            val pb = ProcessBuilder(nodePath, resolvedScript, "--version").apply {
                environment().putAll(buildEnv())
                redirectErrorStream(true)
            }
            pb.start().inputStream.bufferedReader().readText()
                .contains("Copilot", ignoreCase = true)
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
        contextFiles: List<String> = emptyList(),
        projectPath: String? = null,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val tmpFile = File.createTempFile("perf-monitor-copilot", ".txt")
                tmpFile.writeText(prompt)
                tmpFile.deleteOnExit()

                // Build the command
                val cmd = buildString {
                    append("'$nodePath' '$resolvedScript'")
                    append(" -p \"\$(cat '${tmpFile.absolutePath}')\"")

                    // Give Copilot access to the project directory so it can
                    // read files itself — much better than injecting content
                    if (projectPath != null) {
                        append(" --add-dir '${projectPath.replace("'", "\\'")}'")
                    }

                    // Allow Copilot to read files autonomously without prompting
                    append(" --allow-all-paths")
                    append(" --allow-tool=read")
                    append(" -s --no-ask-user")
                }

                val pb = ProcessBuilder("bash", "-c", cmd).apply {
                    environment().putAll(buildEnv())
                    redirectErrorStream(false)
                }

                val process  = pb.start()
                val reader   = process.inputStream.bufferedReader()
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