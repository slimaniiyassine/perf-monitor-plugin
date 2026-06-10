package com.perfmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.perfmonitor.PerfMonitorSettings
import com.perfmonitor.ai.ClaudeClient
import com.perfmonitor.ai.CopilotCliClient
import com.perfmonitor.ai.GeminiClient
import com.perfmonitor.ai.PromptBuilder
import com.intellij.openapi.vfs.VirtualFile
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class MonitorPanel(
    private val project: Project,
    private val monitorName: String,
    private val processCombo: JComboBox<String>,
    private val providerCombo: JComboBox<String>,
    private val defaultPackage: String = "",
    private val onCapture: (packageName: String) -> String
) {

    val panel = JPanel(BorderLayout())

    private val outputDoc   = DefaultStyledDocument()
    private val analysisDoc = DefaultStyledDocument()

    private val outputArea = JTextPane(outputDoc).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = JBColor(Color(245, 245, 245), Color(30, 30, 30))
        foreground = JBColor(Color(40, 40, 40), Color(200, 200, 200))
        border = EmptyBorder(8, 10, 8, 10)
        text = "Run a session to see captured data here."
    }

    private val analysisArea = JTextPane(analysisDoc).apply {
        isEditable = false
        font = Font("Helvetica Neue", Font.PLAIN, 13)
        background = JBColor(Color(245, 245, 245), Color(30, 30, 30))
        foreground = JBColor(Color(40, 40, 40), Color(200, 200, 200))
        border = EmptyBorder(10, 14, 10, 14)
        text = "Click ✦ Analyse after capturing data to see AI insights here."
    }

    private val analysisProgressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        isStringPainted = true
        string = "  Analysing..."
        preferredSize = Dimension(0, 22)
        foreground = Color(99, 102, 241)
    }

    // ── Next-file approval bar ────────────────────────────────────────
    private val nextFileBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        isOpaque = false; isVisible = false
    }
    private val nextFileLabel = JLabel("").apply { font = font.deriveFont(Font.BOLD, 12f) }
    private val yesBtn        = RoundedButton("Next →", primary = true)
    private val yesToAllBtn   = RoundedButton("All Remaining")
    private val skipSummaryBtn = RoundedButton("Skip to Summary")

    private val statusLabel = JLabel("Ready").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor(Color(100, 100, 100), Color(150, 150, 150))
        border = JBUI.Borders.emptyLeft(8)
    }

    private val snapshotRadio = JRadioButton("Snapshot", true).apply { font = font.deriveFont(12f); isOpaque = false }
    private val recordRadio   = JRadioButton("Record").apply { font = font.deriveFont(12f); isOpaque = false }
    private val modeGroup     = ButtonGroup().also { it.add(snapshotRadio); it.add(recordRadio) }

    private val intervalCombo = JComboBox(arrayOf("1s", "2s", "5s", "10s", "30s", "Custom")).apply {
        preferredSize = Dimension(75, 28); font = font.deriveFont(12f)
    }
    private val customSpinner = JSpinner(SpinnerNumberModel(3, 1, 300, 1)).apply {
        preferredSize = Dimension(58, 28); isVisible = false
    }
    private val intervalUnit = JLabel("s").apply { isVisible = false }

    private val startButton   = RoundedButton("▶  Start", primary = true).apply { isEnabled = false }
    private val stopButton    = RoundedButton("⏹  Stop").apply { isEnabled = false; isVisible = false }
    private val clearButton   = RoundedButton("Clear").apply { isEnabled = false }
    private val analyseButton = GradientButton("✦  Analyse").apply { isEnabled = false }

    // Stops the current AI stream mid-flight
    private val stopAnalysisButton = RoundedButton("⏹  Stop AI").apply {
        isEnabled = false
        toolTipText = "Stop the current AI analysis"
    }
    private val analysisAborted = AtomicBoolean(false)

    private val sourcePickerWrapper = JPanel(BorderLayout()).apply { isOpaque = false }
    private var sourceFilePicker: SourceFilePicker? = null

    private var isRecording  = false
    private var recordThread: Thread? = null
    private val samples      = mutableListOf<String>()
    private val timeFmt      = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var currentMode  = "Snapshot"

    // ── Sequential analysis state ─────────────────────────────────────
    private var pendingFiles   = mutableListOf<VirtualFile>()
    private var completedFiles = mutableListOf<VirtualFile>()
    private val fileResults    = mutableMapOf<String, String>()
    private var acceptAll      = false
    private var capturedDataRef = ""
    private var pkgRef          = ""
    private var screenRef       = ""

    init {
        nextFileBar.add(nextFileLabel)
        nextFileBar.add(yesBtn)
        nextFileBar.add(yesToAllBtn)
        nextFileBar.add(skipSummaryBtn)

        yesBtn.addActionListener        { analyseNextFile() }
        yesToAllBtn.addActionListener   { acceptAll = true; analyseNextFile() }
        skipSummaryBtn.addActionListener { hideNextFileBar(); runFinalSummary() }

        val toolbar = buildToolbar()

        val leftScroll = JScrollPane(outputArea).apply {
            border = titledBorder("📊  Captured Data")
            background = outputArea.background
        }

        val rightPane = JPanel(BorderLayout()).apply {
            background = JBColor(Color(245, 245, 245), Color(30, 30, 30))
            val topPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(analysisProgressBar, BorderLayout.NORTH)
                add(nextFileBar, BorderLayout.SOUTH)
            }
            add(topPanel, BorderLayout.NORTH)
            add(JScrollPane(analysisArea).apply {
                border = BorderFactory.createEmptyBorder()
                background = JBColor(Color(245, 245, 245), Color(30, 30, 30))
            }, BorderLayout.CENTER)
            border = titledBorder("✦  AI Analysis")
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightPane).apply {
            resizeWeight = 0.45; isContinuousLayout = true; dividerSize = 5
            background   = JBColor.background()
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(sourcePickerWrapper, BorderLayout.NORTH)
            add(splitPane, BorderLayout.CENTER)
        }

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(centerPanel, BorderLayout.CENTER)
        panel.border     = JBUI.Borders.empty(6)
        panel.background = JBColor.background()

        SwingUtilities.invokeLater {
            val w = splitPane.width
            if (w > 0) splitPane.dividerLocation = (w * 0.45).toInt()
            startButton.isEnabled = selectedPackage() != null
        }

        if (defaultPackage.isNotBlank()) initSourcePicker(defaultPackage)

        updateModeControls()
        snapshotRadio.addActionListener { updateModeControls() }
        recordRadio.addActionListener   { updateModeControls() }
        intervalCombo.addActionListener {
            val c = intervalCombo.selectedItem == "Custom"
            customSpinner.isVisible = c; intervalUnit.isVisible = c
        }

        startButton.addActionListener {
            val pkg = selectedPackage() ?: return@addActionListener
            currentMode = if (snapshotRadio.isSelected) "Snapshot" else "Record"
            if (snapshotRadio.isSelected) runSnapshot(pkg) else startRecording(pkg)
        }

        stopButton.addActionListener { stopRecording() }

        // Stop AI mid-stream
        stopAnalysisButton.addActionListener {
            analysisAborted.set(true)
            stopAnalysisButton.isEnabled = false
            appendToAnalysis("\n\n⏹  Analysis stopped by user.")
            hideNextFileBar()
            // If some files were already analysed, still offer a summary
            if (fileResults.isNotEmpty()) {
                statusLabel.text = "Stopped — generating summary from completed files..."
                runFinalSummary()
            } else {
                finishAnalysis(statusMsg = "Stopped by user")
            }
        }

        clearButton.addActionListener {
            outputArea.text = ""
            setAnalysisText("Analysis will appear here after you click ✦ Analyse.")
            samples.clear()
            statusLabel.text        = "Cleared"
            clearButton.isEnabled   = false
            analyseButton.isEnabled = false
            hideNextFileBar()
            fileResults.clear()
            analysisAborted.set(false)
        }

        analyseButton.addActionListener {
            val pkg = selectedPackage() ?: return@addActionListener
            startSequentialAnalysis(pkg)
        }
    }

    fun onProcessReady(pkg: String) {
        SwingUtilities.invokeLater {
            startButton.isEnabled = true
            initSourcePicker(pkg)
        }
    }

    private fun initSourcePicker(pkg: String) {
        sourceFilePicker = SourceFilePicker(project, monitorName, pkg)
        sourcePickerWrapper.removeAll()
        sourcePickerWrapper.add(sourceFilePicker!!.panel, BorderLayout.CENTER)
        sourcePickerWrapper.revalidate()
        sourcePickerWrapper.repaint()
    }

    private fun buildToolbar(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(JLabel("Mode:").apply { font = font.deriveFont(12f) })
            add(snapshotRadio); add(recordRadio)
            add(vSep())
            add(JLabel("Every:").apply { font = font.deriveFont(12f) })
            add(intervalCombo); add(customSpinner); add(intervalUnit)
            add(vSep())
            add(startButton); add(stopButton); add(clearButton)
            add(vSep())
            add(analyseButton); add(stopAnalysisButton)
            add(statusLabel)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(row, BorderLayout.CENTER)
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
    }

    // ── Capture ───────────────────────────────────────────────────────

    private fun runSnapshot(pkg: String) {
        setCapturing(true)
        outputArea.text = "Capturing $monitorName snapshot for $pkg ..."
        Thread {
            val result = safeCapture(pkg)
            SwingUtilities.invokeLater {
                outputArea.text          = result
                outputArea.caretPosition = 0
                statusLabel.text         = "Snapshot at ${now()}"
                clearButton.isEnabled    = true
                analyseButton.isEnabled  = true
                sourceFilePicker?.enableControls()
                setCapturing(false)
            }
        }.start()
    }

    private fun startRecording(pkg: String) {
        isRecording = true; samples.clear(); outputArea.text = ""
        setAnalysisText("Analysis will appear after you stop recording and click ✦ Analyse.")
        setRecording(true); statusLabel.text = "Recording $monitorName..."

        val intervalMs = if (intervalCombo.selectedItem == "Custom")
            (customSpinner.value as Int) * 1000L
        else (intervalCombo.selectedItem as String).removeSuffix("s").toLong() * 1000L

        recordThread = Thread {
            var n = 1
            while (isRecording) {
                val ts = now(); val data = safeCapture(pkg)
                val sample = "── Sample $n @ $ts ──────────\n$data\n"
                samples.add(sample)
                SwingUtilities.invokeLater {
                    outputDoc.insertString(outputDoc.length, sample, null)
                    outputArea.caretPosition = outputDoc.length
                    statusLabel.text = "Recording — ${samples.size} samples"
                }
                n++
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true }
        recordThread?.start()
    }

    private fun stopRecording() {
        isRecording = false; recordThread?.interrupt()
        SwingUtilities.invokeLater {
            statusLabel.text        = "Stopped — ${samples.size} samples at ${now()}"
            clearButton.isEnabled   = samples.isNotEmpty()
            analyseButton.isEnabled = samples.isNotEmpty()
            if (samples.isNotEmpty()) sourceFilePicker?.enableControls()
            setRecording(false)
        }
    }

    // ── Sequential analysis ───────────────────────────────────────────

    private fun startSequentialAnalysis(pkg: String) {
        val data = getCapturedData()
        if (data.isBlank()) { statusLabel.text = "⚠ No data to analyse. Run a session first."; return }

        capturedDataRef = data; pkgRef = pkg
        screenRef       = sourceFilePicker?.foregroundScreen ?: ""

        val selected = sourceFilePicker?.selectedFiles ?: emptyList()
        pendingFiles   = selected.toMutableList()
        completedFiles = mutableListOf()
        fileResults.clear()
        acceptAll = false
        analysisAborted.set(false)

        analyseButton.isEnabled       = false
        analyseButton.startAnimation()
        stopAnalysisButton.isEnabled  = true
        setAnalysisText("")
        analysisProgressBar.isVisible = true
        hideNextFileBar()

        if (pendingFiles.isEmpty()) {
            statusLabel.text = "Analysing $monitorName data (no source files selected)..."
            runSingleAnalysis(pkg, data, "", null, screenRef) { runFinalSummary() }
        } else {
            analyseNextFile()
        }
    }

    private fun analyseNextFile() {
        hideNextFileBar()
        if (analysisAborted.get()) return
        if (pendingFiles.isEmpty()) { runFinalSummary(); return }

        val file = pendingFiles.removeFirst()
        completedFiles.add(file)

        val fileContent = try { buildFileContext(file) }
        catch (e: Exception) { "// Could not read ${file.name}: ${e.message}" }

        val idx   = completedFiles.size
        val total = completedFiles.size + pendingFiles.size
        statusLabel.text              = "Analysing $idx / $total: ${file.name}"
        analysisProgressBar.isVisible = true

        appendToAnalysis("\n\n")
        insertFileDivider("📄  ${file.name}  ($idx / $total)")

        runSingleAnalysis(
            pkg         = pkgRef,
            data        = capturedDataRef,
            fileContext = fileContent,
            fileName    = file.name,
            foreground  = screenRef
        ) { result ->
            if (analysisAborted.get()) return@runSingleAnalysis
            fileResults[file.name] = result
            analysisProgressBar.isVisible = false

            when {
                pendingFiles.isEmpty() -> runFinalSummary()
                acceptAll              -> analyseNextFile()
                else                   -> showNextFileBar(pendingFiles.first().name)
            }
        }
    }

    private fun runSingleAnalysis(
        pkg: String, data: String, fileContext: String,
        fileName: String?, foreground: String,
        onDone: (result: String) -> Unit
    ) {
        val settings     = PerfMonitorSettings.instance()
        val prompt       = PromptBuilder.buildForFile(monitorName, pkg, currentMode, data, foreground, fileName, fileContext)
        val resultBuffer = StringBuilder()

        val onToken: (String) -> Unit = { token ->
            if (!analysisAborted.get()) {
                resultBuffer.append(token)
                SwingUtilities.invokeLater { appendToAnalysis(token) }
            }
        }
        val onDoneInner: () -> Unit = {
            if (!analysisAborted.get())
                SwingUtilities.invokeLater { onDone(resultBuffer.toString()) }
        }
        val onError: (String) -> Unit = { err ->
            SwingUtilities.invokeLater { failAnalysis(err) }
        }

        when (settings.provider) {
            "CLAUDE" -> {
                val key = settings.claudeApiKey
                if (key.isBlank()) { SwingUtilities.invokeLater { failAnalysis("No Claude API key.") }; return }
                ClaudeClient.analyse(prompt, key, onToken, onDoneInner, onError)
            }
            "GEMINI" -> {
                val key = settings.geminiApiKey
                if (key.isBlank()) { SwingUtilities.invokeLater { failAnalysis("No Gemini API key.") }; return }
                GeminiClient.analyse(prompt, key, onToken, onDoneInner, onError)
            }
            else -> {
                if (settings.copilotMode == "CLI")
                    CopilotCliClient.analyse(prompt, emptyList(), project.basePath, onToken, onDoneInner, onError)
                else
                    runCopilotClipboardAnalysis(pkg, data, fileContext, foreground)
            }
        }
    }

    private fun runFinalSummary() {
        if (analysisAborted.get() && fileResults.isEmpty()) {
            finishAnalysis(statusMsg = "Stopped — no files completed")
            return
        }
        hideNextFileBar()
        analysisProgressBar.isVisible = true
        statusLabel.text              = "Generating final summary..."
        stopAnalysisButton.isEnabled  = true

        appendToAnalysis("\n\n")
        insertFileDivider("📋  FINAL SUMMARY")

        val prompt   = PromptBuilder.buildFinalSummary(monitorName, pkgRef, currentMode, capturedDataRef, fileResults)
        val settings = PerfMonitorSettings.instance()

        // Collect the final summary text so we can write it to the report file
        val summaryBuffer = StringBuilder()

        val onToken: (String) -> Unit = { token ->
            if (!analysisAborted.get()) {
                summaryBuffer.append(token)
                SwingUtilities.invokeLater { appendToAnalysis(token) }
            }
        }
        val onDone: () -> Unit = {
            SwingUtilities.invokeLater {
                analysisProgressBar.isVisible = false
                stopAnalysisButton.isEnabled  = false
                analyseButton.isEnabled       = true
                analyseButton.stopAnimation()
                analysisArea.caretPosition    = 0

                // ── Write report to disk ──────────────────────────────
                val basePath = project.basePath
                if (basePath != null) {
                    try {
                        val reportFile = com.perfmonitor.report.ReportWriter.write(
                            projectBasePath  = basePath,
                            monitorName      = monitorName,
                            packageName      = pkgRef,
                            sessionMode      = currentMode,
                            foregroundScreen = screenRef,
                            capturedData     = capturedDataRef,
                            fileResults      = fileResults,
                            finalSummary     = summaryBuffer.toString(),
                            selectedFileNames = (sourceFilePicker?.selectedFiles ?: emptyList())
                                .map { it.name }
                        )
                        statusLabel.text = "✓ Analysis complete — report saved to ${reportFile.name}"

                        // Append a clickable note at the bottom of the analysis panel
                        appendToAnalysis("\n\n")
                        insertFileDivider("💾  Report saved")
                        appendToAnalysis("\n📄 ${reportFile.path}")
                    } catch (e: Exception) {
                        statusLabel.text = "Analysis complete (report save failed: ${e.message})"
                    }
                } else {
                    statusLabel.text = "Analysis complete at ${now()}"
                }
            }
        }
        val onError: (String) -> Unit = { err -> SwingUtilities.invokeLater { failAnalysis(err) } }

        when (settings.provider) {
            "CLAUDE" -> ClaudeClient.analyse(prompt, settings.claudeApiKey, onToken, onDone, onError)
            "GEMINI" -> GeminiClient.analyse(prompt, settings.geminiApiKey, onToken, onDone, onError)
            else     -> if (settings.copilotMode == "CLI")
                CopilotCliClient.analyse(prompt, emptyList(), project.basePath, onToken, onDone, onError)
            else runCopilotClipboardAnalysis(pkgRef, capturedDataRef, "", "")
        }
    }

    // ── Next-file bar ─────────────────────────────────────────────────

    private fun showNextFileBar(fileName: String) {
        nextFileLabel.text  = "Next: $fileName"
        nextFileBar.isVisible = true
        nextFileBar.revalidate()
    }

    private fun hideNextFileBar() { nextFileBar.isVisible = false }

    // ── File context builder ──────────────────────────────────────────

    private fun buildFileContext(file: VirtualFile): String {
        val content = String(file.contentsToByteArray())
        val useFull = sourceFilePicker?.sendFullContent ?: true
        // Add line numbers so the AI can reference them precisely
        return if (useFull) {
            content.lines()
                .take(200)
                .mapIndexed { i, line -> "${(i + 1).toString().padStart(4)}: $line" }
                .joinToString("\n")
                .let { if (content.lines().size > 200) "$it\n// ... (truncated at line 200)" else it }
        } else {
            content.lines()
                .mapIndexed { i, line -> (i + 1) to line }
                .filter { (_, line) ->
                    val t = line.trim()
                    t.startsWith("class ")       || t.startsWith("object ")     ||
                            t.startsWith("interface ")   || t.startsWith("fun ")        ||
                            t.startsWith("override fun") || t.startsWith("private fun") ||
                            t.startsWith("val ")         || t.startsWith("var ")        ||
                            t.startsWith("@")            || t.contains("suspend fun")
                }
                .joinToString("\n") { (lineNum, line) -> "${lineNum.toString().padStart(4)}: $line" }
        }
    }

    // ── Copilot clipboard ─────────────────────────────────────────────

    private fun runCopilotClipboardAnalysis(
        pkg: String, data: String,
        fileContext: String = "", foreground: String = ""
    ) {
        try {
            val markdown  = PromptBuilder.buildCopilotMarkdown(monitorName, pkg, currentMode, data, foreground, fileContext)
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(markdown), null)

            val basePath = project.basePath ?: throw Exception("No project path found")
            File("$basePath/perf-monitor").mkdirs()
            File("$basePath/perf-monitor/${monitorName.lowercase()}-analysis.md").writeText(markdown)

            SwingUtilities.invokeLater {
                try {
                    val twm = ToolWindowManager.getInstance(project)
                    (twm.getToolWindow("GitHub Copilot Chat")
                        ?: twm.getToolWindow("Copilot Chat")
                        ?: twm.getToolWindow("GitHub Copilot"))?.show()
                } catch (_: Exception) {}

                setAnalysisText("""
                    ✓ Prompt copied to clipboard!
                    
                    1. Click inside Copilot Chat
                    2. Press Cmd+V
                    3. Press Enter
                    
                    (Saved to .perf-monitor/${monitorName.lowercase()}-analysis.md)
                """.trimIndent())
                statusLabel.text              = "Prompt copied — paste into Copilot Chat"
                analyseButton.isEnabled       = true
                stopAnalysisButton.isEnabled  = false
                analyseButton.stopAnimation()
                analysisProgressBar.isVisible = false
            }
        } catch (e: Exception) {
            SwingUtilities.invokeLater { failAnalysis(e.message ?: "Unknown error") }
        }
    }

    // ── Rich text rendering ───────────────────────────────────────────

    private val colorH1     = JBColor(Color(30,  100, 200), Color(100, 160, 255))
    private val colorH2     = JBColor(Color(20,  140,  80), Color( 80, 200, 130))
    private val colorH3     = JBColor(Color(160,  80,   0), Color(220, 160,  60))
    private val colorCode   = JBColor(Color(140,  40, 140), Color(200, 120, 200))
    private val colorNormal = JBColor(Color( 40,  40,  40), Color(200, 200, 200))
    private val colorBold   = JBColor(Color( 20,  20,  20), Color(230, 230, 230))
    private val colorFile   = JBColor(Color(255, 255, 255), Color(255, 255, 255))
    private val colorFileBg = JBColor(Color( 60,  80, 160), Color( 40,  60, 140))

    private fun appendToAnalysis(text: String) {
        try {
            val lines = text.split("\n")
            lines.forEachIndexed { idx, line ->
                when {
                    line.startsWith("### ") -> insertHeader(line.removePrefix("### "), colorH3, 13f)
                    line.startsWith("## ")  -> insertHeader(line.removePrefix("## "),  colorH2, 14f)
                    line.startsWith("# ")   -> insertHeader(line.removePrefix("# "),   colorH1, 15f)
                    line.startsWith("---")  -> insertDivider()
                    else                    -> insertInline(line)
                }
                if (idx < lines.lastIndex) insertRaw("\n")
            }
            analysisArea.caretPosition = analysisDoc.length
        } catch (_: Exception) {}
    }

    private fun insertFileDivider(title: String) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setBold(attrs, true)
        StyleConstants.setFontSize(attrs, 13)
        StyleConstants.setFontFamily(attrs, "Helvetica Neue")
        StyleConstants.setForeground(attrs, colorFile)
        StyleConstants.setBackground(attrs, colorFileBg)
        StyleConstants.setSpaceAbove(attrs, 8f)
        StyleConstants.setSpaceBelow(attrs, 6f)
        analysisDoc.insertString(analysisDoc.length, "  $title  ", attrs)
        insertRaw("\n")
    }

    private fun insertHeader(text: String, color: Color, size: Float) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setBold(attrs, true)
        StyleConstants.setFontSize(attrs, size.toInt())
        StyleConstants.setFontFamily(attrs, "Helvetica Neue")
        StyleConstants.setForeground(attrs, color)
        StyleConstants.setSpaceAbove(attrs, 6f)
        StyleConstants.setSpaceBelow(attrs, 2f)
        analysisDoc.insertString(analysisDoc.length, text, attrs)
    }

    private fun insertDivider() {
        val attrs = SimpleAttributeSet()
        StyleConstants.setForeground(attrs, JBColor(Color(200, 200, 200), Color(70, 70, 70)))
        StyleConstants.setFontSize(attrs, 11)
        analysisDoc.insertString(analysisDoc.length, "────────────────────────────────────", attrs)
    }

    private fun insertInline(line: String) {
        var rem = line
        while (rem.isNotEmpty()) {
            when {
                rem.startsWith("**") -> {
                    val end = rem.indexOf("**", 2)
                    if (end < 0) { insertRaw(rem); break }
                    insertStyledSpan(rem.substring(2, end), bold = true, code = false)
                    rem = rem.substring(end + 2)
                }
                rem.startsWith("`") -> {
                    val end = rem.indexOf("`", 1)
                    if (end < 0) { insertRaw(rem); break }
                    insertStyledSpan(rem.substring(1, end), bold = false, code = true)
                    rem = rem.substring(end + 1)
                }
                else -> {
                    val next = minOf(
                        rem.indexOf("**").let { if (it < 0) Int.MAX_VALUE else it },
                        rem.indexOf("`").let  { if (it < 0) Int.MAX_VALUE else it }
                    )
                    if (next == Int.MAX_VALUE) { insertRaw(rem); break }
                    insertRaw(rem.substring(0, next)); rem = rem.substring(next)
                }
            }
        }
    }

    private fun insertRaw(text: String) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setBold(attrs, false); StyleConstants.setFontSize(attrs, 13)
        StyleConstants.setFontFamily(attrs, "Helvetica Neue")
        StyleConstants.setForeground(attrs, colorNormal)
        analysisDoc.insertString(analysisDoc.length, text, attrs)
    }

    private fun insertStyledSpan(text: String, bold: Boolean, code: Boolean) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setBold(attrs, bold || code)
        StyleConstants.setFontSize(attrs, if (code) 12 else 13)
        StyleConstants.setFontFamily(attrs, if (code) Font.MONOSPACED else "Helvetica Neue")
        StyleConstants.setForeground(attrs, if (code) colorCode else colorBold)
        analysisDoc.insertString(analysisDoc.length, text, attrs)
    }

    private fun setAnalysisText(text: String) {
        try {
            analysisDoc.remove(0, analysisDoc.length)
            if (text.isNotEmpty()) appendToAnalysis(text)
        } catch (_: Exception) { analysisArea.text = text }
    }

    private fun finishAnalysis(failed: Boolean = false, statusMsg: String = "") {
        statusLabel.text              = statusMsg.ifBlank { if (failed) "Analysis failed" else "Analysis complete at ${now()}" }
        analyseButton.isEnabled       = true
        analyseButton.stopAnimation()
        stopAnalysisButton.isEnabled  = false
        analysisProgressBar.isVisible = false
        hideNextFileBar()
        if (!failed) SwingUtilities.invokeLater { analysisArea.caretPosition = 0 }
    }

    private fun failAnalysis(err: String) {
        appendToAnalysis("\n\n⚠ Error: $err")
        finishAnalysis(failed = true)
    }

    // ── Misc helpers ──────────────────────────────────────────────────

    private fun safeCapture(pkg: String) =
        try { onCapture(pkg) } catch (e: Exception) { "Error: ${e.message}" }

    private fun selectedPackage(): String? {
        val item = processCombo.selectedItem as? String
        if (item.isNullOrBlank() || item == "No device connected") return null
        return item.trim().split("\\s+".toRegex()).last()
    }

    private fun updateModeControls() {
        val rec = recordRadio.isSelected
        intervalCombo.isVisible   = rec
        customSpinner.isVisible   = rec && intervalCombo.selectedItem == "Custom"
        intervalUnit.isVisible    = rec && intervalCombo.selectedItem == "Custom"
        stopButton.isVisible      = rec
    }

    private fun setCapturing(active: Boolean) { startButton.isEnabled = !active }

    private fun setRecording(active: Boolean) {
        startButton.isEnabled = !active; stopButton.isEnabled = active
        snapshotRadio.isEnabled = !active; recordRadio.isEnabled = !active
        intervalCombo.isEnabled = !active; customSpinner.isEnabled = !active
    }

    private fun now() = LocalTime.now().format(timeFmt)
    private fun vSep() = JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 22) }

    private fun titledBorder(title: String) =
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor(Color(220, 220, 220), Color(60, 60, 60)), 1, true),
            title, 0, 0, Font("Helvetica Neue", Font.BOLD, 11),
            JBColor(Color(100, 100, 100), Color(160, 160, 160))
        )

    fun getCapturedData(): String =
        if (samples.isNotEmpty()) samples.joinToString("\n") else outputArea.text
}

