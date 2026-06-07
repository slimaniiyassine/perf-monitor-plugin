package com.perfmonitor.adb

object AdbRunner {

    private fun runCommand(vararg args: String): String {
        return try {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun getConnectedDevice(): String? {
        val output = runCommand("adb", "devices")
        return output.lines()
            .drop(1)
            .firstOrNull { it.contains("device") && !it.contains("offline") }
            ?.split("\t")
            ?.firstOrNull()
            ?.trim()
    }

    fun captureMemory(packageName: String): String {
        return runCommand("adb", "shell", "dumpsys", "meminfo", packageName)
    }

    fun captureCpu(packageName: String): String {
        return runCommand("adb", "shell", "top", "-n", "1", "-q")
            .lines()
            .filter { it.contains(packageName) || it.startsWith("Tasks") || it.startsWith("CPU") }
            .joinToString("\n")
    }

    fun captureNetwork(): String {
        return runCommand("adb", "shell", "cat", "/proc/net/dev")
    }

    fun captureBattery(): String {
        return runCommand("adb", "shell", "dumpsys", "battery")
    }

    fun captureUiFps(packageName: String): String {
        return runCommand("adb", "shell", "dumpsys", "gfxinfo", packageName)
    }
}