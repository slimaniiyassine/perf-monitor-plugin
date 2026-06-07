package com.perfmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.perfmonitor.PerfMonitorSettings
import com.perfmonitor.adb.AdbRunner
import com.perfmonitor.ai.ClaudeClient
import com.perfmonitor.ai.GeminiClient
import com.perfmonitor.ai.PromptBuilder
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class MonitorPanel(
    private val project: Project,
    private val monitorName: String,
    private val defaultPackage: String = "",
    private val onCapture: (packageName: String) -> String
) {

    val panel = JPanel(BorderLayout())

    // ── Documents ────────────────────────────────────────────────────
    private val outputDoc   = DefaultStyledDocument()
    private val analysisDoc = DefaultStyledDocument()

    // ── Text areas ───────────────────────────────────────────────────
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
        background = JBColor(Color(252, 252, 255), Color(28, 28, 35))
        foreground = JBColor(Color(30, 30, 30), Color(210, 210, 220))
        border = EmptyBorder(10, 14, 10, 14)
        text = "Click ✦ Analyse after capturing data to see AI insights here."
    }

    // ── Progress bar ─────────────────────────────────────────────────
    private val analysisProgressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        isStringPainted = true
        string = "  Analysing with AI..."
        preferredSize = Dimension(0, 22)
        foreground = JBColor(Color(99, 102, 241), Color(129, 140, 248))
    }

    // ── Status label ─────────────────────────────────────────────────
    private val statusLabel = JLabel("Ready").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor(Color(100, 100, 100), Color(150, 150, 150))
        border = JBUI.Borders.emptyLeft(8)
    }

    // ── Process combo ─────────────────────────────────────────────────
    private val processCombo = JComboBox<String>().apply {
        preferredSize = Dimension(240, 28)
        toolTipText = "Select the app process to monitor"
        font = font.deriveFont(12f)
    }

    // ── Mode toggle ──────────────────────────────────────────────────
    private val snapshotRadio = JRadioButton("Snapshot", true).apply { font = font.deriveFont(12f) }
    private val recordRadio   = JRadioButton("Record").apply { font = font.deriveFont(12f) }
    private val modeGroup     = ButtonGroup().also { it.add(snapshotRadio); it.add(recordRadio) }

    // ── Interval controls ─────────────────────────────────────────────
    private val intervalCombo = JComboBox(arrayOf("1s", "2s", "5s", "10s", "30s", "Custom")).apply {
        preferredSize = Dimension(75, 28)
        font = font.deriveFont(12f)
    }
    private val customSpinner = JSpinner(SpinnerNumberModel(3, 1, 300, 1)).apply {
        preferredSize = Dimension(58, 28)
        isVisible = false
    }
    private val intervalUnit = JLabel("s").apply { isVisible = false }

    // ── Buttons ───────────────────────────────────────────────────────
    private val refreshButton = styledButton("⟳", tooltip = "Refresh process list", small = true)
    private val startButton   = styledButton("▶  Start", primary = true).apply { isEnabled = false }
    private val stopButton    = styledButton("⏹  Stop").apply { isEnabled = false; isVisible = false }
    private val clearButton   = styledButton("Clear").apply { isEnabled = false }
    private val analyseButton = styledButton("✦  Analyse", accent = true).apply { isEnabled = false }

    // ── State ─────────────────────────────────────────────────────────
    private var isRecording  = false
    private var recordThread: Thread? = null
    private val samples      = mutableListOf<String>()
    private val timeFmt      = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var currentMode  = "Snapshot"

    init {
        // ── Toolbar ──────────────────────────────────────────────────
        val toolbar = buildToolbar()

        // ── Left pane: captured data ──────────────────────────────────
        val leftScroll = JScrollPane(outputArea).apply {
            border = titledBorder("📊  Captured Data")
            background = outputArea.background
        }

        // ── Right pane: AI analysis ───────────────────────────────────
        val rightPane = JPanel(BorderLayout()).apply {
            background = analysisArea.background
            add(analysisProgressBar, BorderLayout.NORTH)
            add(JScrollPane(analysisArea).apply {
                border = BorderFactory.createEmptyBorder()
                background = analysisArea.background
            }, BorderLayout.CENTER)
            border = titledBorder("✦  AI Analysis")
        }

        // ── Horizontal split ──────────────────────────────────────────
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightPane).apply {
            resizeWeight     = 0.45
            isContinuousLayout = true
            dividerSize      = 5
            background       = JBColor.background()
        }

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(splitPane, BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(6)
        panel.background = JBColor.background()

        // Set divider after layout
        SwingUtilities.invokeLater {
            val w = splitPane.width
            if (w > 0) splitPane.dividerLocation = (w * 0.45).toInt()
        }

        // ── Wire up listeners ─────────────────────────────────────────
        updateModeControls()
        snapshotRadio.addActionListener { updateModeControls() }
        recordRadio.addActionListener   { updateModeControls() }
        intervalCombo.addActionListener {
            val isCustom = intervalCombo.selectedItem == "Custom"
            customSpinner.isVisible = isCustom
            intervalUnit.isVisible  = isCustom
        }

        loadProcesses()
        refreshButton.addActionListener { loadProcesses() }
        processCombo.addActionListener  { startButton.isEnabled = processCombo.selectedItem != null }

        startButton.addActionListener {
            val pkg = selectedPackage() ?: return@addActionListener
            currentMode = if (snapshotRadio.isSelected) "Snapshot" else "Record"
            if (snapshotRadio.isSelected) runSnapshot(pkg) else startRecording(pkg)
        }

        stopButton.addActionListener  { stopRecording() }

        clearButton.addActionListener {
            outputArea.text   = ""
            setAnalysisText("Analysis will appear here after you click ✦ Analyse.")
            samples.clear()
            statusLabel.text        = "Cleared"
            clearButton.isEnabled   = false
            analyseButton.isEnabled = false
        }

        analyseButton.addActionListener {
            val pkg = selectedPackage() ?: return@addActionListener
            runAnalysis(pkg)
        }
    }

    // ── Toolbar builder ───────────────────────────────────────────────
    private fun buildToolbar(): JPanel {
        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(JLabel("Process:").apply { font = font.deriveFont(12f) })
            add(processCombo)
            add(refreshButton)
        }

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(JLabel("Mode:").apply { font = font.deriveFont(12f) })
            add(snapshotRadio)
            add(recordRadio)
            add(vSep())
            add(JLabel("Every:").apply { font = font.deriveFont(12f) })
            add(intervalCombo)
            add(customSpinner)
            add(intervalUnit)
            add(vSep())
            add(startButton)
            add(stopButton)
            add(clearButton)
            add(vSep())
            add(analyseButton)
            add(statusLabel)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(row1, BorderLayout.NORTH)
            add(row2, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────
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
                setCapturing(false)
            }
        }.start()
    }

    // ── Record ────────────────────────────────────────────────────────
    private fun startRecording(pkg: String) {
        isRecording   = true
        samples.clear()
        outputArea.text = ""
        setAnalysisText("Analysis will appear after you stop recording and click ✦ Analyse.")
        setRecording(true)
        statusLabel.text = "Recording $monitorName..."

        val intervalMs = if (intervalCombo.selectedItem == "Custom") {
            (customSpinner.value as Int) * 1000L
        } else {
            (intervalCombo.selectedItem as String).removeSuffix("s").toLong() * 1000L
        }

        recordThread = Thread {
            var n = 1
            while (isRecording) {
                val ts     = now()
                val data   = safeCapture(pkg)
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
        isRecording = false
        recordThread?.interrupt()
        SwingUtilities.invokeLater {
            statusLabel.text        = "Stopped — ${samples.size} samples at ${now()}"
            clearButton.isEnabled   = samples.isNotEmpty()
            analyseButton.isEnabled = samples.isNotEmpty()
            setRecording(false)
        }
    }

    // ── AI Analysis ───────────────────────────────────────────────────
    private fun runAnalysis(pkg: String) {
        val data     = getCapturedData()
        val settings = PerfMonitorSettings.instance()

        if (data.isBlank()) {
            statusLabel.text = "⚠ No data to analyse. Run a session first."
            return
        }

        analyseButton.isEnabled       = false
        setAnalysisText("")
        statusLabel.text              = "Analysing..."
        analysisProgressBar.isVisible = true

        when (settings.provider) {
            "CLAUDE" -> runClaudeAnalysis(pkg, data, settings.claudeApiKey)
            "GEMINI" -> runGeminiAnalysis(pkg, data, settings.geminiApiKey)
            else     -> runCopilotAnalysis(pkg, data)
        }
    }

    private fun appendToAnalysis(text: String) {
        try {
            val attrs = SimpleAttributeSet()
            analysisDoc.insertString(analysisDoc.length, text, attrs)
            analysisArea.caretPosition = analysisDoc.length
        } catch (_: Exception) {}
    }

    private fun setAnalysisText(text: String) {
        try {
            analysisDoc.remove(0, analysisDoc.length)
            if (text.isNotEmpty()) appendToAnalysis(text)
        } catch (_: Exception) {
            analysisArea.text = text
        }
    }

    private fun runClaudeAnalysis(pkg: String, data: String, apiKey: String) {
        if (apiKey.isBlank()) {
            SwingUtilities.invokeLater {
                setAnalysisText("⚠ No Claude API key set.\n\nGo to Settings → Perf Monitor and add your key from console.anthropic.com")
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
                statusLabel.text              = "No API key configured"
            }
            return
        }
        val prompt = PromptBuilder.build(monitorName, pkg, currentMode, data)
        ClaudeClient.analyse(
            prompt  = prompt,
            apiKey  = apiKey,
            onToken = { token -> SwingUtilities.invokeLater { appendToAnalysis(token) } },
            onDone  = { SwingUtilities.invokeLater {
                statusLabel.text              = "Analysis complete at ${now()}"
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
            }},
            onError = { err -> SwingUtilities.invokeLater {
                appendToAnalysis("\n\n⚠ Error: $err")
                statusLabel.text              = "Analysis failed"
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
            }}
        )
    }

    private fun runGeminiAnalysis(pkg: String, data: String, apiKey: String) {
        if (apiKey.isBlank()) {
            SwingUtilities.invokeLater {
                setAnalysisText("⚠ No Gemini API key set.\n\nGo to Settings → Perf Monitor and add your free key from aistudio.google.com")
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
                statusLabel.text              = "No API key configured"
            }
            return
        }
        val prompt = PromptBuilder.build(monitorName, pkg, currentMode, data)
        GeminiClient.analyse(
            prompt  = prompt,
            apiKey  = apiKey,
            onToken = { token ->
                println("TOKEN_RECEIVED: length=${token.length}")
                SwingUtilities.invokeLater { appendToAnalysis(token) }
            },
            onDone  = { SwingUtilities.invokeLater {
                println("ANALYSIS_DOC_LENGTH: ${analysisDoc.length}")
                statusLabel.text              = "Analysis complete at ${now()}"
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
            }},
            onError = { err -> SwingUtilities.invokeLater {
                appendToAnalysis("\n\n⚠ Error: $err")
                statusLabel.text              = "Analysis failed"
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
            }}
        )
    }

    private fun runCopilotAnalysis(pkg: String, data: String) {
        try {
            val markdown  = PromptBuilder.buildCopilotMarkdown(monitorName, pkg, currentMode, data)
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(markdown), null)

            val basePath = project.basePath ?: throw Exception("No project path found")
            val dir  = File("$basePath/.perf-monitor").also { it.mkdirs() }
            val file = File(dir, "${monitorName.lowercase()}-analysis.md")
            file.writeText(markdown)

            SwingUtilities.invokeLater {
                try {
                    val twm = ToolWindowManager.getInstance(project)
                    (twm.getToolWindow("GitHub Copilot Chat")
                        ?: twm.getToolWindow("Copilot Chat")
                        ?: twm.getToolWindow("GitHub Copilot"))?.show()
                } catch (_: Exception) {}

                setAnalysisText("""
                    ✓ Prompt copied to clipboard!
                    
                    Just paste and send:
                    1. Click inside the Copilot Chat panel that just opened
                    2. Press Cmd+V to paste
                    3. Press Enter
                    
                    (Prompt also saved to .perf-monitor/${monitorName.lowercase()}-analysis.md)
                """.trimIndent())
                statusLabel.text              = "Prompt copied — paste into Copilot Chat"
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
            }
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                setAnalysisText("⚠ Error: ${e.message}")
                statusLabel.text              = "Failed"
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private fun safeCapture(pkg: String) =
        try { onCapture(pkg) } catch (e: Exception) { "Error: ${e.message}" }

    private fun selectedPackage(): String? {
        val item = processCombo.selectedItem as? String
        if (item.isNullOrBlank()) { statusLabel.text = "⚠ Select a process first"; return null }
        return item.trim().split("\\s+".toRegex()).last()
    }

    private fun updateModeControls() {
        val rec = recordRadio.isSelected
        intervalCombo.isVisible   = rec
        customSpinner.isVisible   = rec && intervalCombo.selectedItem == "Custom"
        intervalUnit.isVisible    = rec && intervalCombo.selectedItem == "Custom"
        stopButton.isVisible      = rec
    }

    private fun setCapturing(active: Boolean) {
        startButton.isEnabled   = !active
        processCombo.isEnabled  = !active
        refreshButton.isEnabled = !active
    }

    private fun setRecording(active: Boolean) {
        startButton.isEnabled     = !active
        stopButton.isEnabled      = active
        processCombo.isEnabled    = !active
        refreshButton.isEnabled   = !active
        snapshotRadio.isEnabled   = !active
        recordRadio.isEnabled     = !active
        intervalCombo.isEnabled   = !active
        customSpinner.isEnabled   = !active
    }

    private fun now() = LocalTime.now().format(timeFmt)

    private fun vSep() = JSeparator(SwingConstants.VERTICAL).apply {
        preferredSize = Dimension(2, 22)
    }

    private fun titledBorder(title: String) =
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor(Color(220, 220, 220), Color(60, 60, 60)), 1, true),
            title,
            0, 0,
            Font("Helvetica Neue", Font.BOLD, 11),
            JBColor(Color(100, 100, 100), Color(160, 160, 160))
        )

    private fun styledButton(
        text: String,
        tooltip: String? = null,
        small: Boolean = false,
        primary: Boolean = false,
        accent: Boolean = false
    ) = JButton(text).apply {
        toolTipText = tooltip
        font = if (small) font.deriveFont(11f) else font.deriveFont(12f)
        isFocusPainted = false
        if (primary) {
            background = JBColor(Color(59, 130, 246), Color(37, 99, 235))
            foreground = Color.WHITE
            isOpaque   = true
        }
        if (accent) {
            background = JBColor(Color(99, 102, 241), Color(79, 70, 229))
            foreground = Color.WHITE
            isOpaque   = true
        }
    }

    private fun loadProcesses() {
        refreshButton.isEnabled = false
        startButton.isEnabled   = false
        statusLabel.text        = "Checking for device..."
        processCombo.removeAllItems()

        Thread {
            // Check device connection first
            if (!AdbRunner.isDeviceConnected()) {
                SwingUtilities.invokeLater {
                    processCombo.addItem("No device connected")
                    statusLabel.text        = "⚠ No device — start emulator or connect device, then click ⟳"
                    refreshButton.isEnabled = true
                }
                return@Thread
            }

            val processes = try {
                ProcessBuilder(AdbRunner.resolvedAdbPath(), "shell", "ps", "-e")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readLines()
                    .drop(1)
                    .mapNotNull { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        parts.lastOrNull()?.takeIf {
                            it.contains(".") &&
                                    !it.startsWith("[") &&
                                    !it.startsWith("/") &&   // ← filters out adb path
                                    it.length < 100           // ← sanity check
                        }
                    }
                    .distinct().sorted()
            } catch (e: Exception) { emptyList() }

            if (processes.isEmpty()) {
                SwingUtilities.invokeLater {
                    statusLabel.text        = "⚠ No processes found — is the app running?"
                    refreshButton.isEnabled = true
                }
                return@Thread
            }

            val foreground = AdbRunner.getForegroundPackage()

            SwingUtilities.invokeLater {
                processes.forEach { processCombo.addItem(it) }
                val toSelect = foreground ?: defaultPackage.takeIf { it.isNotBlank() }
                if (toSelect != null) {
                    val match = processes.indexOfFirst { it.contains(toSelect) }
                    if (match >= 0) processCombo.selectedIndex = match
                }
                startButton.isEnabled   = processCombo.selectedItem != null
                refreshButton.isEnabled = true
                statusLabel.text        = if (foreground != null)
                    "${processes.size} processes — auto-selected foreground app"
                else
                    "${processes.size} processes found"
            }
        }.start()
    }

    fun getCapturedData(): String =
        if (samples.isNotEmpty()) samples.joinToString("\n") else outputArea.text
}