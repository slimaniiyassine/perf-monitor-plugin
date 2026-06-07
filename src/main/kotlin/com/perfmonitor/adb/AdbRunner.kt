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
        // Get PID of the target package first
        val pidOutput = runCommand("adb", "shell", "pidof", packageName)
        val pid = pidOutput.trim()

        val result = StringBuilder()

        // Overall CPU stats
        result.appendLine("=== CPU Overview ===")
        result.appendLine(runCommand("adb", "shell", "top", "-n", "1", "-b"))

        // Per-process stats if we have a PID
        if (pid.isNotBlank() && !pid.contains("ERROR")) {
            result.appendLine("\n=== Process CPU Detail (PID: $pid) ===")
            result.appendLine(runCommand("adb", "shell", "cat", "/proc/$pid/stat"))

            result.appendLine("\n=== Thread Count ===")
            result.appendLine(runCommand("adb", "shell", "ls", "/proc/$pid/task", "|", "wc", "-l"))

            result.appendLine("\n=== CPU Time Breakdown ===")
            result.appendLine(runCommand("adb", "shell", "cat", "/proc/$pid/schedstat"))
        } else {
            result.appendLine("\n⚠ Process '$packageName' not found running on device.")
            result.appendLine("Make sure the app is open and running on the emulator.")
        }

        return result.toString()
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