package com.perfmonitor

import com.intellij.openapi.options.Configurable
import com.perfmonitor.ai.CopilotCliClient
import java.awt.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.swing.*
import javax.swing.border.EmptyBorder

class SettingsConfigurable : Configurable {

    private lateinit var providerCombo: JComboBox<String>
    private lateinit var claudeKeyField: JPasswordField
    private lateinit var geminiKeyField: JPasswordField
    private lateinit var claudeStatusLabel: JLabel
    private lateinit var geminiStatusLabel: JLabel
    private lateinit var claudeSection: JPanel
    private lateinit var geminiSection: JPanel
    private lateinit var copilotSection: JPanel
    private lateinit var clipboardRadio: JRadioButton
    private lateinit var cliRadio: JRadioButton

    override fun getDisplayName() = "Perf Monitor"

    override fun createComponent(): JComponent {
        val settings = PerfMonitorSettings.instance()
        val root     = JPanel(BorderLayout())
        val content  = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = EmptyBorder(12, 16, 12, 16)

        // ── Provider ──────────────────────────────────────────────────
        content.add(sectionLabel("AI Provider"))
        content.add(vgap(4))
        providerCombo = JComboBox(arrayOf(
            "GitHub Copilot Chat",
            "Claude (Anthropic)",
            "Gemini (Google AI Studio)"
        )).apply {
            maximumSize   = Dimension(Int.MAX_VALUE, 32)
            alignmentX    = Component.LEFT_ALIGNMENT
            selectedIndex = when (settings.provider) {
                "CLAUDE" -> 1
                "GEMINI" -> 2
                else     -> 0
            }
        }
        content.add(providerCombo)
        content.add(vgap(16))

        // ── Claude section ────────────────────────────────────────────
        claudeSection = buildKeySection(
            labelText    = "Claude API Key",
            hintText     = "Requires credits — from \$5",
            getLinkText  = "Get Key ↗",
            getLinkUrl   = "https://console.anthropic.com/settings/keys",
            keyFieldInit = settings.claudeApiKey,
            onFieldReady = { claudeKeyField = it },
            onStatusReady = { claudeStatusLabel = it },
            onTest = { key, btn, status ->
                testClaudeKey(key) { ok, msg ->
                    SwingUtilities.invokeLater {
                        btn.isEnabled = true
                        setStatus(status, if (ok) "✓ $msg" else "✗ $msg",
                            if (ok) Color(34, 160, 82) else Color(200, 60, 60))
                    }
                }
            }
        )
        content.add(claudeSection)
        content.add(vgap(16))

        // ── Gemini section ────────────────────────────────────────────
        geminiSection = buildKeySection(
            labelText    = "Gemini API Key",
            hintText     = "Free tier: 500 requests/day — no credit card needed",
            getLinkText  = "Get Free Key ↗",
            getLinkUrl   = "https://aistudio.google.com/apikey",
            keyFieldInit = settings.geminiApiKey,
            onFieldReady = { geminiKeyField = it },
            onStatusReady = { geminiStatusLabel = it },
            onTest = { key, btn, status ->
                testGeminiKey(key) { ok, msg ->
                    SwingUtilities.invokeLater {
                        btn.isEnabled = true
                        setStatus(status, if (ok) "✓ $msg" else "✗ $msg",
                            if (ok) Color(34, 160, 82) else Color(200, 60, 60))
                    }
                }
            }
        )
        content.add(geminiSection)
        content.add(vgap(16))

        // ── Copilot section ───────────────────────────────────────────
        copilotSection = buildCopilotSection(settings)
        content.add(copilotSection)

        // ── Visibility ────────────────────────────────────────────────
        fun updateVisibility() {
            claudeSection.isVisible  = providerCombo.selectedIndex == 1
            geminiSection.isVisible  = providerCombo.selectedIndex == 2
            copilotSection.isVisible = providerCombo.selectedIndex == 0
            root.revalidate(); root.repaint()
        }
        updateVisibility()
        providerCombo.addActionListener { updateVisibility() }

        root.add(content, BorderLayout.NORTH)
        return root
    }

