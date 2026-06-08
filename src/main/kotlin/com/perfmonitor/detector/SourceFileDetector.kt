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

    // Prefixes we never follow — library/framework classes
    private val SKIP_PREFIXES = listOf(
        "android.", "androidx.", "kotlin.", "kotlinx.",
        "java.", "javax.", "com.google.", "com.squareup.",
        "io.reactivex.", "rx.", "okhttp3.", "retrofit2.",
        "dagger.", "hilt.", "koin.", "org.jetbrains."
    )

    // File name patterns we skip — generated files
    private val SKIP_FILES = listOf(
        "BuildConfig", "R", "Manifest",
        "databinding", "DataBindingComponent"
    )

    // ── Get foreground fragment from adb ──────────────────────────────
    fun getForegroundFragment(): String? {
        val output = AdbRunner.runShellCommand("dumpsys", "activity", "top")
        val lines  = output.lines()

        // Collect ALL "#0:" lines that look like real fragments
        // The last valid one is the currently visible fragment
        var lastValidFragment: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#0:")) {
                val name = trimmed.substringAfter("#0:").trim().substringBefore("{").trim()
                if (name.isNotBlank() &&
                    !name.startsWith("androidx.") &&
                    !name.startsWith("android.") &&
                    !name.contains("NavHost") &&
                    !name.contains("ReportFragment") &&
                    !name.contains("LifecycleDispatcher") &&
                    // Only accept classes that are actually screens
                    (name.endsWith("Fragment") || name.endsWith("Screen")) &&
                    // Skip known Android framework classes
                    name !in setOf("AutofillManager", "DialogFragment", "BottomSheetDialogFragment")
                ) {
                    lastValidFragment = name
                }
            }
        }

        println("FOREGROUND_FRAGMENT_FOUND: $lastValidFragment")
        return lastValidFragment
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

    // ── Build a map of className → VirtualFile for the whole project ──
    private fun buildProjectClassMap(project: Project): Map<String, VirtualFile> {
        val map = mutableMapOf<String, VirtualFile>()
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
            ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
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
                if (SKIP_FILES.none { name.startsWith(it) }) {
                    map[name] = child
                }
            }
        }
    }

    // ── Check if a class name should be followed ──────────────────────
    private fun shouldFollow(className: String, fullQualifiedName: String?): Boolean {
        if (SKIP_FILES.any { className.startsWith(it) }) return false
        if (fullQualifiedName != null &&
            SKIP_PREFIXES.any { fullQualifiedName.startsWith(it) }) return false
        return true
    }

    // ── Extract referenced class names from a file ────────────────────
    // Parses imports + direct class name references in the file body
    private fun extractReferencedClasses(
        file: VirtualFile,
        classMap: Map<String, VirtualFile>
    ): List<Pair<String, String>> { // Pair<simpleClassName, reason>
        val content = try { String(file.contentsToByteArray()) } catch (_: Exception) { return emptyList() }
        val results = mutableListOf<Pair<String, String>>()

        // 1. Parse import statements — most reliable source
        val importRegex = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)
        importRegex.findAll(content).forEach { match ->
            val fqn       = match.groupValues[1]
            val simple    = fqn.substringAfterLast('.')
            if (shouldFollow(simple, fqn) && classMap.containsKey(simple)) {
                val reason = classifyReference(simple)
                results.add(simple to reason)
            }
        }

        // 2. Find direct usages of known project classes in the body
        // (catches cases where fully qualified names are used inline)
        classMap.keys.forEach { name ->
            if (content.contains(name) &&
                results.none { it.first == name } &&
                shouldFollow(name, null)) {
                val reason = classifyReference(name)
                results.add(name to "Referenced in ${file.name}")
            }
        }

        return results.distinctBy { it.first }
    }

    // ── Classify why a class was included ─────────────────────────────
    private fun classifyReference(name: String): String = when {
        name.endsWith("ViewModel")           -> "ViewModel"
        name.endsWith("Repository")          -> "Repository"
        name.endsWith("Adapter")             -> "RecyclerView Adapter"
        name.endsWith("Service")             -> "Service"
        name.endsWith("Worker")              -> "WorkManager Worker"
        name.endsWith("UseCase")             -> "Use Case"
        name.endsWith("Mapper")              -> "Data Mapper"
        name.endsWith("Api")                 -> "API interface"
        name.endsWith("ApiService")          -> "API Service"
        name.endsWith("DataSource")          -> "Data Source"
        name.endsWith("Manager")             -> "Manager"
        name.endsWith("Helper")              -> "Helper class"
        name.endsWith("Util") ||
                name.endsWith("Utils") ||
                name.endsWith("Utility")             -> "Utility class"
        name.endsWith("UiState")             -> "UI State"
        name.endsWith("State")               -> "State class"
        name.endsWith("Event")               -> "UI Event"
        name.endsWith("View")                -> "Custom View"
        name.endsWith("Fragment")            -> "Fragment"
        name.endsWith("Activity")            -> "Activity"
        name.endsWith("BottomSheet") ||
                name.endsWith("BottomSheetFragment") -> "Bottom Sheet"
        name.endsWith("Dialog") ||
                name.endsWith("DialogFragment")      -> "Dialog"
        name.endsWith("Constants")           -> "Constants"
        name.endsWith("Extensions")          -> "Extensions"
        name.endsWith("Config")              -> "Configuration"
        name.endsWith("Interceptor")         -> "Network Interceptor"
        else                                 -> "Project class"
    }

    // ── Smart BFS traversal ───────────────────────────────────────────
    // Starts from rootClassName, follows references smartly,
    // stops at library classes, caps total files for token safety
    private fun smartTraverse(
        rootClassName: String,
        classMap: Map<String, VirtualFile>,
        maxFiles: Int = 20
    ): List<DetectedFile> {
        val visited = mutableSetOf<String>()
        val results = mutableListOf<DetectedFile>()
        val queue   = ArrayDeque<Pair<String, String>>() // className, reason

        queue.add(rootClassName to "Entry point")

        while (queue.isNotEmpty() && results.size < maxFiles) {
            val (className, reason) = queue.removeFirst()
            if (className in visited) continue
            visited.add(className)

            val file = classMap[className] ?: continue
            results.add(DetectedFile(file, className, reason))

            // Find what this file references and queue them
            extractReferencedClasses(file, classMap).forEach { (refName, refReason) ->
                if (refName !in visited && classMap.containsKey(refName)) {
                    queue.add(refName to refReason)
                }
            }
        }

        return results
    }

    // ── Main entry point ──────────────────────────────────────────────
    fun detectRelevantFiles(
        project: Project,
        monitorName: String,
        packageName: String
    ): List<DetectedFile> {
        // Build class map once for the whole session
        val classMap = buildProjectClassMap(project)



        // Get foreground fragment/activity
        val foregroundClass = getForegroundFragment()
            ?: getForegroundActivity()
        println("DETECTOR: classMap=${classMap.size} foreground=$foregroundClass pkg=$packageName")
        println("DETECTOR: sourceRoots=${
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .runReadAction<List<String>> {
                    ProjectRootManager.getInstance(project).contentSourceRoots
                        .map { it.path }.take(5)
                }
        }")
        println("DETECTOR: fragment=${getForegroundFragment()} activity=${getForegroundActivity()} pkg=$packageName")
        println("DETECTOR: classMap size=${classMap.size}, foreground=$foregroundClass")


        if (foregroundClass == null) {
            // No foreground class detected — return empty, user can add manually
            return emptyList()
        }

        // Smart BFS from the foreground class
        val traversed = smartTraverse(
            rootClassName = foregroundClass,
            classMap      = classMap,
            maxFiles      = 20
        )

        // Sort: entry point first, then by relevance to current monitor
        return traversed.sortedWith(compareBy(
            { it.className != foregroundClass },          // entry point first
            { !isRelevantToMonitor(it.className, monitorName) }, // monitor-relevant next
            { it.className }                              // alphabetical
        ))
    }

    // ── Fallback: get foreground Activity if no fragment found ────────
    private fun getForegroundActivity(): String? {
        val output = AdbRunner.runShellCommand("dumpsys", "activity", "activities")
        val line   = output.lines().firstOrNull {
            it.contains("mResumedActivity") || it.contains("topResumedActivity=")
        } ?: return null
        println("RESUMED_LINE: $line")
        // Match format: {hex packageName/.ActivityName ...}
        // e.g. ca.bell.selfserve.mybellmobile.bqat/.ui.MainActivity
        val match = Regex("""[a-z][\w.]+/\.?([\w.]+)""").find(line)
        val full   = match?.groupValues?.get(1) ?: return null
        // Get simple class name — last segment after dot
        val simple = full.substringAfterLast('.')
        println("ACTIVITY_SIMPLE: $simple")
        return simple.takeIf {
            it.endsWith("Activity") &&
                    it[0].isUpperCase() &&
                    it !in setOf("FragmentActivity", "AppCompatActivity", "ComponentActivity")
        }
    }

    // ── Check if a class name is relevant to current monitor ──────────
    private fun isRelevantToMonitor(className: String, monitorName: String): Boolean {
        return when (monitorName) {
            "Memory"   -> className.endsWith("ViewModel") || className.endsWith("Repository") ||
                    className.endsWith("Cache") || className.endsWith("Manager")
            "CPU"      -> className.endsWith("ViewModel") || className.endsWith("Worker") ||
                    className.endsWith("UseCase") || className.endsWith("Repository")
            "Network"  -> className.endsWith("Repository") || className.endsWith("ApiService") ||
                    className.endsWith("DataSource") || className.endsWith("Api")
            "Battery"  -> className.endsWith("Worker") || className.endsWith("Service") ||
                    className.endsWith("Receiver") || className.endsWith("Manager")
            "UI / FPS" -> className.endsWith("Adapter") || className.endsWith("View") ||
                    className.endsWith("ViewModel") || className.endsWith("UiState")
            else       -> false
        }
    }

    // ── Public helpers ────────────────────────────────────────────────
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