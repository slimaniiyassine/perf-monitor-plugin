package com.perfmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.perfmonitor.PerfMonitorSettings
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
    private val processCombo: JComboBox<String>,    // ← shared from factory
    private val providerCombo: JComboBox<String>,   // ← shared from factory
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
    private val intervalUnit  = JLabel("s").apply { isVisible = false }

    private val startButton   = RoundedButton("▶  Start", primary = true).apply { isEnabled = false }
    private val stopButton    = RoundedButton("⏹  Stop").apply { isEnabled = false; isVisible = false }
    private val clearButton   = RoundedButton("Clear").apply { isEnabled = false }
    private val analyseButton = GradientButton("✦  Analyse").apply { isEnabled = false }

    private var isRecording  = false
    private var recordThread: Thread? = null
    private val samples      = mutableListOf<String>()
    private val timeFmt      = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var currentMode  = "Snapshot"

    init {
        val toolbar = buildToolbar()

        val leftScroll = JScrollPane(outputArea).apply {
            border = titledBorder("📊  Captured Data")
            background = outputArea.background
        }

        val rightPane = JPanel(BorderLayout()).apply {
            background = JBColor(Color(245, 245, 245), Color(30, 30, 30))
            add(analysisProgressBar, BorderLayout.NORTH)
            add(JScrollPane(analysisArea).apply {
                border = BorderFactory.createEmptyBorder()
                background = JBColor(Color(245, 245, 245), Color(30, 30, 30))
            }, BorderLayout.CENTER)
            border = titledBorder("✦  AI Analysis")
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightPane).apply {
            resizeWeight       = 0.45
            isContinuousLayout = true
            dividerSize        = 5
            background         = JBColor.background()
        }

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(splitPane, BorderLayout.CENTER)
        panel.border     = JBUI.Borders.empty(6)
        panel.background = JBColor.background()

        SwingUtilities.invokeLater {
            val w = splitPane.width
            if (w > 0) splitPane.dividerLocation = (w * 0.45).toInt()
            // Enable start if a real process is already selected
            startButton.isEnabled = selectedPackage() != null
        }

        updateModeControls()
        snapshotRadio.addActionListener { updateModeControls() }
        recordRadio.addActionListener   { updateModeControls() }
        intervalCombo.addActionListener {
            val isCustom = intervalCombo.selectedItem == "Custom"
            customSpinner.isVisible = isCustom
            intervalUnit.isVisible  = isCustom
        }

        // Enable/disable start when shared combo changes
        processCombo.addActionListener {
            startButton.isEnabled = selectedPackage() != null
        }

        startButton.addActionListener {
            val pkg = selectedPackage() ?: return@addActionListener
            currentMode = if (snapshotRadio.isSelected) "Snapshot" else "Record"
            if (snapshotRadio.isSelected) runSnapshot(pkg) else startRecording(pkg)
        }

        stopButton.addActionListener { stopRecording() }

        clearButton.addActionListener {
            outputArea.text = ""
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

    private fun buildToolbar(): JPanel {
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
            add(row2, BorderLayout.CENTER)
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
    }

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

    private fun startRecording(pkg: String) {
        isRecording = true
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

    private fun runAnalysis(pkg: String) {
        val data     = getCapturedData()
        val settings = PerfMonitorSettings.instance()

        if (data.isBlank()) {
            statusLabel.text = "⚠ No data to analyse. Run a session first."
            return
        }

        analyseButton.isEnabled       = false
        analyseButton.startAnimation()
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
            var remaining = text
            while (remaining.isNotEmpty()) {
                val boldStart = remaining.indexOf("**")
                if (boldStart < 0) { insertStyled(remaining, bold = false); break }
                if (boldStart > 0) insertStyled(remaining.substring(0, boldStart), bold = false)
                val boldEnd = remaining.indexOf("**", boldStart + 2)
                if (boldEnd < 0) { insertStyled(remaining.substring(boldStart), bold = false); break }
                insertStyled(remaining.substring(boldStart + 2, boldEnd), bold = true)
                remaining = remaining.substring(boldEnd + 2)
            }
            analysisArea.caretPosition = analysisDoc.length
        } catch (_: Exception) {}
    }

    private fun insertStyled(text: String, bold: Boolean) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setBold(attrs, bold)
        StyleConstants.setFontSize(attrs, 13)
        StyleConstants.setFontFamily(attrs, "Helvetica Neue")
        StyleConstants.setForeground(attrs, JBColor(Color(40, 40, 40), Color(200, 200, 200)))
        analysisDoc.insertString(analysisDoc.length, text, attrs)
    }

    private fun setAnalysisText(text: String) {
        try {
            analysisDoc.remove(0, analysisDoc.length)
            if (text.isNotEmpty()) appendToAnalysis(text)
        } catch (_: Exception) { analysisArea.text = text }
    }

    private fun finishAnalysis() {
        statusLabel.text              = "Analysis complete at ${now()}"
        analyseButton.isEnabled       = true
        analyseButton.stopAnimation()
        analysisProgressBar.isVisible = false
        // Scroll back to top so user reads from the beginning
        SwingUtilities.invokeLater { analysisArea.caretPosition = 0 }
    }

    private fun failAnalysis(err: String) {
        appendToAnalysis("\n\n⚠ Error: $err")
        statusLabel.text              = "Analysis failed"
        analyseButton.isEnabled       = true
        analyseButton.stopAnimation()
        analysisProgressBar.isVisible = false
    }

    private fun runClaudeAnalysis(pkg: String, data: String, apiKey: String) {
        if (apiKey.isBlank()) {
            SwingUtilities.invokeLater {
                setAnalysisText("⚠ No Claude API key.\n\nGo to Settings → Perf Monitor.")
                analyseButton.isEnabled       = true
                analyseButton.stopAnimation()
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
            onDone  = { SwingUtilities.invokeLater { finishAnalysis() } },
            onError = { err -> SwingUtilities.invokeLater { failAnalysis(err) } }
        )
    }

    private fun runGeminiAnalysis(pkg: String, data: String, apiKey: String) {
        if (apiKey.isBlank()) {
            SwingUtilities.invokeLater {
                setAnalysisText("⚠ No Gemini API key.\n\nGo to Settings → Perf Monitor or select a different provider.")
                analyseButton.isEnabled       = true
                analyseButton.stopAnimation()
                analysisProgressBar.isVisible = false
                statusLabel.text              = "No API key configured"
            }
            return
        }
        val prompt = PromptBuilder.build(monitorName, pkg, currentMode, data)
        GeminiClient.analyse(
            prompt  = prompt,
            apiKey  = apiKey,
            onToken = { token -> SwingUtilities.invokeLater { appendToAnalysis(token) } },
            onDone  = { SwingUtilities.invokeLater { finishAnalysis() } },
            onError = { err -> SwingUtilities.invokeLater { failAnalysis(err) } }
        )
    }

    private fun runCopilotAnalysis(pkg: String, data: String) {
        try {
            val markdown  = PromptBuilder.buildCopilotMarkdown(monitorName, pkg, currentMode, data)
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(markdown), null)

            val basePath = project.basePath ?: throw Exception("No project path found")
            File("$basePath/.perf-monitor").mkdirs()
            File("$basePath/.perf-monitor/${monitorName.lowercase()}-analysis.md").writeText(markdown)

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
                analyseButton.stopAnimation()
                analysisProgressBar.isVisible = false
            }
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                setAnalysisText("⚠ Error: ${e.message}")
                statusLabel.text              = "Failed"
                analyseButton.isEnabled       = true
                analyseButton.stopAnimation()
                analysisProgressBar.isVisible = false
            }
        }
    }

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

    private fun setCapturing(active: Boolean) {
        startButton.isEnabled = !active
    }

    private fun setRecording(active: Boolean) {
        startButton.isEnabled   = !active
        stopButton.isEnabled    = active
        snapshotRadio.isEnabled = !active
        recordRadio.isEnabled   = !active
        intervalCombo.isEnabled = !active
        customSpinner.isEnabled = !active
    }

    private fun now() = LocalTime.now().format(timeFmt)

    private fun vSep() = JSeparator(SwingConstants.VERTICAL).apply {
        preferredSize = Dimension(2, 22)
    }

    private fun titledBorder(title: String) =
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(
                JBColor(Color(220, 220, 220), Color(60, 60, 60)), 1, true
            ),
            title, 0, 0,
            Font("Helvetica Neue", Font.BOLD, 11),
            JBColor(Color(100, 100, 100), Color(160, 160, 160))
        )

    fun getCapturedData(): String =
        if (samples.isNotEmpty()) samples.joinToString("\n") else outputArea.text
}

