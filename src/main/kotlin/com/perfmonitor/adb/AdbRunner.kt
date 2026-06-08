package com.perfmonitor.adb

import java.io.File

object AdbRunner {

    private val adbPath: String by lazy {
        findAdb() ?: "adb"
    }

    private fun findAdb(): String? {
        // 1. Check environment variables
        val fromEnv = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT")
        ).map { "$it/platform-tools/adb" }
            .firstOrNull { File(it).canExecute() }
        if (fromEnv != null) return fromEnv

        // 2. Check standard locations per OS
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/Library/Android/sdk/platform-tools/adb",
            "$home/Android/Sdk/platform-tools/adb",
            "$home/AppData/Local/Android/Sdk/platform-tools/adb.exe",
            "/usr/local/bin/adb",
            "/opt/homebrew/bin/adb",
            "/opt/android-sdk/platform-tools/adb",
            "/Applications/Android Studio.app/Contents/plugins/android/resources/deploy/adb",
        )
        val fromPath = candidates.firstOrNull { File(it).canExecute() }
        if (fromPath != null) return fromPath

        // 3. Try which/where
        return try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = if (os.contains("win")) arrayOf("where", "adb") else arrayOf("which", "adb")
            val result = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readLine()?.trim()
            result?.takeIf { File(it).canExecute() }
        } catch (_: Exception) { null }
    }

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

    fun resolvedAdbPath(): String = adbPath

    fun getConnectedDevice(): String? {
        val output = runCommand(adbPath, "devices")
        return output.lines()
            .drop(1)
            .firstOrNull { it.contains("device") && !it.contains("offline") }
            ?.split("\t")
            ?.firstOrNull()
            ?.trim()
    }

    fun isDeviceConnected(): Boolean {
        return getConnectedDevice() != null
    }

    fun getForegroundPackage(): String? {
        val output = runCommand(adbPath, "shell", "dumpsys", "activity", "activities")
        return output.lines()
            .firstOrNull { it.contains("mResumedActivity") || it.contains("ResumedActivity") }
            ?.let { line ->
                Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
                    .findAll(line)
                    .map { it.value }
                    .firstOrNull {
                        it.contains(".") &&
                                !it.startsWith("android") &&
                                !it.startsWith("com.android")
                    }
            }
    }

    fun captureMemory(packageName: String): String {
        return runCommand(adbPath, "shell", "dumpsys", "meminfo", packageName)
    }

    fun captureCpu(packageName: String): String {
        val pidOutput = runCommand(adbPath, "shell", "pidof", packageName)
        val pid = pidOutput.trim()
        val result = StringBuilder()

        result.appendLine("=== CPU Overview ===")
        result.appendLine(runCommand(adbPath, "shell", "top", "-n", "1", "-b"))

        if (pid.isNotBlank() && !pid.contains("ERROR")) {
            result.appendLine("\n=== Process CPU Detail (PID: $pid) ===")
            result.appendLine(runCommand(adbPath, "shell", "cat", "/proc/$pid/stat"))
            result.appendLine("\n=== Thread Count ===")
            result.appendLine(runCommand(adbPath, "shell", "ls", "/proc/$pid/task"))
            result.appendLine("\n=== CPU Time Breakdown ===")
            result.appendLine(runCommand(adbPath, "shell", "cat", "/proc/$pid/schedstat"))
        } else {
            result.appendLine("\n⚠ Process '$packageName' not found. Make sure the app is running.")
        }

        return result.toString()
    }

    fun captureNetwork(): String {
        return runCommand(adbPath, "shell", "cat", "/proc/net/dev")
    }

    fun captureBattery(): String {
        return runCommand(adbPath, "shell", "dumpsys", "battery")
    }

    fun captureUiFps(packageName: String): String {
        return runCommand(adbPath, "shell", "dumpsys", "gfxinfo", packageName)
    }

    fun runShellCommand(vararg args: String): String {
        return runCommand(adbPath, "shell", *args)
    }

}