// ── Rounded dark button ───────────────────────────────────────────────
class RoundedButton(text: String, private val primary: Boolean = false, private val small: Boolean = false) : JButton(text) {
    private val primaryBg = Color(59, 130, 246); private val primaryHover = Color(37, 99, 235)
    private val normalBg  = Color(44, 44, 46);   private val normalHover  = Color(58, 58, 62)
    private val disabledBg = Color(30, 30, 32);  private val primaryBorder = Color(96, 165, 250)
    private val normalBorder = Color(75, 75, 80); private val disabledBorder = Color(45, 45, 50)
    private val primaryFg = Color.WHITE; private val normalFg = Color(210, 210, 215); private val disabledFg = Color(80, 80, 85)
    private var hovered = false
    init {
        isOpaque = false; isFocusPainted = false; isBorderPainted = false; isContentAreaFilled = false
        font = font.deriveFont(if (small) 11f else 12f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(if (small) 32 else 120, 28)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: java.awt.event.MouseEvent)  { hovered = false; repaint() }
        })
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height; val arc = 10
        g2.color = when { !isEnabled -> disabledBg; primary && hovered -> primaryHover; primary -> primaryBg; hovered -> normalHover; else -> normalBg }
        g2.fillRoundRect(0, 0, w, h, arc, arc)
        g2.stroke = BasicStroke(if (primary && isEnabled) 1.5f else 1f)
        g2.color = when { !isEnabled -> disabledBorder; primary -> primaryBorder; else -> normalBorder }
        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
        g2.color = when { !isEnabled -> disabledFg; primary -> primaryFg; else -> normalFg }
        g2.font = font; val fm = g2.fontMetrics
        g2.drawString(text, (w - fm.stringWidth(text)) / 2, (h + fm.ascent - fm.descent) / 2)
    }
}

