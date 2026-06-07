package com.perfmonitor.ai

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ClaudeClient {

    fun analyse(
        prompt: String,
        apiKey: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val url = URL("https://api.anthropic.com/v1/messages")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                    doOutput = true
                    readTimeout = 60_000
                    connectTimeout = 15_000
                }

                val body = buildJsonBody(prompt)
                conn.outputStream.use { it.write(body.toByteArray()) }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                    onError("API error $responseCode: $error")
                    return@Thread
                }

                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (!l.startsWith("data: ")) continue
                        val data = l.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
                        val text = extractDeltaText(data) ?: continue
                        onToken(text)
                    }
                }
                onDone()

            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun buildJsonBody(prompt: String): String {
        val escaped = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"model":"claude-sonnet-4-6","max_tokens":1500,"stream":true,"messages":[{"role":"user","content":"$escaped"}]}"""
    }

    private fun extractDeltaText(json: String): String? {
        // Parse "text" field from streaming delta — avoids pulling in a JSON library
        val key = "\"text\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = json.indexOf("\"", from)
        if (end < 0) return null
        return json.substring(from, end)
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}