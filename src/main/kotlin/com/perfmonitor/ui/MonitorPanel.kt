package com.perfmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
        text = "Select a process and a mode, then start a session."
    }

    private val statusLabel = JLabel("Ready").apply {
        border = JBUI.Borders.emptyLeft(8)
    }

    private val processCombo = JComboBox<String>().apply {
        preferredSize = java.awt.Dimension(260, 28)
        toolTipText = "Select the app process to monitor"
    }

    // Mode toggle
    private val snapshotRadio = JRadioButton("Snapshot", true)
    private val recordRadio   = JRadioButton("Record")
    private val modeGroup     = ButtonGroup().also {
        it.add(snapshotRadio)
        it.add(recordRadio)
    }

    // Interval controls (record mode only)
    private val intervalCombo = JComboBox(arrayOf("1s", "2s", "5s", "10s", "30s", "Custom")).apply {
        preferredSize = java.awt.Dimension(80, 28)
    }
    private val customSpinner = JSpinner(SpinnerNumberModel(3, 1, 300, 1)).apply {
        preferredSize = java.awt.Dimension(60, 28)
        toolTipText = "Custom interval in seconds"
        isVisible = false
    }
    private val intervalUnit = JLabel("s").apply { isVisible = false }

    // Buttons
    private val refreshButton = JButton("⟳").apply {
        toolTipText = "Refresh process list"
    }
    private val startButton = JButton("▶ Start").apply { isEnabled = false }
    private val stopButton  = JButton("⏹ Stop").apply { isEnabled = false }
    private val clearButton = JButton("Clear").apply { isEnabled = false }

    // Recording state
    private var isRecording  = false
    private var recordThread: Thread? = null
    private val samples      = mutableListOf<String>()
    private val timeFmt      = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        // ── Toolbar row 1: process picker ──────────────────────────
        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("Process:"))
            add(processCombo)
            add(refreshButton)
        }

        // ── Toolbar row 2: mode + interval + buttons ───────────────
        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("Mode:"))
            add(snapshotRadio)
            add(recordRadio)
            add(JSeparator(SwingConstants.VERTICAL).apply {
                preferredSize = java.awt.Dimension(2, 22)
            })
            add(JLabel("Every:"))
            add(intervalCombo)
            add(customSpinner)
            add(intervalUnit)
            add(JSeparator(SwingConstants.VERTICAL).apply {
                preferredSize = java.awt.Dimension(2, 22)
            })
            add(startButton)
            add(stopButton)
            add(clearButton)
            add(statusLabel)
        }

        val toolbar = JPanel(BorderLayout()).apply {
            add(row1, BorderLayout.NORTH)
            add(row2, BorderLayout.SOUTH)
            border = JBUI.Borders.emptyBottom(4)
        }

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(outputArea), BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(4)

        // ── Initial state ───────────────────────────────────────────
        updateModeControls()
        snapshotRadio.addActionListener { updateModeControls() }
        recordRadio.addActionListener   { updateModeControls() }

        // ── Show custom spinner only when "Custom" preset selected ──
        intervalCombo.addActionListener {
            val isCustom = intervalCombo.selectedItem == "Custom"
            customSpinner.isVisible = isCustom
            intervalUnit.isVisible  = isCustom
        }

        // ── Load processes on startup ───────────────────────────────
        loadProcesses()
        refreshButton.addActionListener { loadProcesses() }

        // ── Enable start once a process is selected ─────────────────
        processCombo.addActionListener {
            startButton.isEnabled = processCombo.selectedItem != null
        }

        // ── Start button ────────────────────────────────────────────
        startButton.addActionListener {
            val pkg = selectedPackage() ?: return@addActionListener
            if (snapshotRadio.isSelected) runSnapshot(pkg)
            else startRecording(pkg)
        }

        // ── Stop button ─────────────────────────────────────────────
        stopButton.addActionListener { stopRecording() }

        // ── Clear button ────────────────────────────────────────────
        clearButton.addActionListener {
            outputArea.text = ""
            samples.clear()
            statusLabel.text = "Cleared"
            clearButton.isEnabled = false
        }
    }

    // ── Snapshot ─────────────────────────────────────────────────────
    private fun runSnapshot(pkg: String) {
        setCapturing(true)
        outputArea.text = "Taking snapshot for $pkg ..."

        Thread {
            val result = safeCapture(pkg)
            SwingUtilities.invokeLater {
                outputArea.text = result
                outputArea.caretPosition = 0
                statusLabel.text = "Snapshot at ${now()}"
                clearButton.isEnabled = true
                setCapturing(false)
            }
        }.start()
    }

    // ── Record ───────────────────────────────────────────────────────
    private fun startRecording(pkg: String) {
        isRecording = true
        samples.clear()
        outputArea.text = ""
        setRecording(true)
        statusLabel.text = "Recording $monitorName..."

        val intervalMs = if (intervalCombo.selectedItem == "Custom") {
            (customSpinner.value as Int) * 1000L
        } else {
            val preset = (intervalCombo.selectedItem as String).removeSuffix("s").toLong()
            preset * 1000L
        }

        recordThread = Thread {
            var sampleNum = 1
            while (isRecording) {
                val timestamp = now()
                val data = safeCapture(pkg)
                val sample = "── Sample $sampleNum @ $timestamp ──────────\n$data\n"
                samples.add(sample)

                SwingUtilities.invokeLater {
                    outputArea.append(sample)
                    outputArea.caretPosition = outputArea.document.length
                    statusLabel.text = "Recording — ${samples.size} samples captured"
                }

                sampleNum++
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true }

        recordThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        recordThread?.interrupt()
        SwingUtilities.invokeLater {
            statusLabel.text = "Stopped — ${samples.size} samples at ${now()}"
            clearButton.isEnabled = samples.isNotEmpty()
            setRecording(false)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────
    private fun safeCapture(pkg: String) = try {
        onCapture(pkg)
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    private fun selectedPackage(): String? {
        val item = processCombo.selectedItem as? String
        if (item.isNullOrBlank()) {
            statusLabel.text = "⚠ Select a process first"
            return null
        }
        return item.trim().split("\\s+".toRegex()).last()
    }

    private fun updateModeControls() {
        val isRecord = recordRadio.isSelected
        intervalCombo.isVisible   = isRecord
        customSpinner.isVisible   = isRecord && intervalCombo.selectedItem == "Custom"
        intervalUnit.isVisible    = isRecord && intervalCombo.selectedItem == "Custom"
        stopButton.isVisible      = isRecord
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
                ProcessBuilder("adb", "shell", "ps", "-e")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readLines()
                    .drop(1)
                    .mapNotNull { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        parts.lastOrNull()?.takeIf { name ->
                            name.contains(".") && !name.startsWith("[")
                        }
                    }
                    .distinct()
                    .sorted()
            } catch (e: Exception) {
                listOf("Error: ${e.message}")
            }

            SwingUtilities.invokeLater {
                processes.forEach { processCombo.addItem(it) }

                if (defaultPackage.isNotBlank()) {
                    val match = processes.indexOfFirst { it.contains(defaultPackage) }
                    if (match >= 0) processCombo.selectedIndex = match
                }

                startButton.isEnabled   = processCombo.selectedItem != null
                refreshButton.isEnabled = true
                statusLabel.text        = "${processes.size} processes found"
            }
        }.start()
    }

    // Expose captured data for Copilot prompt builder
    fun getCapturedData(): String = if (samples.isNotEmpty())
        samples.joinToString("\n") else outputArea.text
}