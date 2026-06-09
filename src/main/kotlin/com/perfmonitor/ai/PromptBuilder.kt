package com.perfmonitor.ai

object PromptBuilder {

    // ── Per-file analysis prompt ──────────────────────────────────────
    fun buildForFile(
        monitorName: String,
        packageName: String,
        sessionMode: String,
        capturedData: String,
        foreground: String,
        fileName: String?,
        fileContent: String
    ): String {
        val dataPreview = capturedData.take(3000)
        val screenLine  = if (foreground.isNotBlank()) "Foreground screen: $foreground" else ""
        val fileSection = if (!fileName.isNullOrBlank() && fileContent.isNotBlank()) """
            --- SOURCE FILE: $fileName ---
            $fileContent
            --- END OF FILE ---
        """.trimIndent() else ""

        val fileInstruction = if (!fileName.isNullOrBlank()) """
            IMPORTANT: Focus this analysis specifically on $fileName.
            - Reference real class names, method names, and line patterns from the file above.
            - Point to the exact location in $fileName where each issue or fix applies.
            - Use the actual variable and field names visible in the code.
        """.trimIndent() else ""

        return """
            You are an expert Android performance engineer.
            App: $packageName
            Monitor: $monitorName  |  Session: $sessionMode
            $screenLine

            $fileSection

            --- Captured Performance Data ---
            $dataPreview
            --- End of Data ---

            $fileInstruction

            Provide a focused analysis for ${fileName ?: "this session"}.
            The source file above has line numbers prepended (e.g. "  42: fun onResume()").
            Always reference issues using the format: `FileName.kt:lineNumber` — e.g. `$fileName:42`.

            ## Issues Found
            List problems ranked by severity: **[Critical]** / **[High]** / **[Medium]** / **[Low]**
            For each issue use this exact format:
            > **[Severity]** `$fileName:LINE` — short title
            > Why: one sentence explaining the problem
            > Evidence: the specific metric or value from the captured data

            ## Before / After
            For each issue show a diff-style fix:
            ```
            // $fileName:LINE  ← BEFORE
            <existing code from the file>

            // $fileName:LINE  ← AFTER
            <fixed code>
            ```
            Use the actual method and variable names from the file. Do not invent names.

            ## Quick Wins
            Numbered list of 2-3 changes in $fileName, each with the line number:
            1. `$fileName:LINE` — what to change and why

            Be specific. Every issue and fix must cite a real line number from the file above.
        """.trimIndent()
    }

    // ── Final merged summary prompt ───────────────────────────────────
    fun buildFinalSummary(
        monitorName: String,
        packageName: String,
        sessionMode: String,
        capturedData: String,
        fileResults: Map<String, String>
    ): String {
        val dataPreview = capturedData.take(2000)

        val perFileSection = fileResults.entries.joinToString("\n\n") { (name, result) ->
            "### $name\n${result.take(1500)}"
        }

        return """
            You are an expert Android performance engineer producing a final consolidated report.
            App: $packageName
            Monitor: $monitorName  |  Session: $sessionMode

            --- Captured Performance Data (summary) ---
            $dataPreview
            --- End of Data ---

            --- Per-file Analysis Results ---
            $perFileSection
            --- End of Per-file Results ---

            Produce a final report with this exact structure:

            # $monitorName Performance Report — $packageName

            ## Per-File Breakdown
            For each file analysed, one short paragraph summarising the key finding and the recommended fix.
            Use the actual class and method names from the per-file results above.

            ## Merged Issue List
            All issues across all files deduplicated and ranked by severity: Critical → High → Medium → Low.
            Each entry: **[Severity] FileName.kt — issue description — one-line fix**.

            ## Action Plan
            A prioritised step-by-step plan (numbered list) the team should follow to fix the top issues.
            Start with the highest-impact, lowest-effort changes.
            Reference specific file names and method names throughout.

            ## Summary
            Two sentences: overall memory health verdict and the single most important thing to fix first.
        """.trimIndent()
    }

    // ── Standard single-pass prompt (no files / Copilot clipboard) ────
    fun build(
        monitorName: String,
        packageName: String,
        sessionMode: String,
        capturedData: String,
        foregroundScreen: String = "",
        sourceContext: String = ""
    ): String {
        val dataPreview    = capturedData.take(3000)
        val screenSection  = if (foregroundScreen.isNotBlank()) "Foreground screen: $foregroundScreen\n" else ""
        val sourceSection  = if (sourceContext.isNotBlank()) """
            --- Project Source Files ---
            $sourceContext
            --- End of Source Files ---
        """.trimIndent() else ""
        val sourceInstruct = if (sourceContext.isNotBlank()) """
            IMPORTANT: Reference real class names, method names, and fields from the source files above.
            Point to the exact file and class where each fix should be applied.
        """.trimIndent() else ""

        return """
            You are an expert Android performance engineer.
            App: $packageName  |  Monitor: $monitorName  |  Session: $sessionMode
            $screenSection
            $sourceSection

            --- Captured Performance Data ---
            $dataPreview
            --- End of Data ---

            $sourceInstruct

            ## Summary
            Plain-English explanation of what the data shows. Highlight anything unusual.

            ## Issues Found
            Ranked by severity (Critical / High / Medium / Low).
            For each: explain WHY it is a problem, reference the specific metric.

            ## Before / After
            Concrete Kotlin/Java fixes for each issue.

            ## Quick Wins
            2-3 immediate improvements.
        """.trimIndent()
    }

    // ── Copilot clipboard markdown ────────────────────────────────────
    fun buildCopilotMarkdown(
        monitorName: String,
        packageName: String,
        sessionMode: String,
        capturedData: String,
        foregroundScreen: String = "",
        sourceContext: String = ""
    ): String {
        val dataPreview   = capturedData.take(3000)
        val screenSection = if (foregroundScreen.isNotBlank()) "\n**Foreground Screen:** `$foregroundScreen`" else ""
        val sourceSection = if (sourceContext.isNotBlank()) "\n## Source Files\n$sourceContext\n" else ""

        return """
            # Perf Monitor — $monitorName Analysis
            **App:** `$packageName` | **Mode:** $sessionMode$screenSection
            $sourceSection
            ## Captured Data
            ```
            $dataPreview
            ```
            ## What I need
            1. **Summary** — what this data shows
            2. **Issues Found** — ranked by severity, explain WHY each is a problem
            3. **Before / After** — concrete Kotlin fixes using real class names if source provided
            4. **Quick Wins** — 2-3 immediate improvements
        """.trimIndent()
    }
}