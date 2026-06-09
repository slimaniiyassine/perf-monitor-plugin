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

    private val SKIP_PREFIXES = listOf(
        "android.", "androidx.", "kotlin.", "kotlinx.",
        "java.", "javax.", "com.google.", "com.squareup.",
        "io.reactivex.", "rx.", "okhttp3.", "retrofit2.",
        "dagger.", "hilt.", "koin.", "org.jetbrains."
    )

    private val SKIP_FILES = listOf(
        "BuildConfig", "R", "Manifest",
        "databinding", "DataBindingComponent"
    )

    // Rough chars-per-token estimate; keeps total well under API limits
    private const val CHARS_PER_TOKEN   = 4
    private const val MAX_TOKEN_BUDGET  = 6_000   // ~24 000 chars total across all files
    private const val MAX_FILE_TOKENS   = 1_500   // ~6 000 chars per single file

    // ── Public: foreground detection ─────────────────────────────────

    fun getForegroundFragment(): String? {
        val output = AdbRunner.runShellCommand("dumpsys", "activity", "top")
        var last: String? = null
        for (line in output.lines()) {
            val t = line.trim()
            if (!t.startsWith("#0:")) continue
            val name = t.substringAfter("#0:").trim().substringBefore("{").trim()
            if (name.isNotBlank() &&
                !name.startsWith("androidx.") &&
                !name.startsWith("android.") &&
                !name.contains("NavHost") &&
                !name.contains("ReportFragment") &&
                !name.contains("LifecycleDispatcher") &&
                (name.endsWith("Fragment") || name.endsWith("Screen")) &&
                name !in setOf("AutofillManager", "DialogFragment", "BottomSheetDialogFragment")
            ) last = name
        }
        return last
    }

    fun getForegroundActivity(): String? {
        val output = AdbRunner.runShellCommand("dumpsys", "activity", "activities")
        val line   = output.lines().firstOrNull {
            it.contains("mResumedActivity") || it.contains("topResumedActivity=")
        } ?: return null
        val match  = Regex("""[a-z][\w.]+/\.?([\w.]+)""").find(line)
        val full   = match?.groupValues?.get(1) ?: return null
        val simple = full.substringAfterLast('.')
        return simple.takeIf {
            it.endsWith("Activity") && it[0].isUpperCase() &&
                    it !in setOf("FragmentActivity", "AppCompatActivity", "ComponentActivity")
        }
    }

    fun getForegroundScreen(): String? = getForegroundFragment() ?: getForegroundActivity()

    // ── Class map ─────────────────────────────────────────────────────

    private fun buildProjectClassMap(project: Project): Map<String, VirtualFile> {
        val map = mutableMapOf<String, VirtualFile>()
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
            ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
                val p = root.path
                if (!p.contains("/build/") && !p.contains("/generated/") && !p.contains("/.gradle/"))
                    indexDir(root, map)
            }
        }
        return map
    }

    private fun indexDir(dir: VirtualFile, map: MutableMap<String, VirtualFile>) {
        for (child in dir.children) {
            if (child.isDirectory) indexDir(child, map)
            else if (child.extension in listOf("kt", "java")) {
                val name = child.nameWithoutExtension
                if (SKIP_FILES.none { name.startsWith(it) }) map[name] = child
            }
        }
    }

    private fun shouldFollow(className: String, fqn: String?): Boolean {
        if (SKIP_FILES.any { className.startsWith(it) }) return false
        if (fqn != null && SKIP_PREFIXES.any { fqn.startsWith(it) }) return false
        return true
    }

    // ── Reference extraction ──────────────────────────────────────────

    private fun extractReferencedClasses(
        file: VirtualFile,
        classMap: Map<String, VirtualFile>
    ): List<Pair<String, String>> {
        val content = try { String(file.contentsToByteArray()) } catch (_: Exception) { return emptyList() }
        val results = mutableListOf<Pair<String, String>>()

        // 1. Import-based — most reliable
        Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE).findAll(content).forEach { m ->
            val fqn    = m.groupValues[1]
            val simple = fqn.substringAfterLast('.')
            if (shouldFollow(simple, fqn) && classMap.containsKey(simple))
                results.add(simple to classifyReference(simple))
        }

        // 2. Direct name references in body (catches inline FQN usage)
        classMap.keys.forEach { name ->
            if (content.contains(name) &&
                results.none { it.first == name } &&
                shouldFollow(name, null))
                results.add(name to "Referenced in ${file.name}")
        }

        return results.distinctBy { it.first }
    }

    private fun classifyReference(name: String): String = when {
        name.endsWith("ViewModel")                   -> "ViewModel"
        name.endsWith("Repository")                  -> "Repository"
        name.endsWith("Adapter")                     -> "RecyclerView Adapter"
        name.endsWith("Service")                     -> "Service"
        name.endsWith("Worker")                      -> "WorkManager Worker"
        name.endsWith("UseCase")                     -> "Use Case"
        name.endsWith("Mapper")                      -> "Data Mapper"
        name.endsWith("Api")                         -> "API interface"
        name.endsWith("ApiService")                  -> "API Service"
        name.endsWith("DataSource")                  -> "Data Source"
        name.endsWith("Manager")                     -> "Manager"
        name.endsWith("Helper")                      -> "Helper class"
        name.endsWith("Util") || name.endsWith("Utils")
                || name.endsWith("Utility")          -> "Utility class"
        name.endsWith("UiState")                     -> "UI State"
        name.endsWith("State")                       -> "State class"
        name.endsWith("Event")                       -> "UI Event"
        name.endsWith("View")                        -> "Custom View"
        name.endsWith("Fragment")                    -> "Fragment"
        name.endsWith("Activity")                    -> "Activity"
        name.endsWith("BottomSheet") ||
                name.endsWith("BottomSheetFragment") -> "Bottom Sheet"
        name.endsWith("Dialog") ||
                name.endsWith("DialogFragment")      -> "Dialog"
        name.endsWith("Constants")                   -> "Constants"
        name.endsWith("Extensions")                  -> "Extensions"
        name.endsWith("Config")                      -> "Configuration"
        name.endsWith("Interceptor")                 -> "Network Interceptor"
        else                                         -> "Project class"
    }

    // ── BFS traversal — token-budget based, not file-count ───────────
    // Only follows classes that are DIRECTLY imported by the entry point
    // or by classes at depth 1. Stops when token budget is exhausted.
    // This prevents padding to 20 with loosely-related files.

    private fun smartTraverse(
        rootClassName: String,
        classMap: Map<String, VirtualFile>,
        monitorName: String
    ): List<DetectedFile> {
        val visited      = mutableSetOf<String>()
        val results      = mutableListOf<DetectedFile>()
        val queue        = ArrayDeque<Triple<String, String, Int>>() // className, reason, depth
        var tokenBudget  = MAX_TOKEN_BUDGET

        queue.add(Triple(rootClassName, "Entry point", 0))

        while (queue.isNotEmpty() && tokenBudget > 0) {
            val (className, reason, depth) = queue.removeFirst()
            if (className in visited) continue
            visited.add(className)

            val file         = classMap[className] ?: continue
            val fileSize     = estimateTokens(file)

            // Skip very large files that would eat the entire budget alone
            if (fileSize > MAX_FILE_TOKENS && depth > 0) continue

            val tokensToUse  = minOf(fileSize, MAX_FILE_TOKENS)
            tokenBudget     -= tokensToUse
            if (tokenBudget < 0) break

            results.add(DetectedFile(file, className, reason))

            // Only follow references at depth 0 (entry point) and depth 1
            // Depth 2+ is too loosely related and causes the "always 20" problem
            if (depth <= 1) {
                extractReferencedClasses(file, classMap)
                    .filter { (refName, _) ->
                        refName !in visited &&
                                classMap.containsKey(refName) &&
                                // Prefer monitor-relevant classes when deciding what to follow
                                (depth == 0 || isRelevantToMonitor(refName, monitorName))
                    }
                    .forEach { (refName, refReason) ->
                        queue.add(Triple(refName, refReason, depth + 1))
                    }
            }
        }
        return results
    }

    // Rough token estimate for a file without reading its content twice
    private fun estimateTokens(file: VirtualFile): Int {
        return try {
            (file.length / CHARS_PER_TOKEN).toInt()
        } catch (_: Exception) { MAX_FILE_TOKENS }
    }

    // ── Main entry point ──────────────────────────────────────────────

    fun detectRelevantFiles(
        project: Project,
        monitorName: String,
        packageName: String
    ): List<DetectedFile> {
        val classMap        = buildProjectClassMap(project)
        val foregroundClass = getForegroundFragment() ?: getForegroundActivity()
        ?: return emptyList()

        val traversed = smartTraverse(foregroundClass, classMap, monitorName)

        return traversed.sortedWith(compareBy(
            { it.className != foregroundClass },
            { !isRelevantToMonitor(it.className, monitorName) },
            { it.className }
        ))
    }

    private fun isRelevantToMonitor(className: String, monitorName: String): Boolean = when (monitorName) {
        "Memory"   -> className.endsWith("ViewModel")   || className.endsWith("Repository") ||
                className.endsWith("Cache")        || className.endsWith("Manager")
        "CPU"      -> className.endsWith("ViewModel")   || className.endsWith("Worker") ||
                className.endsWith("UseCase")      || className.endsWith("Repository")
        "Network"  -> className.endsWith("Repository")  || className.endsWith("ApiService") ||
                className.endsWith("DataSource")   || className.endsWith("Api")
        "Battery"  -> className.endsWith("Worker")      || className.endsWith("Service") ||
                className.endsWith("Receiver")     || className.endsWith("Manager")
        "UI / FPS" -> className.endsWith("Adapter")     || className.endsWith("View") ||
                className.endsWith("ViewModel")    || className.endsWith("UiState")
        else       -> false
    }

    // ── Helpers ───────────────────────────────────────────────────────

    fun findFilesByClassName(project: Project, simpleClassName: String): List<VirtualFile> {
        val results = mutableListOf<VirtualFile>()
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
            ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
                searchDir(root, simpleClassName, results)
            }
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
}