// ── Animated gradient Analyse button ─────────────────────────────────
class GradientButton(text: String) : JButton(text) {
    private val gradientColors = listOf(Color(66,133,244), Color(103,58,183), Color(0,172,193), Color(66,133,244))
    private var animating = false; private var phase = 0f; private var timer: Timer? = null; private var hovered = false
    private val idleBg = Color(44,44,46); private val idleBorder = Color(75,75,80); private val idleFg = Color(210,210,215)
    private val disabledBg = Color(30,30,32); private val disabledFg = Color(80,80,85)
    init {
        isOpaque = false; isFocusPainted = false; isBorderPainted = false; isContentAreaFilled = false
        font = font.deriveFont(Font.PLAIN, 12f); preferredSize = Dimension(120, 28)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: java.awt.event.MouseEvent)  { hovered = false; repaint() }
        })
    }
    fun startAnimation() { animating = true; phase = 0f; timer = Timer(30) { phase = (phase + 0.02f) % 1f; repaint() }; timer?.start() }
    fun stopAnimation() { animating = false; timer?.stop(); timer = null; repaint() }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height; val arc = 10
        when {
            !isEnabled -> { g2.color = disabledBg; g2.fillRoundRect(0,0,w,h,arc,arc); g2.stroke = BasicStroke(1f); g2.color = Color(45,45,50); g2.drawRoundRect(0,0,w-1,h-1,arc,arc) }
            animating -> {
                val total = gradientColors.size - 1; val seg = (phase * total).toInt().coerceIn(0, total - 1); val t = (phase * total) - seg
                val c1 = gradientColors[seg]; val c2 = gradientColors[seg + 1]
                val blended = Color((c1.red+(c2.red-c1.red)*t).toInt().coerceIn(0,255),(c1.green+(c2.green-c1.green)*t).toInt().coerceIn(0,255),(c1.blue+(c2.blue-c1.blue)*t).toInt().coerceIn(0,255))
                g2.paint = GradientPaint(0f,0f,blended,w.toFloat(),h.toFloat(),c2); g2.fillRoundRect(0,0,w,h,arc,arc)
                g2.stroke = BasicStroke(1.5f); g2.color = Color(255,255,255,60); g2.drawRoundRect(0,0,w-1,h-1,arc,arc)
            }
            else -> { g2.color = if (hovered) Color(58,58,62) else idleBg; g2.fillRoundRect(0,0,w,h,arc,arc); g2.stroke = BasicStroke(1f); g2.color = idleBorder; g2.drawRoundRect(0,0,w-1,h-1,arc,arc) }
        }
        g2.color = when { !isEnabled -> disabledFg; animating -> Color.WHITE; else -> idleFg }
        g2.font = font; val fm = g2.fontMetrics
        g2.drawString(text, (w - fm.stringWidth(text)) / 2, (h + fm.ascent - fm.descent) / 2)
    }
}