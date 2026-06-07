package com.perfmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class MonitorPanel(
    private val project: Project,
    private val monitorName: String,
    private val onCapture: () -> String
) {

    val panel = JPanel(BorderLayout())

    private val outputArea = JBTextArea().apply {
        isEditable = false
        font = font.deriveFont(12f)
        text = "Press 'Start Session' to capture $monitorName data..."
    }

    private val statusLabel = JLabel("No device session active").apply {
        border = JBUI.Borders.emptyLeft(8)
    }

    private val startButton = JButton("▶ Start Session")
    private val clearButton = JButton("Clear")

    init {
        // Top toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(startButton)
            add(clearButton)
            add(statusLabel)
        }

        // Output area with scroll
        val scrollPane = JBScrollPane(outputArea)

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(4)

        // Button actions
        startButton.addActionListener {
            startButton.isEnabled = false
            statusLabel.text = "Capturing $monitorName data..."
            outputArea.text = "Running..."

            // Run on background thread so UI doesn't freeze
            Thread {
                val result = try {
                    onCapture()
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }

                // Update UI back on EDT
                javax.swing.SwingUtilities.invokeLater {
                    outputArea.text = result
                    outputArea.caretPosition = 0
                    statusLabel.text = "Last captured: ${java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}"
                    startButton.isEnabled = true
                }
            }.start()
        }

        clearButton.addActionListener {
            outputArea.text = ""
            statusLabel.text = "Cleared"
        }
    }
}