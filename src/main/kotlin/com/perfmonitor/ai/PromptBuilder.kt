package com.perfmonitor.ai

object PromptBuilder {

    fun build(
        monitorName: String,
        packageName: String,
        sessionMode: String,   // "Snapshot" or "Record"
        capturedData: String
    ): String {
        val dataPreview = capturedData.take(4000) // keep tokens bounded

        return """
            You are an expert Android performance engineer.
            
            The developer has captured a $sessionMode session from the $monitorName monitor
            for the app: $packageName
            
            --- Captured Data ---
            $dataPreview
            --- End of Data ---
            
            Please provide:
            
            1. SUMMARY
               A plain-English explanation of what the data shows. Highlight anything unusual or concerning.
            
            2. ISSUES FOUND
               List any performance problems, ranked by severity (Critical / High / Medium / Low).
               For each issue explain WHY it is a problem.
            
            3. BEFORE / AFTER
               For each issue, show a concrete code fix with a before and after example in Kotlin or Java.
            
            4. QUICK WINS
               List 2-3 immediate things the developer can do right now to improve $monitorName performance.
            
            Keep explanations accessible to a mid-level Android developer.
            Be specific — reference actual values from the captured data where possible.
        """.trimIndent()
    }

    fun buildCopilotMarkdown(
        monitorName: String,
        packageName: String,
        sessionMode: String,
        capturedData: String
    ): String {
        val dataPreview = capturedData.take(4000)

        return """
            # Perf Monitor — $monitorName Analysis Request
            
            **App:** `$packageName`
            **Mode:** $sessionMode
            **Monitor:** $monitorName
            
            ## Captured Data
            
            ```
            $dataPreview
            ```
            
            ## What I need from you
            
            1. **Summary** — plain-English explanation of what this data shows
            2. **Issues Found** — list problems ranked by severity (Critical / High / Medium / Low), explain WHY each is a problem
            3. **Before / After** — concrete Kotlin/Java code fixes for each issue
            4. **Quick Wins** — 2-3 things I can do right now to improve $monitorName performance
            
            Please reference actual values from the data above where possible.
        """.trimIndent()
    }
}