    // ── Copilot section builder ───────────────────────────────────────
    private fun buildCopilotSection(settings: PerfMonitorSettings): JPanel {
        val panel = JPanel().apply {
            layout     = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque   = false
        }

        panel.add(sectionLabel("GitHub Copilot Mode"))
        panel.add(vgap(6))

        // Mode radio buttons
        val modeGroup = ButtonGroup()
        clipboardRadio = JRadioButton("Clipboard — copy prompt, paste into Copilot Chat manually").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isSelected = settings.copilotMode != "CLI"
            isOpaque   = false
        }
        cliRadio = JRadioButton("CLI — stream response directly into the plugin panel").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isSelected = settings.copilotMode == "CLI"
            isOpaque   = false
            isEnabled  = false  // disabled until detected
        }
        modeGroup.add(clipboardRadio)
        modeGroup.add(cliRadio)
        panel.add(clipboardRadio)
        panel.add(vgap(4))
        panel.add(cliRadio)
        panel.add(vgap(8))

        // Detect CLI row
        val detectBtn   = JButton("Detect CLI").apply { font = font.deriveFont(11f) }
        val cliStatus   = JLabel("Click 'Detect CLI' to check if Copilot CLI is installed").apply {
            font       = font.deriveFont(Font.PLAIN, 11f)
            foreground = Color(120, 120, 120)
        }
        val detectRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque   = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(detectBtn)
            add(cliStatus)
        }
        panel.add(detectRow)
        panel.add(vgap(6))

        // Info label
        panel.add(JLabel("<html><body style='width:400px'>" +
                "<b>Clipboard mode:</b> prompt is copied to clipboard and Copilot Chat opens — press Cmd+V and Enter.<br><br>" +
                "<b>CLI mode:</b> uses <code>copilot -p</code> to stream the response directly into the analysis panel. " +
                "Requires Copilot CLI installed (<code>npm install -g @github/copilot-cli</code>) and authenticated." +
                "</body></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            foreground = Color(120, 120, 120)
            font       = font.deriveFont(12f)
        })

        // If CLI was previously selected, try to re-enable the radio
        if (settings.copilotMode == "CLI") {
            Thread {
                val ok = CopilotCliClient.isAvailable()
                SwingUtilities.invokeLater {
                    cliRadio.isEnabled  = ok
                    if (!ok) {
                        clipboardRadio.isSelected = true
                        cliStatus.text      = "✗ CLI not found — select Clipboard mode or install CLI"
                        cliStatus.foreground = Color(200, 60, 60)
                    } else {
                        cliStatus.text       = "✓ ${CopilotCliClient.getVersion()}"
                        cliStatus.foreground = Color(34, 160, 82)
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        // Detect button action
        detectBtn.addActionListener {
            detectBtn.isEnabled  = false
            cliStatus.text       = "Detecting..."
            cliStatus.foreground = Color(100, 100, 100)
            Thread {
                val available = CopilotCliClient.isAvailable()
                val version   = if (available) CopilotCliClient.getVersion() else ""
                SwingUtilities.invokeLater {
                    detectBtn.isEnabled = true
                    if (available) {
                        cliStatus.text       = "✓ $version"
                        cliStatus.foreground = Color(34, 160, 82)
                        cliRadio.isEnabled   = true
                    } else {
                        cliStatus.text       = "✗ Not found — run: npm install -g @github/copilot-cli"
                        cliStatus.foreground = Color(200, 60, 60)
                        cliRadio.isEnabled   = false
                        clipboardRadio.isSelected = true
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        return panel
    }

    // ── Key section builder ───────────────────────────────────────────
    private fun buildKeySection(
        labelText: String,
        hintText: String,
        getLinkText: String,
        getLinkUrl: String,
        keyFieldInit: String,
        onFieldReady: (JPasswordField) -> Unit,
        onStatusReady: (JLabel) -> Unit,
        onTest: (String, JButton, JLabel) -> Unit
    ): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            alignmentX  = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 150)
        }
        val gbc = GridBagConstraints().apply {
            insets = Insets(3, 0, 3, 6)
            anchor = GridBagConstraints.WEST
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.NONE
        panel.add(sectionLabel(labelText), gbc)
        gbc.gridwidth = 1

        val keyField = JPasswordField(keyFieldInit, 36)
        onFieldReady(keyField)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.gridwidth = 2
        panel.add(keyField, gbc)
        gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE

        val testBtn = JButton("Test Key").apply { font = font.deriveFont(11f); preferredSize = Dimension(80, 28) }
        gbc.gridx = 2; gbc.gridy = 1
        panel.add(testBtn, gbc)

        val getBtn = JButton(getLinkText).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            isBorderPainted     = false
            isContentAreaFilled = false
            foreground = Color(59, 130, 246)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        gbc.gridx = 3; gbc.gridy = 1
        panel.add(getBtn, gbc)

        val status = JLabel(hintText).apply {
            font       = font.deriveFont(Font.PLAIN, 11f)
            foreground = Color(120, 120, 120)
        }
        onStatusReady(status)
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(status, gbc)

        testBtn.addActionListener {
            val key = String(keyField.password).trim()
            if (key.isBlank()) {
                setStatus(status, "⚠ Enter a key first", Color(200, 120, 0))
                return@addActionListener
            }
            testBtn.isEnabled = false
            setStatus(status, "Testing...", Color(100, 100, 100))
            onTest(key, testBtn, status)
        }
        getBtn.addActionListener { openUrl(getLinkUrl) }

        return panel
    }

    // ── Key validation ────────────────────────────────────────────────

    private fun testClaudeKey(key: String, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val conn = (URL("https://api.anthropic.com/v1/messages")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("x-api-key", key)
                    setRequestProperty("anthropic-version", "2023-06-01")
                    setRequestProperty("content-type", "application/json")
                    connectTimeout = 8_000; readTimeout = 10_000
                    doOutput = true
                }
                val body = """{"model":"claude-haiku-4-5-20251001","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                when (conn.responseCode) {
                    200  -> callback(true,  "Key is valid — Claude is ready")
                    401  -> callback(false, "Invalid API key")
                    403  -> callback(false, "Key lacks permission")
                    429  -> callback(true,  "Key valid but rate limited — add credits at console.anthropic.com")
                    else -> {
                        val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                        if (err.contains("credit") || err.contains("balance"))
                            callback(false, "Key valid but no credits — add at console.anthropic.com")
                        else
                            callback(false, "HTTP ${conn.responseCode}")
                    }
                }
            } catch (e: Exception) { callback(false, "Connection error: ${e.message}") }
        }.also { it.isDaemon = true }.start()
    }

    private fun testGeminiKey(key: String, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8_000; readTimeout = 10_000
                    doOutput = true
                }
                val body = """{"contents":[{"parts":[{"text":"hi"}]}],"generationConfig":{"maxOutputTokens":1}}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                when (conn.responseCode) {
                    200  -> callback(true,  "Key is valid — Gemini free tier ready")
                    400  -> callback(false, "Bad request — check key format")
                    403  -> callback(false, "Invalid or restricted API key")
                    429  -> callback(true,  "Key valid but quota exceeded — resets daily")
                    else -> callback(false, "HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) { callback(false, "Connection error: ${e.message}") }
        }.also { it.isDaemon = true }.start()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun openUrl(url: String) {
        try { Desktop.getDesktop().browse(URI(url)) } catch (_: Exception) {}
    }

    private fun sectionLabel(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 12f)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun setStatus(label: JLabel, text: String, color: Color) {
        label.text       = text
        label.foreground = color
    }

    private fun vgap(h: Int) = Box.createRigidArea(Dimension(0, h))

    // ── Configurable ──────────────────────────────────────────────────

    override fun isModified(): Boolean {
        val s = PerfMonitorSettings.instance()
        return providerIndexToKey(providerCombo.selectedIndex) != s.provider ||
                String(claudeKeyField.password) != s.claudeApiKey ||
                String(geminiKeyField.password) != s.geminiApiKey ||
                (if (cliRadio.isSelected) "CLI" else "CLIPBOARD") != s.copilotMode
    }

    override fun reset() {
        val settings = PerfMonitorSettings.instance()
        providerCombo.selectedIndex = when (settings.provider) {
            "CLAUDE" -> 1
            "GEMINI" -> 2
            else     -> 0
        }
        // Update visibility
        claudeSection.isVisible  = providerCombo.selectedIndex == 1
        geminiSection.isVisible  = providerCombo.selectedIndex == 2
        copilotSection.isVisible = providerCombo.selectedIndex == 0
    }

    override fun apply() {
        val s          = PerfMonitorSettings.instance()
        s.provider     = providerIndexToKey(providerCombo.selectedIndex)
        s.claudeApiKey = String(claudeKeyField.password)
        s.geminiApiKey = String(geminiKeyField.password)
        s.copilotMode  = if (cliRadio.isSelected) "CLI" else "CLIPBOARD"
    }

    private fun providerIndexToKey(idx: Int) = when (idx) {
        1    -> "CLAUDE"
        2    -> "GEMINI"
        else -> "COPILOT"
    }
}