// ── Rounded dark button ───────────────────────────────────────────────
class RoundedButton(
    text: String,
    private val primary: Boolean = false,
    private val small: Boolean = false
) : JButton(text) {

    private val primaryBg      = Color(59,  130, 246)
    private val primaryHover   = Color(37,  99,  235)
    private val normalBg       = Color(44,  44,  46)
    private val normalHover    = Color(58,  58,  62)
    private val disabledBg     = Color(30,  30,  32)
    private val primaryBorder  = Color(96,  165, 250)
    private val normalBorder   = Color(75,  75,  80)
    private val disabledBorder = Color(45,  45,  50)
    private val primaryFg      = Color.WHITE
    private val normalFg       = Color(210, 210, 215)
    private val disabledFg     = Color(80,  80,  85)
    private var hovered        = false

    init {
        isOpaque = false; isFocusPainted = false
        isBorderPainted = false; isContentAreaFilled = false
        font = font.deriveFont(if (small) 11f else 12f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(if (small) 32 else 90, 28)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true;  repaint() }
            override fun mouseExited(e: java.awt.event.MouseEvent)  { hovered = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height; val arc = 10
        g2.color = when {
            !isEnabled -> disabledBg; primary && hovered -> primaryHover
            primary -> primaryBg; hovered -> normalHover; else -> normalBg
        }
        g2.fillRoundRect(0, 0, w, h, arc, arc)
        g2.stroke = BasicStroke(if (primary && isEnabled) 1.5f else 1f)
        g2.color = when { !isEnabled -> disabledBorder; primary -> primaryBorder; else -> normalBorder }
        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
        g2.color = when { !isEnabled -> disabledFg; primary -> primaryFg; else -> normalFg }
        g2.font = font
        val fm = g2.fontMetrics
        g2.drawString(text, (w - fm.stringWidth(text)) / 2, (h + fm.ascent - fm.descent) / 2)
    }
}

// ── Animated gradient Analyse button ─────────────────────────────────
class GradientButton(text: String) : JButton(text) {

    private val gradientColors = listOf(
        Color(66, 133, 244), Color(103, 58, 183),
        Color(0, 172, 193),  Color(66, 133, 244)
    )
    private var animating  = false
    private var phase      = 0f
    private var timer: Timer? = null
    private var hovered    = false
    private val idleBg     = Color(44, 44, 46)
    private val idleBorder = Color(75, 75, 80)
    private val idleFg     = Color(210, 210, 215)
    private val disabledBg = Color(30, 30, 32)
    private val disabledFg = Color(80, 80, 85)

    init {
        isOpaque = false; isFocusPainted = false
        isBorderPainted = false; isContentAreaFilled = false
        font = font.deriveFont(Font.PLAIN, 12f)
        preferredSize = Dimension(120, 28)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true;  repaint() }
            override fun mouseExited(e: java.awt.event.MouseEvent)  { hovered = false; repaint() }
        })
    }

    fun startAnimation() {
        animating = true; phase = 0f
        timer = Timer(30) { phase = (phase + 0.02f) % 1f; repaint() }
        timer?.start()
    }

    fun stopAnimation() {
        animating = false; timer?.stop(); timer = null; repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height; val arc = 10
        when {
            !isEnabled -> {
                g2.color = disabledBg; g2.fillRoundRect(0, 0, w, h, arc, arc)
                g2.stroke = BasicStroke(1f); g2.color = Color(45, 45, 50)
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
            }
            animating -> {
                val total = gradientColors.size - 1
                val seg = (phase * total).toInt().coerceIn(0, total - 1)
                val t = (phase * total) - seg
                val c1 = gradientColors[seg]; val c2 = gradientColors[seg + 1]
                val blended = Color(
                    (c1.red   + (c2.red   - c1.red)   * t).toInt().coerceIn(0, 255),
                    (c1.green + (c2.green - c1.green) * t).toInt().coerceIn(0, 255),
                    (c1.blue  + (c2.blue  - c1.blue)  * t).toInt().coerceIn(0, 255)
                )
                g2.paint = GradientPaint(0f, 0f, blended, w.toFloat(), h.toFloat(), c2)
                g2.fillRoundRect(0, 0, w, h, arc, arc)
                g2.stroke = BasicStroke(1.5f); g2.color = Color(255, 255, 255, 60)
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
            }
            else -> {
                g2.color = if (hovered) Color(58, 58, 62) else idleBg
                g2.fillRoundRect(0, 0, w, h, arc, arc)
                g2.stroke = BasicStroke(1f); g2.color = idleBorder
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
            }
        }
        g2.color = when { !isEnabled -> disabledFg; animating -> Color.WHITE; else -> idleFg }
        g2.font = font
        val fm = g2.fontMetrics
        g2.drawString(text, (w - fm.stringWidth(text)) / 2, (h + fm.ascent - fm.descent) / 2)
    }
}