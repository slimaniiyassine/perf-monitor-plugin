package com.perfmonitor

import com.intellij.openapi.options.Configurable
import javax.swing.*
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

class SettingsConfigurable : Configurable {

    private lateinit var providerCombo: JComboBox<String>
    private lateinit var claudeKeyField: JPasswordField
    private lateinit var geminiKeyField: JPasswordField
    private lateinit var claudeRow: JPanel
    private lateinit var geminiRow: JPanel
    private lateinit var copilotInfo: JLabel

    override fun getDisplayName() = "Perf Monitor"

    override fun createComponent(): JComponent {
        val settings = PerfMonitorSettings.instance()
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(6, 8, 6, 8)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        // ── Provider row ──────────────────────────────────────────
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("AI Provider:"), gbc)

        providerCombo = JComboBox(arrayOf(
            "GitHub Copilot Chat",
            "Claude (Anthropic)",
            "Gemini (Google AI Studio)"
        ))
        providerCombo.selectedIndex = when (settings.provider) {
            "CLAUDE"  -> 1
            "GEMINI"  -> 2
            else      -> 0
        }
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        panel.add(providerCombo, gbc)

        // ── Separator ─────────────────────────────────────────────
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2
        panel.add(JSeparator(), gbc)
        gbc.gridwidth = 1

        // ── Claude key row ────────────────────────────────────────
        claudeKeyField = JPasswordField(settings.claudeApiKey, 36)
        claudeRow = keyRow(
            panel, gbc, row = 2,
            label = "Claude API Key:",
            field = claudeKeyField,
            hint = "Get your key at <b>console.anthropic.com</b>"
        )

        // ── Gemini key row ────────────────────────────────────────
        geminiKeyField = JPasswordField(settings.geminiApiKey, 36)
        geminiRow = keyRow(
            panel, gbc, row = 4,
            label = "Gemini API Key:",
            field = geminiKeyField,
            hint = "Get your free key at <b>aistudio.google.com</b>"
        )

        // ── Copilot info ──────────────────────────────────────────
        copilotInfo = JLabel("""
            <html><small><b>Copilot:</b> the prompt is written to a .md file
            and opened in the editor — paste it into Copilot Chat.</small></html>
        """.trimIndent())
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2
        panel.add(copilotInfo, gbc)
        gbc.gridwidth = 1

        // ── Show/hide based on provider ───────────────────────────
        fun updateVisibility() {
            val provider = providerCombo.selectedIndex
            claudeRow.isVisible  = provider == 1
            geminiRow.isVisible  = provider == 2
            copilotInfo.isVisible = provider == 0
        }
        updateVisibility()
        providerCombo.addActionListener { updateVisibility() }

        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }

    private fun keyRow(
        panel: JPanel,
        gbc: GridBagConstraints,
        row: Int,
        label: String,
        field: JPasswordField,
        hint: String
    ): JPanel {
        val container = JPanel(BorderLayout())

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel(label), gbc)

        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        panel.add(field, gbc)

        gbc.gridx = 1; gbc.gridy = row + 1; gbc.weightx = 1.0
        val hintLabel = JLabel("<html><small>$hint</small></html>")
        panel.add(hintLabel, gbc)

        return container
    }

    override fun isModified(): Boolean {
        val s = PerfMonitorSettings.instance()
        val providerVal = when (providerCombo.selectedIndex) {
            1    -> "CLAUDE"
            2    -> "GEMINI"
            else -> "COPILOT"
        }
        return providerVal != s.provider ||
                String(claudeKeyField.password) != s.claudeApiKey ||
                String(geminiKeyField.password) != s.geminiApiKey
    }

    override fun apply() {
        val s = PerfMonitorSettings.instance()
        s.provider     = when (providerCombo.selectedIndex) {
            1    -> "CLAUDE"
            2    -> "GEMINI"
            else -> "COPILOT"
        }
        s.claudeApiKey = String(claudeKeyField.password)
        s.geminiApiKey = String(geminiKeyField.password)
    }
}