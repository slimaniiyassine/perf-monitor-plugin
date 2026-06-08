package com.perfmonitor.detector

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.perfmonitor.adb.AdbRunner

data class DetectedFile(
    val virtualFile: VirtualFile,
    val className: String,
    val reason: String,
    val selected: Boolean = true
)

object SourceFileDetector {

    // ── Get foreground fragment from adb ──────────────────────────────
    fun getForegroundFragment(): String? {
        val output = AdbRunner.runShellCommand("dumpsys", "activity", "top")
        val lines  = output.lines()

        // Find the last "Added Fragments:" block — this is the NavHost child
        var lastAddedIdx = -1
        lines.forEachIndexed { i, line ->
            if (line.trimStart().startsWith("Added Fragments:")) lastAddedIdx = i
        }
        if (lastAddedIdx < 0) return null

        for (i in lastAddedIdx + 1 until minOf(lastAddedIdx + 5, lines.size)) {
            val line = lines[i].trim()
            if (line.startsWith("#0:")) return extractClassName(line)
        }
        return null
    }

    private fun extractClassName(line: String): String? {
        val name = line.substringAfter("#0:").trim().substringBefore("{").trim()
        return name.takeIf {
            it.isNotBlank() &&
                    !it.startsWith("androidx.") &&
                    !it.startsWith("android.") &&
                    !it.contains("NavHost") &&
                    !it.contains("ReportFragment")
        }
    }

    // ── Find files by exact class name ────────────────────────────────
    fun findFilesByClassName(project: Project, simpleClassName: String): List<VirtualFile> {
        val results = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
            searchDir(root, simpleClassName, results)
        }
        return results
    }

    private fun searchDir(dir: VirtualFile, className: String, results: MutableList<VirtualFile>) {
        for (child in dir.children) {
            if (child.isDirectory) searchDir(child, className, results)
            else if (child.extension in listOf("kt", "java") &&
                child.nameWithoutExtension == className) results.add(child)
        }
    }

    // ── Derive the app's base package path from packageName ───────────
    // e.g. "ca.bell.selfserve.mybellmobile" → only scan files under that path
    private fun findAppSourceRoot(project: Project, packageName: String): List<VirtualFile> {
        val packagePath = packageName.replace('.', '/')
        val roots = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
            val appRoot = root.findFileByRelativePath(packagePath)
            if (appRoot != null && appRoot.isDirectory) roots.add(appRoot)
            else roots.add(root) // fallback to full source root
        }
        return roots
    }

    // ── Main detection ────────────────────────────────────────────────
    fun detectRelevantFiles(
        project: Project,
        monitorName: String,
        packageName: String
    ): List<DetectedFile> {
        val results = mutableListOf<DetectedFile>()

        // 1. Foreground fragment — exact class name match, no broad scanning
        val foregroundFragment = getForegroundFragment()
        if (foregroundFragment != null) {
            val files = findFilesByClassName(project, foregroundFragment)
            files.forEach { results.add(DetectedFile(it, foregroundFragment, "Foreground fragment")) }

            // 2. ViewModel — try common naming conventions
            listOf(
                foregroundFragment.replace("Fragment", "ViewModel"),
                foregroundFragment.replace("Fragment", "VM"),
                foregroundFragment + "ViewModel"
            ).distinct().forEach { vmName ->
                findFilesByClassName(project, vmName).forEach { f ->
                    results.add(DetectedFile(f, vmName, "ViewModel for $foregroundFragment"))
                }
            }

            // 3. Scan the fragment file itself for referenced classes
            files.firstOrNull()?.let { fragmentFile ->
                results.addAll(findRelatedFromFile(project, fragmentFile, monitorName))
            }
        }

        // 4. Monitor-specific files — ONLY search within the app's package directory
        //    to avoid matching framework/library files
        if (foregroundFragment != null || packageName.isNotBlank()) {
            val appRoots = findAppSourceRoot(project, packageName)
            val monitorFiles = findMonitorSpecificFiles(appRoots, monitorName, maxResults = 5)
            results.addAll(monitorFiles)
        }

        return results.distinctBy { it.virtualFile.path }
    }

    // ── Scan a file for referenced classes ────────────────────────────
    private fun findRelatedFromFile(
        project: Project,
        file: VirtualFile,
        monitorName: String
    ): List<DetectedFile> {
        val results = mutableListOf<DetectedFile>()
        val content = try { String(file.contentsToByteArray()) } catch (_: Exception) { return results }

        // ViewModel refs
        Regex("""(\w+ViewModel)\b""").findAll(content).forEach { match ->
            val name = match.groupValues[1]
            findFilesByClassName(project, name).forEach { f ->
                if (f.path != file.path)
                    results.add(DetectedFile(f, name, "ViewModel in ${file.name}"))
            }
        }

        // Adapter refs (only for UI/Memory)
        if (monitorName in listOf("Memory", "UI / FPS")) {
            Regex("""(\w+Adapter)\b""").findAll(content).forEach { match ->
                val name = match.groupValues[1]
                findFilesByClassName(project, name).forEach { f ->
                    results.add(DetectedFile(f, name, "Adapter in ${file.name}"))
                }
            }
        }

        // Repository refs
        Regex("""(\w+Repository)\b""").findAll(content).forEach { match ->
            val name = match.groupValues[1]
            findFilesByClassName(project, name).forEach { f ->
                results.add(DetectedFile(f, name, "Repository in ${file.name}"))
            }
        }

        return results.distinctBy { it.virtualFile.path }
    }

    // ── Monitor-specific patterns — capped and scoped to app package ──
    private fun findMonitorSpecificFiles(
        appRoots: List<VirtualFile>,
        monitorName: String,
        maxResults: Int
    ): List<DetectedFile> {
        val results  = mutableListOf<DetectedFile>()

        // Exact suffix matches only — no broad terms like "Manager" or "Service"
        val patterns: List<Pair<String, String>> = when (monitorName) {
            "Memory"   -> listOf(
                "Cache"            to "Cache class (memory usage)",
                "Pool"             to "Object pool (memory usage)"
            )
            "CPU"      -> listOf(
                "Worker"           to "WorkManager worker",
                "SyncAdapter"      to "Sync adapter"
            )
            "Network"  -> listOf(
                "ApiService"       to "Retrofit API service",
                "RemoteDataSource" to "Remote data source",
                "ApiClient"        to "API client"
            )
            "Battery"  -> listOf(
                "Receiver"         to "BroadcastReceiver",
                "Worker"           to "WorkManager worker"
            )
            "UI / FPS" -> listOf(
                "Adapter"          to "RecyclerView adapter",
                "ItemDecoration"   to "RecyclerView decoration"
            )
            else -> emptyList()
        }

        for (root in appRoots) {
            searchByPatterns(root, patterns, results)
            if (results.size >= maxResults) break
        }

        return results.take(maxResults)
    }

    private fun searchByPatterns(
        dir: VirtualFile,
        patterns: List<Pair<String, String>>,
        results: MutableList<DetectedFile>
    ) {
        for (child in dir.children) {
            if (child.isDirectory) searchByPatterns(child, patterns, results)
            else if (child.extension in listOf("kt", "java")) {
                for ((suffix, reason) in patterns) {
                    if (child.nameWithoutExtension.endsWith(suffix)) {
                        results.add(DetectedFile(child, child.nameWithoutExtension, reason))
                        break
                    }
                }
            }
        }
    }
}