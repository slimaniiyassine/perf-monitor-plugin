package com.perfmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.JBUI
import com.perfmonitor.PerfMonitorSettings
import com.perfmonitor.adb.AdbRunner
import com.perfmonitor.ai.ClaudeClient
import com.perfmonitor.ai.GeminiClient
import com.perfmonitor.ai.PromptBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.text.DefaultStyledDocument

class MonitorPanel(
    private val project: Project,
    private val monitorName: String,
    private val defaultPackage: String = "",
    private val onCapture: (packageName: String) -> String
) {

    val panel = JPanel(BorderLayout())

    // ── Captured data area ───────────────────────────────────────────
    private val outputDoc = DefaultStyledDocument()
    private val outputArea = JTextPane(outputDoc).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        text = "Select a process and a mode, then start a session."
        foreground = UIManager.getColor("TextArea.foreground") ?: java.awt.Color.WHITE
        background = UIManager.getColor("TextArea.background") ?: java.awt.Color(43, 43, 43)
    }

    // ── Analysis area ────────────────────────────────────────────────
    private val analysisDoc = DefaultStyledDocument()
    private val analysisArea = JTextPane(analysisDoc).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        text = "Analysis will appear here after you click Analyse."
        foreground = UIManager.getColor("TextArea.foreground") ?: java.awt.Color.WHITE
        background = UIManager.getColor("TextArea.background") ?: java.awt.Color(43, 43, 43)
    }

    // ── Progress bar ─────────────────────────────────────────────────
    private val analysisProgressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        isStringPainted = true
        string = "Analysing..."
        preferredSize = Dimension(0, 4)
    }

    private val statusLabel = JLabel("Ready").apply {
        border = JBUI.Borders.emptyLeft(8)
    }

    private val processCombo = JComboBox<String>().apply {
        preferredSize = Dimension(260, 28)
        toolTipText = "Select the app process to monitor"
    }

    // Mode toggle
    private val snapshotRadio = JRadioButton("Snapshot", true)
    private val recordRadio   = JRadioButton("Record")
    private val modeGroup     = ButtonGroup().also {
        it.add(snapshotRadio); it.add(recordRadio)
    }

    // Interval controls
    private val intervalCombo = JComboBox(arrayOf("1s", "2s", "5s", "10s", "30s", "Custom")).apply {
        preferredSize = Dimension(80, 28)
    }
    private val customSpinner = JSpinner(SpinnerNumberModel(3, 1, 300, 1)).apply {
        preferredSize = Dimension(60, 28)
        isVisible = false
    }
    private val intervalUnit = JLabel("s").apply { isVisible = false }

    // Buttons
    private val refreshButton = JButton("⟳").apply { toolTipText = "Refresh process list" }
    private val startButton   = JButton("▶ Start").apply { isEnabled = false }
    private val stopButton    = JButton("⏹ Stop").apply { isEnabled = false }
    private val clearButton   = JButton("Clear").apply { isEnabled = false }
    private val analyseButton = JButton("✦ Analyse").apply {
        isEnabled = false
        toolTipText = "Analyse captured data with AI"
    }

    // Recording state
    private var isRecording  = false
    private var recordThread: Thread? = null
    private val samples      = mutableListOf<String>()
    private val timeFmt      = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var currentMode  = "Snapshot"

    init {
        // ── Row 1: process picker ────────────────────────────────────
        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("Process:"))
            add(processCombo)
            add(refreshButton)
        }

        // ── Row 2: mode + interval + buttons ────────────────────────
        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("Mode:"))
            add(snapshotRadio)
            add(recordRadio)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 22) })
            add(JLabel("Every:"))
            add(intervalCombo)
            add(customSpinner)
            add(intervalUnit)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 22) })
            add(startButton)
            add(stopButton)
            add(clearButton)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 22) })
            add(analyseButton)
            add(statusLabel)
        }

        val toolbar = JPanel(BorderLayout()).apply {
            add(row1, BorderLayout.NORTH)
            add(row2, BorderLayout.SOUTH)
            border = JBUI.Borders.emptyBottom(4)
        }

        // ── Use JSplitPane instead of JBSplitter ─────────────────────
        val outputScroll = JScrollPane(outputArea).apply {
            border = BorderFactory.createTitledBorder("Captured Data")
        }

        val analysisPanel = JPanel(BorderLayout()).apply {
            add(analysisProgressBar, BorderLayout.NORTH)
            add(JScrollPane(analysisArea), BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("AI Analysis")
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, outputScroll, analysisPanel).apply {
            resizeWeight = 0.55
            isContinuousLayout = true
            dividerSize = 6
        }

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(splitPane, BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(4)

        // Set divider after panel is shown
        SwingUtilities.invokeLater {
            val h = splitPane.height
            if (h > 0) splitPane.dividerLocation = (h * 0.55).toInt()
        }

        // ── Listeners ────────────────────────────────────────────────
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

        processCombo.addActionListener {
            startButton.isEnabled = processCombo.selectedItem != null
        }

        startButton.addActionListener {
            val pkg = selectedPackage() ?: return@addActionListener
            currentMode = if (snapshotRadio.isSelected) "Snapshot" else "Record"
            if (snapshotRadio.isSelected) runSnapshot(pkg) else startRecording(pkg)
        }

        stopButton.addActionListener { stopRecording() }

        clearButton.addActionListener {
            outputArea.text   = ""
            analysisArea.text = "Analysis will appear here after you click Analyse."
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

    // ── Snapshot ──────────────────────────────────────────────────────
    private fun runSnapshot(pkg: String) {
        setCapturing(true)
        outputArea.text = "Taking snapshot for $pkg ..."

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
        isRecording = true
        samples.clear()
        outputArea.text   = ""
        analysisArea.text = "Analysis will appear here after you stop recording and click Analyse."
        setRecording(true)
        statusLabel.text  = "Recording $monitorName..."

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
                    outputArea.document.insertString(outputArea.document.length, sample, null)
                    outputArea.caretPosition = outputArea.document.length
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
        analysisArea.text             = ""
        statusLabel.text              = "Analysing with ${settings.provider}..."
        analysisProgressBar.isVisible = true

        when (settings.provider) {
            "CLAUDE" -> runClaudeAnalysis(pkg, data, settings.claudeApiKey)
            "GEMINI" -> runGeminiAnalysis(pkg, data, settings.geminiApiKey)
            else     -> runCopilotAnalysis(pkg, data)
        }
    }

    private fun appendToAnalysis(text: String) {
        println("APPEND_CALLED: length=${text.length} first50=${text.take(50)}")
        try {
            analysisDoc.insertString(analysisDoc.length, text, null)
            println("APPEND_DONE: docLength=${analysisDoc.length}")
            analysisArea.caretPosition = analysisDoc.length
        } catch (e: Exception) {
            println("APPEND_ERROR: ${e.message}")
        }
    }

    private fun runClaudeAnalysis(pkg: String, data: String, apiKey: String) {
        if (apiKey.isBlank()) {
            SwingUtilities.invokeLater {
                analysisArea.text             = "⚠ No Claude API key set.\n\nGo to Settings → Perf Monitor and add your key from console.anthropic.com"
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
                analysisArea.text             = "⚠ No Gemini API key set.\n\nGo to Settings → Perf Monitor and add your free key from aistudio.google.com"
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
            onDone = { SwingUtilities.invokeLater {
                println("ANALYSIS_DOC_LENGTH: ${analysisDoc.length}")
                println("ANALYSIS_TEXT: ${analysisDoc.getText(0, minOf(100, analysisDoc.length))}")
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
            val markdown = PromptBuilder.buildCopilotMarkdown(monitorName, pkg, currentMode, data)
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(markdown), null)

            val basePath = project.basePath ?: throw Exception("No project path found")
            val dir  = File("$basePath/.perf-monitor").also { it.mkdirs() }
            val file = File(dir, "${monitorName.lowercase()}-analysis.md")
            file.writeText(markdown)

            SwingUtilities.invokeLater {
                try {
                    val twm     = ToolWindowManager.getInstance(project)
                    val copilot = twm.getToolWindow("GitHub Copilot Chat")
                        ?: twm.getToolWindow("Copilot Chat")
                        ?: twm.getToolWindow("GitHub Copilot")
                    copilot?.show()
                } catch (_: Exception) {}

                analysisArea.text = """
                    ✓ Prompt copied to clipboard!
                    
                    Just paste and send:
                    1. Click inside the Copilot Chat panel that just opened
                    2. Press Cmd+V to paste
                    3. Press Enter
                    
                    (Prompt also saved to .perf-monitor/${monitorName.lowercase()}-analysis.md)
                """.trimIndent()
                statusLabel.text              = "Prompt copied — paste into Copilot Chat"
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
            }
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                analysisArea.text             = "⚠ Error: ${e.message}"
                statusLabel.text              = "Failed"
                analyseButton.isEnabled       = true
                analysisProgressBar.isVisible = false
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private fun safeCapture(pkg: String) = try { onCapture(pkg) } catch (e: Exception) { "Error: ${e.message}" }

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

    private fun loadProcesses() {
        refreshButton.isEnabled = false
        startButton.isEnabled   = false
        statusLabel.text        = "Loading processes..."
        processCombo.removeAllItems()

        Thread {
            val processes = try {
                ProcessBuilder(AdbRunner.resolvedAdbPath(), "shell", "ps", "-e")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readLines()
                    .drop(1)
                    .mapNotNull { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        parts.lastOrNull()?.takeIf { it.contains(".") && !it.startsWith("[") }
                    }
                    .distinct().sorted()
            } catch (e: Exception) { listOf("Error: ${e.message}") }

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