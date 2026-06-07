package com.perfmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class MonitorPanel(
    private val project: Project,
    private val monitorName: String,
    private val defaultPackage: String = "",
    private val onCapture: (packageName: String) -> String
) {

    val panel = JPanel(BorderLayout())

    private val outputArea = JBTextArea().apply {
        isEditable = false
        font = font.deriveFont(12f)
        text = "Select a process then press 'Start Session' to capture $monitorName data..."
    }

    private val statusLabel = JLabel("No session active").apply {
        border = JBUI.Borders.emptyLeft(8)
    }

    private val processCombo = JComboBox<String>().apply {
        preferredSize = java.awt.Dimension(280, 28)
        toolTipText = "Select the app process to monitor"
    }

    private val refreshButton  = JButton("⟳ Refresh")
    private val startButton    = JButton("▶ Start Session")
    private val clearButton = JButton("Clear").apply {
        isEnabled = false
    }

    init {
        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Process:"))
            add(processCombo)
            add(refreshButton)
            add(JSeparator(SwingConstants.VERTICAL).apply {
                preferredSize = java.awt.Dimension(2, 24)
            })
            add(startButton)
            add(clearButton)
            add(statusLabel)
        }

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(outputArea), BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(4)

        // Load processes immediately
        loadProcesses()

        // Button actions
        refreshButton.addActionListener { loadProcesses() }

        startButton.addActionListener {
            val selected = processCombo.selectedItem as? String
            if (selected.isNullOrBlank()) {
                statusLabel.text = "⚠ Select a process first"
                return@addActionListener
            }

            // Extract just the package name (strip PID prefix if present)
            val packageName = selected.trim().split("\\s+".toRegex()).last()

            startButton.isEnabled = false
            statusLabel.text = "Capturing..."
            outputArea.text = "Running adb capture for $packageName ..."

            Thread {
                val result = try {
                    onCapture(packageName)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                SwingUtilities.invokeLater {
                    outputArea.text = result
                    outputArea.caretPosition = 0
                    statusLabel.text = "Captured at ${java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}"
                    startButton.isEnabled = true
                    clearButton.isEnabled = true
                }
            }.start()
        }

        clearButton.addActionListener {
            outputArea.text = ""
            statusLabel.text = "Cleared"
            clearButton.isEnabled = false
        }
    }

    private fun loadProcesses() {
        refreshButton.isEnabled = false
        statusLabel.text = "Loading processes..."
        processCombo.removeAllItems()

        Thread {
            val processes = try {
                // Get all running processes on the device
                val output = ProcessBuilder("adb", "shell", "ps", "-e")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readLines()

                output
                    .drop(1) // skip header line
                    .mapNotNull { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        // ps output: USER PID PPID VSZ RSS WCHAN ADDR S NAME
                        parts.lastOrNull()?.takeIf { name ->
                            name.contains(".") // package names contain dots
                                    && !name.startsWith("[") // skip kernel threads
                        }
                    }
                    .distinct()
                    .sorted()
            } catch (e: Exception) {
                listOf("Error loading processes: ${e.message}")
            }

            SwingUtilities.invokeLater {
                processes.forEach { processCombo.addItem(it) }

                // Pre-select the project's package if found in the list
                if (defaultPackage.isNotBlank()) {
                    val match = processes.indexOfFirst { it.contains(defaultPackage) }
                    if (match >= 0) processCombo.selectedIndex = match
                }

                statusLabel.text = "${processes.size} processes found"
                refreshButton.isEnabled = true
            }
        }.start()
    }
}