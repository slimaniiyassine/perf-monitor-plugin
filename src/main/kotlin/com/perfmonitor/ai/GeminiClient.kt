package com.perfmonitor.ai

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object GeminiClient {

    fun analyse(
        prompt: String,
        apiKey: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                            "gemini-2.5-flash:streamGenerateContent?alt=sse&key=$apiKey"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    readTimeout = 60_000
                    connectTimeout = 15_000
                }

                val escaped = prompt
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")

                val body = """{"contents":[{"parts":[{"text":"$escaped"}]}]}"""
                conn.outputStream.use { it.write(body.toByteArray()) }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                    onError("Gemini API error $responseCode: $error")
                    return@Thread
                }

                // Collect full response first
                val fullText = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        println("RAW_LINE: '${l.take(200)}'")
                        if (!l.startsWith("data: ")) continue
                        val data = l.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
                        val text = extractGeminiText(data)
                        println("EXTRACTED: '${text?.take(50)}'")
                        if (text == null) continue
                        fullText.append(text)
                    }
                }

                // Send full text then done
                onToken(fullText.toString())
                onDone()

            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun extractGeminiText(json: String): String? {
        // Debug: print exact bytes around "text"
        val idx = json.indexOf("\"text\"")
        if (idx >= 0) {
            println("TEXT_CONTEXT: '${json.substring(idx, minOf(idx + 30, json.length))}'")
        }

        // Try multiple patterns to find the text value
        val patterns = listOf(
            Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)""""),
            Regex(""""text":"((?:[^"\\]|\\.)*)"""")
        )

        for (pattern in patterns) {
            val match = pattern.find(json)
            if (match != null) {
                return match.groupValues[1]
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\/", "/")
            }
        }
        return null
    }}